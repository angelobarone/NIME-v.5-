import os
import uuid
import json
import torch
import numpy as np
import scipy.io.wavfile as wavfile
from fastapi import FastAPI, BackgroundTasks, Request
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from typing import Optional, Dict
from transformers import pipeline
from diffusers import StableAudioPipeline

app = FastAPI(title="NIME Backend")

AUDIO_DIR = "generated_audio"
os.makedirs(AUDIO_DIR, exist_ok=True)
app.mount("/audio", StaticFiles(directory=AUDIO_DIR), name="audio")

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
DTYPE = torch.float16 if DEVICE == "cuda" else torch.float32

gemma_pipe = None
audio_pipe = None
MODEL_LOADED = False
jobs: Dict[str, dict] = {}

# Prompt di sistema per far generare a Gemma i 4 layer timbrici coordinati
SYSTEM_PROMPT = """You are a sound design architect for a Vector Synthesis musical instrument.
Given a user's mood, return ONLY a valid JSON object with:
1. "scale": Best musical scale ("minor_pentatonic", "major_pentatonic", "dorian", "lydian", "phrygian").
2. "base_cutoff_hz": Integer between 400 and 6000.
3. "layers": An object with 4 technical prompts for Stable Audio (all tuned to note C, no drums, no percussion, no melody):
   - "low_drone": Deep bass drone/sub pad in C2.
   - "mid_pad": Warm harmonic synth pad texture in C3.
   - "high_shimmer": Bright airy/ethereal upper harmonic texture in C4.
   - "lead_timbre": Expressive resonant lead synth timbre in C4.
Respond ONLY with raw JSON."""


@app.on_event("startup")
def load_models():
    global gemma_pipe, audio_pipe, MODEL_LOADED
    print(f"[NIME] Caricamento Gemma + Stable Audio su {DEVICE}...")

    gemma_pipe = pipeline(
        "text-generation",
        model="google/gemma-2-2b-it",
        model_kwargs={"torch_dtype": DTYPE},
        device=DEVICE,
    )

    audio_pipe = StableAudioPipeline.from_pretrained(
        "stabilityai/stable-audio-open-small",
        torch_dtype=DTYPE,
    ).to(DEVICE)

    MODEL_LOADED = True
    print("[NIME] Modelli pronti per Multi-Sample Generation!")


class MusicGenRequest(BaseModel):
    prompt: str
    duration: int = 4
    model: str = "stabilityai/stable-audio-open-small"
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


def translate_mood_to_4_layers(user_mood: str) -> dict:
    messages = [
        {"role": "user", "content": f"{SYSTEM_PROMPT}\n\nUser Mood: '{user_mood}'"}
    ]
    try:
        out = gemma_pipe(messages, max_new_tokens=280, do_sample=False)
        raw_text = out[0]["generated_text"][-1]["content"].strip()
        clean_json = raw_text.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        data = json.loads(clean_json)
        if "layers" in data and len(data["layers"]) == 4:
            return data
    except Exception as e:
        print(f"[Warn] Fallback Gemma JSON parsing: {e}")

    # Fallback sicuro se Gemma sbaglia la formattazione JSON
    m = user_mood.strip()
    return {
        "scale": "minor_pentatonic",
        "base_cutoff_hz": 1800,
        "layers": {
            "low_drone": f"{m} deep analog bass drone, sustained C2 note, dark sub texture, no drums, no percussion",
            "mid_pad": f"{m} warm lush synthesizer pad, sustained C3 note, harmonic drone, no drums, no melody",
            "high_shimmer": f"{m} bright shimmering ambient texture, airy C4 sustained pad, granular glassy synth, no drums",
            "lead_timbre": f"{m} expressive resonant synth lead timbre, single sustained C4 note, pure waveform, no drums",
        },
    }


def apply_fade_and_normalize(audio_np: np.ndarray, sr: int) -> np.ndarray:
    fade_samples = int(0.05 * sr)
    if len(audio_np) > fade_samples * 2:
        fade_in = np.linspace(0.0, 1.0, fade_samples, dtype=np.float32)
        fade_out = np.linspace(1.0, 0.0, fade_samples, dtype=np.float32)
        audio_np[:fade_samples] *= fade_in
        audio_np[-fade_samples:] *= fade_out

    peak = np.max(np.abs(audio_np))
    if peak > 0:
        audio_np = (audio_np / peak) * 0.9
    return audio_np


def run_multisample_job(job_id: str, req: MusicGenRequest, base_url: str):
    try:
        # 1. Gemma genera i 4 prompt per i quadranti X/Y
        jobs[job_id]["status"] = "processing"
        jobs[job_id]["progress"] = 0.15
        jobs[job_id]["message"] = "Gemma sta progettando i 4 livelli timbrici..."

        gemma_data = translate_mood_to_4_layers(req.prompt)
        layers_dict: Dict[str, str] = gemma_data["layers"]

        jobs[job_id]["scale"] = gemma_data.get("scale", "minor_pentatonic")
        jobs[job_id]["base_cutoff_hz"] = gemma_data.get("base_cutoff_hz", 1800)

        target_duration = float(max(2, min(req.duration, 6)))
        sr = audio_pipe.vae.sampling_rate
        neg_prompt = "drums, percussion, beat, rhythm, melody, chord progression, vocals, clicks"

        layer_names = list(layers_dict.keys())  # ["low_drone", "mid_pad", "high_shimmer", "lead_timbre"]
        layer_prompts = [layers_dict[k] for k in layer_names]

        # 2. Generazione dei 4 campioni
        # Se siamo su GPU (CUDA), generiamo tutti e 4 i campioni IN PARALLELO passando la lista di prompt!
        # Se siamo su CPU, li generiamo uno alla volta aggiornando la barra di progresso.
        generated_urls = {}

        if DEVICE == "cuda":
            jobs[job_id]["progress"] = 0.4
            jobs[job_id]["message"] = "Generazione parallela dei 4 timbri (Batch GPU)..."
            with torch.no_grad():
                batch_out = audio_pipe(
                    prompt=layer_prompts,
                    negative_prompt=[neg_prompt] * 4,
                    num_inference_steps=8,
                    audio_end_in_s=target_duration,
                ).audios

            for idx, name in enumerate(layer_names):
                audio_np = batch_out[idx].cpu().float().numpy()
                if audio_np.ndim > 1:
                    audio_np = np.mean(audio_np, axis=0)
                audio_np = apply_fade_and_normalize(audio_np, sr)

                file_name = f"{job_id}_{name}.wav"
                file_path = os.path.join(AUDIO_DIR, file_name)
                wavfile.write(file_path, rate=sr, data=(audio_np * 32767).astype(np.int16))
                generated_urls[name] = f"{base_url.rstrip('/')}/audio/{file_name}"
        else:
            # Su CPU: ciclo sequenziale con progresso dettagliato (0.25 -> 0.50 -> 0.75 -> 0.95)
            for idx, name in enumerate(layer_names):
                jobs[job_id]["progress"] = round(0.2 + (idx * 0.2), 2)
                jobs[job_id]["message"] = f"Generazione layer {idx+1}/4 ({name})..."

                with torch.no_grad():
                    audio_out = audio_pipe(
                        prompt=layers_dict[name],
                        negative_prompt=neg_prompt,
                        num_inference_steps=8,
                        audio_end_in_s=target_duration,
                    ).audios[0]

                audio_np = audio_out.cpu().float().numpy()
                if audio_np.ndim > 1:
                    audio_np = np.mean(audio_np, axis=0)
                audio_np = apply_fade_and_normalize(audio_np, sr)

                file_name = f"{job_id}_{name}.wav"
                file_path = os.path.join(AUDIO_DIR, file_name)
                wavfile.write(file_path, rate=sr, data=(audio_np * 32767).astype(np.int16))
                generated_urls[name] = f"{base_url.rstrip('/')}/audio/{file_name}"

        # 3. Job completato
        jobs[job_id]["status"] = "completed"
        jobs[job_id]["progress"] = 1.0
        jobs[job_id]["message"] = "Pack di 4 timbri pronto!"
        jobs[job_id]["audio_urls"] = generated_urls
        jobs[job_id]["audio_url"] = generated_urls["mid_pad"]  # Fallback singolo

    except Exception as e:
        jobs[job_id]["status"] = "failed"
        jobs[job_id]["message"] = f"Errore: {str(e)}"


@app.get("/health", response_model=HealthResponse)
async def check_health():
    return HealthResponse(status="ok" if MODEL_LOADED else "loading", device=DEVICE, model_loaded=MODEL_LOADED)


@app.post("/generate", response_model=MusicGenResponse)
async def generate_music(req: MusicGenRequest, background_tasks: BackgroundTasks, request: Request):
    job_id = str(uuid.uuid4())
    base_url = str(request.base_url)

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

    background_tasks.add_task(run_multisample_job, job_id, req, base_url)
    return MusicGenResponse(**jobs[job_id])


@app.get("/status/{job_id}", response_model=MusicGenResponse)
async def get_job_status(job_id: str):
    if job_id not in jobs:
        return MusicGenResponse(job_id=job_id, status="failed", message="Job non trovato")
    return MusicGenResponse(**jobs[job_id])