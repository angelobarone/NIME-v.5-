import os
import sys
import time
import uuid
import logging
from logging.handlers import RotatingFileHandler
from contextlib import asynccontextmanager
from typing import Optional, Dict

import torch
import transformers
from fastapi import FastAPI, BackgroundTasks, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from pyngrok import ngrok

import hashlib
import json
import shutil
import threading
from pathlib import Path

import llm_gen
import music_gen

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
# Livello configurabile: PowerShell -> $env:LOG_LEVEL="DEBUG"
# INFO  = ciclo di vita di server, richieste e job
# DEBUG = in più: polling di /status e /health, output grezzo dell'LLM
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
LOG_FILE = os.getenv("LOG_FILE", "nime_backend.log")

log = logging.getLogger("nime")
log.setLevel(LOG_LEVEL)
log.propagate = False  # evita righe doppie con i logger di uvicorn

_formatter = logging.Formatter(
    "%(asctime)s | %(levelname)-7s | %(threadName)-12s | %(message)s",
    datefmt="%H:%M:%S",
)
_console = logging.StreamHandler(sys.stdout)
_console.setFormatter(_formatter)
log.addHandler(_console)

_file_handler = RotatingFileHandler(LOG_FILE, maxBytes=2_000_000, backupCount=3, encoding="utf-8")
_file_handler.setFormatter(_formatter)
log.addHandler(_file_handler)


def jid(job_id: str) -> str:
    """ID breve del job per le righe di log."""
    return job_id[:8]


# ---------------------------------------------------------------------------
# Configurazione
# ---------------------------------------------------------------------------
AUDIO_DIR = music_gen.AUDIO_DIR
NOTES = llm_gen.NOTES

NGROK_PORT = 8000
NGROK_DOMAIN = os.getenv("NGROK_DOMAIN")
PUBLIC_URL: Optional[str] = None

jobs: Dict[str, dict] = {}
abort_events: Dict[str, threading.Event] = {}


# ---------------------------------------------------------------------------
# Sistema di Caching
# ---------------------------------------------------------------------------
CACHE_DIR = Path("cache_audio")
CACHE_DIR.mkdir(exist_ok=True)
CACHE_MAX_MB = 500

class MultisampleCache:
    """
    Cache per le note audio generate, organizzata per hash del risultato LLM.
    Se l'LLM categorizza un mood allo stesso modo, riutilizza i file esistenti.
    """
    def __init__(self, max_mb: float = CACHE_MAX_MB):
        self._index: Dict[str, Dict[str, Path]] = {}
        self._lock = threading.Lock()
        self._max_bytes = max_mb * 1024 * 1024
        self._rebuild_index()

    def _rebuild_index(self):
        for cat_dir in sorted(CACHE_DIR.iterdir()):
            if cat_dir.is_dir():
                chunks = {p.stem: p for p in cat_dir.glob("*.wav")}
                if len(chunks) > 0:
                    self._index[cat_dir.name] = chunks
                    log.info(f"[CACHE] Categoria LLM '{cat_dir.name[:8]}' caricata: {len(chunks)} note")

    def _total_bytes(self) -> int:
        total = 0
        for chunks in self._index.values():
            for path in chunks.values():
                if path.exists():
                    total += path.stat().st_size
        return total

    def _evict_oldest(self):
        while self._total_bytes() > self._max_bytes:
            all_cats = []
            for cat, chunks in self._index.items():
                if chunks:
                    first_chunk = list(chunks.values())[0]
                    if first_chunk.exists():
                        all_cats.append((cat, first_chunk.stat().st_mtime))

            if not all_cats:
                break

            oldest_cat, _ = min(all_cats, key=lambda x: x[1])
            for path in self._index[oldest_cat].values():
                path.unlink(missing_ok=True)
            
            cat_dir = CACHE_DIR / oldest_cat
            if cat_dir.exists():
                try:
                    cat_dir.rmdir()
                except OSError:
                    pass
            del self._index[oldest_cat]
            log.info(f"[CACHE] Eviction: rimossa categoria LLM '{oldest_cat[:8]}' (soglia MB superata)")

    def get(self, cache_key: str) -> Optional[Dict[str, Path]]:
        with self._lock:
            chunks = self._index.get(cache_key)
            if not chunks:
                return None
            # Verifica che tutte le note previste esistano fisicamente
            if all(p.exists() for p in chunks.values()) and len(chunks) == len(NOTES):
                return chunks
            else:
                del self._index[cache_key]
                return None

    def add(self, cache_key: str, src_paths: Dict[str, Path]):
        with self._lock:
            cat_dir = CACHE_DIR / cache_key
            cat_dir.mkdir(exist_ok=True)

            dst_paths = {}
            now_ts = time.time()
            for name, src_path in src_paths.items():
                dst = cat_dir / f"{name}.wav"
                shutil.copy2(src_path, dst)
                os.utime(dst, (now_ts, now_ts))
                dst_paths[name] = dst

            self._index[cache_key] = dst_paths
            log.info(f"[CACHE] Salvate {len(dst_paths)} note per la categoria LLM '{cache_key[:8]}'")
            self._evict_oldest()

audio_cache = MultisampleCache()

def _generate_cache_key(gemma_data: dict, duration: float) -> str:
    """Crea una firma MD5 univoca basata sulla categoria generata dall'LLM e la durata."""
    category = gemma_data.get("category", "unknown_category")
    # Raggruppa i file in base alla macro-categoria invece che all'esatto prompt testuale
    key_str = f"{category}_{int(duration)}s"
    return hashlib.md5(key_str.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Startup
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    global PUBLIC_URL

    t_start = time.perf_counter()
    log.info("=" * 60)
    log.info("NIME backend in avvio")
    log.info("Python %s | torch %s | transformers %s",
             sys.version.split()[0], torch.__version__, transformers.__version__)
    if music_gen.DEVICE == "cuda":
        log.info("Device: cuda (%s) | dtype=%s", torch.cuda.get_device_name(0), music_gen.DTYPE)
    else:
        log.info("Device: cpu | dtype=%s | thread torch=%d", music_gen.DTYPE, torch.get_num_threads())
    log.info("Livello di log: %s | file di log: %s", LOG_LEVEL, os.path.abspath(LOG_FILE))
    log.info("Cartella audio: %s", os.path.abspath(AUDIO_DIR))
    log.info("LLM configurato: API online tramite modulo llm_gen (%s)", llm_gen.MODEL_ID)
    log.info("MusicGen pronto (%s) | %.0fM parametri | sample rate %d Hz",
             music_gen.MODEL_ID, music_gen.N_PARAMS_M, music_gen.SAMPLE_RATE)

    # --- Tunnel ngrok ---
    log.info("[1/1] Avvio tunnel ngrok sulla porta %d...", NGROK_PORT)
    try:
        token = os.getenv("NGROK_AUTHTOKEN")
        if token:
            ngrok.set_auth_token(token)
            log.info("[1/1] Token ngrok trovato nelle variabili d'ambiente")
        else:
            log.warning("[1/1] NGROK_AUTHTOKEN non impostato: uso la configurazione ngrok esistente")
        options = {"domain": NGROK_DOMAIN} if NGROK_DOMAIN else {}
        if NGROK_DOMAIN:
            log.info("[1/1] Dominio statico richiesto: %s", NGROK_DOMAIN)
        tunnel = ngrok.connect(NGROK_PORT, "http", **options)
        PUBLIC_URL = tunnel.public_url
        log.info("[1/1] Tunnel attivo -> URL pubblico: %s", PUBLIC_URL)
    except Exception:
        log.exception("[1/1] Tunnel ngrok NON avviato: il backend resta raggiungibile solo in locale")

    log.info("Backend pronto in %.1fs | in ascolto su http://127.0.0.1:%d",
             time.perf_counter() - t_start, NGROK_PORT)
    log.info("=" * 60)

    yield

    log.info("Spegnimento backend: chiudo il tunnel ngrok...")
    ngrok.kill()
    log.info("Backend spento")


app = FastAPI(title="NIME Backend", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/audio", StaticFiles(directory=AUDIO_DIR), name="audio")


@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Registra ogni richiesta HTTP con esito e durata."""
    t0 = time.perf_counter()
    client = request.client.host if request.client else "?"
    path = request.url.path

    # Il polling di /status e /health è molto frequente: va solo a livello DEBUG
    noisy = path.startswith("/status") or path == "/health"
    level = logging.DEBUG if noisy else logging.INFO

    try:
        response = await call_next(request)
    except Exception:
        log.exception("%s %s da %s -> ERRORE NON GESTITO", request.method, path, client)
        raise

    elapsed_ms = (time.perf_counter() - t0) * 1000
    log.log(level, "HTTP %s %s da %s -> %d (%.0f ms)",
            request.method, path, client, response.status_code, elapsed_ms)
    return response


# ---------------------------------------------------------------------------
# Schemi
# ---------------------------------------------------------------------------
class MusicGenRequest(BaseModel):
    prompt: str
    duration: int = 4
    model: str = music_gen.MODEL_ID  # mantenuto per compatibilità col client
    temperature: float = 1.0


class MusicGenResponse(BaseModel):
    job_id: str
    status: str
    audio_url: Optional[str] = None
    audio_urls: Optional[Dict[str, str]] = None
    progress: Optional[float] = None
    message: Optional[str] = None
    scale: Optional[str] = None
    base_cutoff_hz: Optional[int] = None


class HealthResponse(BaseModel):
    status: str
    device: Optional[str] = None
    model_loaded: Optional[bool] = True


# ---------------------------------------------------------------------------
# Gestione job
# ---------------------------------------------------------------------------
def update_job(job_id: str, **fields):
    """Aggiorna lo stato del job e registra le transizioni."""
    job = jobs[job_id]
    old_status = job.get("status")
    job.update(fields)

    if "status" in fields and fields["status"] != old_status:
        log.info("[%s] stato: %s -> %s", jid(job_id), old_status, fields["status"])
    if "progress" in fields or "message" in fields:
        progress = job.get("progress") or 0.0
        log.info("[%s] %3.0f%% | %s", jid(job_id), progress * 100, job.get("message"))


# ---------------------------------------------------------------------------
# Job
# ---------------------------------------------------------------------------
def run_multisample_job(job_id: str, req: MusicGenRequest, base_url: str):
    tag = jid(job_id)
    t_job = time.perf_counter()
    abort_evt = abort_events.get(job_id)
    try:
        update_job(job_id, status="processing", progress=0.1,
                   message="L'LLM online sta progettando il timbro del multisample...")

        # 1. Traduzione del mood in un unico timbro clonato sulle note
        gemma_data = llm_gen.translate_mood_to_multisample(req.prompt, tag)
        
        if abort_evt and abort_evt.is_set():
            update_job(job_id, status="cancelled", message="Generazione annullata dall'utente (post-LLM).")
            return

        layers_dict: Dict[str, str] = gemma_data["layers"]

        update_job(job_id, scale=gemma_data["scale"], base_cutoff_hz=gemma_data["base_cutoff_hz"])

        duration = float(max(music_gen.MIN_DURATION, min(req.duration, music_gen.MAX_DURATION)))
        if duration != req.duration:
            log.info("[%s] durata richiesta %ds fuori range -> uso %.0fs", tag, req.duration, duration)
        
        # --- Controllo Cache ---
        cache_key = _generate_cache_key(gemma_data, duration)
        cached_chunks = audio_cache.get(cache_key)
        
        generated_urls: Dict[str, str] = {}
        source_paths_for_cache: Dict[str, Path] = {}

        if cached_chunks:
            log.info("[%s] Cache HIT per categoria LLM '%s': riuso file generati", tag, cache_key[:8])
            update_job(job_id, progress=0.8, message="Corrispondenza LLM in cache trovata, recupero file...")
            
            for name, cached_path in cached_chunks.items():
                if abort_evt and abort_evt.is_set():
                    update_job(job_id, status="cancelled", message="Generazione annullata.")
                    return
                # Copia dalla cache alla cartella di output del server FastAPI
                session_filename = f"{job_id}_{name}.wav"
                session_path = Path(AUDIO_DIR) / session_filename
                shutil.copy2(cached_path, session_path)
                
                generated_urls[name] = f"{base_url.rstrip('/')}/audio/{session_filename}"
        else:
            log.info("[%s] Cache MISS per categoria LLM '%s': avvio sintesi audio", tag, cache_key[:8])
            # 2. Generazione audio tramite music_gen
            if music_gen.DEVICE == "cuda":
                # GPU: gestisce internamente la suddivisione in batch per non saturare la VRAM
                update_job(job_id, progress=0.4, message=f"Generazione parallela di {len(NOTES)} note (GPU)...")
                prompts = [layers_dict[n] for n in NOTES]
                audios = music_gen.generate_audio(prompts, duration, tag, abort_event=abort_evt)
                
                if abort_evt and abort_evt.is_set():
                    update_job(job_id, status="cancelled", message="Generazione annullata durante l'elaborazione GPU.")
                    return
                    
                for name, audio_np in zip(NOTES, audios):
                    generated_urls[name] = music_gen.save_wav(audio_np, job_id, name, base_url, tag)
                    source_paths_for_cache[name] = Path(AUDIO_DIR) / f"{job_id}_{name}.wav"
            else:
                # CPU: una nota alla volta, con progresso dettagliato
                for idx, name in enumerate(NOTES):
                    if abort_evt and abort_evt.is_set():
                        update_job(job_id, status="cancelled", message="Generazione annullata durante l'elaborazione CPU.")
                        return
                    prog = round(0.2 + (idx / len(NOTES)) * 0.7, 2)
                    update_job(job_id, progress=prog,
                               message=f"Generazione nota {idx + 1}/{len(NOTES)} ({name})...")
                    audio_np = music_gen.generate_audio([layers_dict[name]], duration, tag, abort_event=abort_evt)[0]
                    generated_urls[name] = music_gen.save_wav(audio_np, job_id, name, base_url, tag)
                    source_paths_for_cache[name] = Path(AUDIO_DIR) / f"{job_id}_{name}.wav"
            
            # 3. Salva i file appena generati nella cache
            if not (abort_evt and abort_evt.is_set()):
                audio_cache.add(cache_key, source_paths_for_cache)

        if abort_evt and abort_evt.is_set():
            update_job(job_id, status="cancelled", message="Generazione annullata prima della fine.")
            return

        # audio_url fa da fallback per client legacy: ora punta alla nota fondamentale (primo elemento)
        primary_note = NOTES[0]
        update_job(job_id, status="completed", progress=1.0, message=f"Multisample con {len(NOTES)} note pronto!",
                   audio_urls=generated_urls, audio_url=generated_urls[primary_note])
        log.info("[%s] JOB COMPLETATO in %.1fs | %d file pronti", tag, time.perf_counter() - t_job, len(NOTES))

    except Exception as e:
        log.exception("[%s] JOB FALLITO dopo %.1fs", tag, time.perf_counter() - t_job)
        update_job(job_id, status="failed", message=f"Errore: {str(e)}")
    finally:
        # Pulizia dell'evento per evitare memory leak
        abort_events.pop(job_id, None)


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------
@app.get("/health", response_model=HealthResponse)
async def check_health():
    return HealthResponse(
        status="ok" if music_gen.MODEL_LOADED else "loading",
        device=music_gen.DEVICE,
        model_loaded=music_gen.MODEL_LOADED,
    )


@app.post("/generate", response_model=MusicGenResponse)
async def generate_music(req: MusicGenRequest, background_tasks: BackgroundTasks, request: Request):
    job_id = str(uuid.uuid4())
    base_url = PUBLIC_URL or str(request.base_url)

    log.info("[%s] NUOVO JOB | prompt='%s' | durata=%ds | base_url=%s",
             jid(job_id), req.prompt, req.duration, base_url)
    if not music_gen.MODEL_LOADED:
        log.warning("[%s] richiesta ricevuta mentre MusicGen non è ancora caricato", jid(job_id))

    jobs[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "audio_url": None,
        "audio_urls": None,
        "progress": 0.0,
        "message": "In coda...",
        "scale": None,
        "base_cutoff_hz": None,
    }
    abort_events[job_id] = threading.Event()

    background_tasks.add_task(run_multisample_job, job_id, req, base_url)
    return MusicGenResponse(**jobs[job_id])


@app.get("/status/{job_id}", response_model=MusicGenResponse)
async def get_job_status(job_id: str):
    if job_id not in jobs:
        log.warning("[%s] status richiesto per un job inesistente", jid(job_id))
        return MusicGenResponse(job_id=job_id, status="failed", message="Job non trovato")
    return MusicGenResponse(**jobs[job_id])

@app.post("/abort/{job_id}")
async def abort_job(job_id: str):
    """Interrompe un job in esecuzione."""
    if job_id in abort_events:
        abort_events[job_id].set()
        log.info("[%s] Richiesto abort dall'utente", jid(job_id))
        return {"status": "cancelling", "message": "Richiesta di interruzione inviata"}
    
    if job_id in jobs:
        return {"status": jobs[job_id]["status"], "message": "Job già terminato o non interrompibile"}
        
    return {"status": "not_found", "message": "Job non trovato"}