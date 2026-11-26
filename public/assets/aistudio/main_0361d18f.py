import os
import sys
import time
import uuid
import logging
from logging.handlers import RotatingFileHandler
from contextlib import asynccontextmanager
from typing import Optional, Dict

import torch
from fastapi import FastAPI, BackgroundTasks, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from pyngrok import ngrok

import threading

import llm_gen
import music_gen  # Re-importato per la generazione del sottofondo

LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
LOG_FILE = os.getenv("LOG_FILE", "nime_backend.log")

log = logging.getLogger("nime")
log.setLevel(LOG_LEVEL)
log.propagate = False

_formatter = logging.Formatter("%(asctime)s | %(levelname)-7s | %(threadName)-12s | %(message)s", datefmt="%H:%M:%S")
_console = logging.StreamHandler(sys.stdout)
_console.setFormatter(_formatter)
log.addHandler(_console)


def jid(job_id: str) -> str: return job_id[:8]


NGROK_PORT = 8000
NGROK_DOMAIN = os.getenv("NGROK_DOMAIN")
PUBLIC_URL: Optional[str] = None
AUDIO_DIR = music_gen.AUDIO_DIR

jobs: Dict[str, dict] = {}
abort_events: Dict[str, threading.Event] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    global PUBLIC_URL
    log.info("NIME backend in avvio - MODALITA' IBRIDA (Synth Live + Backing Track AI)")

    try:
        token = os.getenv("NGROK_AUTHTOKEN")
        if token: ngrok.set_auth_token(token)
        options = {"domain": NGROK_DOMAIN} if NGROK_DOMAIN else {}
        tunnel = ngrok.connect(NGROK_PORT, "http", **options)
        PUBLIC_URL = tunnel.public_url
        log.info("Tunnel attivo -> URL pubblico: %s", PUBLIC_URL)
    except Exception:
        log.info("Tunnel ngrok NON avviato, solo locale.")
    yield
    ngrok.kill()


app = FastAPI(title="NIME Backend", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.mount("/audio", StaticFiles(directory=AUDIO_DIR), name="audio")


class MusicGenRequest(BaseModel):
    prompt: str


class MusicGenResponse(BaseModel):
    job_id: str
    status: str
    progress: Optional[float] = None
    scale: Optional[str] = None
    synth_params: Optional[dict] = None
    effects: Optional[dict] = None
    backing_track_url: Optional[str] = None


class HealthResponse(BaseModel):
    status: str
    device: str


def update_job(job_id: str, **fields):
    jobs[job_id].update(fields)


def run_backing_track_job(job_id: str, prompt: str, base_url: str):
    """Genera 20 secondi di audio drone in background con MusicGen."""
    tag = jid(job_id)
    t_job = time.perf_counter()
    abort_evt = abort_events.get(job_id)
    try:
        log.info("[%s] Avvio generazione Backing Track in background...", tag)
        # Genera una singola traccia di 20 secondi
        audio_np = music_gen.generate_backing_track(prompt, duration_s=20.0, tag=tag, abort_event=abort_evt)

        if abort_evt and abort_evt.is_set():
            update_job(job_id, status="cancelled")
            return

        url = music_gen.save_wav(audio_np, job_id, "backing_track", base_url, tag)
        update_job(job_id, status="completed", progress=1.0, backing_track_url=url)
        log.info("[%s] BACKING TRACK PRONTA in %.1fs -> %s", tag, time.perf_counter() - t_job, url)
    except Exception as e:
        log.exception("[%s] Errore Backing Track", tag)
        update_job(job_id, status="failed")


@app.get("/health", response_model=HealthResponse)
async def check_health():
    return HealthResponse(status="ok", device=str(music_gen.DEVICE))


@app.post("/generate", response_model=MusicGenResponse)
async def generate_music(req: MusicGenRequest, background_tasks: BackgroundTasks, request: Request):
    job_id = str(uuid.uuid4())
    base_url = PUBLIC_URL or str(request.base_url)
    tag = jid(job_id)

    # 1. Chiamata sincrona e veloce all'LLM (1-2 secondi)
    llm_data = llm_gen.translate_mood_to_multisample(req.prompt, tag)

    jobs[job_id] = {
        "job_id": job_id,
        "status": "generating_background",  # Stato intermedio: synth pronto, background in elaborazione
        "progress": 0.5,
        "scale": llm_data.get("scale"),
        "synth_params": llm_data.get("synth_params"),
        "effects": llm_data.get("effects"),
        "backing_track_url": None
    }
    abort_events[job_id] = threading.Event()

    # 2. Avvia la generazione audio pesante in background
    backing_prompt = llm_data.get("backing_track_prompt", req.prompt + " ambient texture")
    background_tasks.add_task(run_backing_track_job, job_id, backing_prompt, base_url)

    # 3. Ritorna IMMEDIATAMENTE i parametri all'App, così l'utente può iniziare a suonare
    return MusicGenResponse(**jobs[job_id])


@app.get("/status/{job_id}", response_model=MusicGenResponse)
async def get_job_status(job_id: str):
    if job_id not in jobs:
        return MusicGenResponse(job_id=job_id, status="failed")
    return MusicGenResponse(**jobs[job_id])