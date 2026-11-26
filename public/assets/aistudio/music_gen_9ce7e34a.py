import os
import time
import logging
import threading

import torch
import numpy as np
import scipy.io.wavfile as wavfile
from transformers import AutoProcessor, MusicgenForConditionalGeneration

import api_token

log = logging.getLogger("nime")

AUDIO_DIR = "generated_audio"
os.makedirs(AUDIO_DIR, exist_ok=True)

IS_TPU = False
try:
    import torch_xla.core.xla_model as xm

    DEVICE = xm.xla_device()
    IS_TPU = True
    DTYPE = torch.float32
except ImportError:
    DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    DTYPE = torch.float16 if torch.cuda.is_available() else torch.float32

MODEL_ID = getattr(api_token, "modello2", "musicgen-small")
TOKENS_PER_SECOND = 50
GUIDANCE_SCALE = 3.5

PROMPT_SUFFIX = ", ambient background drone, seamless, no drums, studio quality."

print(f"Caricamento MusicGen ({MODEL_ID}) su {DEVICE}...")


def _load_musicgen():
    proc = AutoProcessor.from_pretrained(MODEL_ID)
    if str(DEVICE).startswith("cuda"):
        mdl = MusicgenForConditionalGeneration.from_pretrained(MODEL_ID, dtype=DTYPE, device_map="auto")
    else:
        mdl = MusicgenForConditionalGeneration.from_pretrained(MODEL_ID, dtype=DTYPE).to(DEVICE)
    return proc, mdl


processor, model = _load_musicgen()
model.eval()
SAMPLE_RATE = model.config.audio_encoder.sampling_rate
modello_lock = threading.Lock()

print("MUSIC GEN (Backing Track Engine) READY")


def generate_backing_track(prompt: str, duration_s: float = 20.0, tag: str = "-",
                           abort_event: threading.Event = None) -> np.ndarray:
    """Genera una singola traccia audio lunga da usare in loop."""
    full_prompt = prompt.rstrip(" .,") + PROMPT_SUFFIX
    max_new_tokens = int(duration_s * TOKENS_PER_SECOND)

    log.info("[%s] MusicGen Prompt Backing: %s", tag, full_prompt)

    inputs = processor(text=[full_prompt], padding=True, return_tensors="pt").to(DEVICE)

    with modello_lock:
        with torch.no_grad():
            audio_values = model.generate(
                **inputs, do_sample=True, guidance_scale=GUIDANCE_SCALE, max_new_tokens=max_new_tokens,
            )
            if IS_TPU:
                import torch_xla.core.xla_model as xm
                xm.mark_step()

    audio_np = audio_values.cpu().float().numpy()[0]
    if audio_np.ndim > 1:
        audio_np = np.mean(audio_np, axis=0)  # Converti stereo a mono per sicurezza

    return audio_np


def save_wav(audio_np: np.ndarray, job_id: str, name: str, base_url: str, tag: str = "-") -> str:
    # Aggiungiamo un fade in/out generoso (1 sec) per aiutare il loop
    sr = SAMPLE_RATE
    audio_np = audio_np.astype(np.float32).copy()
    audio_np -= np.mean(audio_np)

    fade = min(sr, len(audio_np) // 4)
    audio_np[:fade] *= np.linspace(0.0, 1.0, fade, dtype=np.float32)
    audio_np[-fade:] *= np.linspace(1.0, 0.0, fade, dtype=np.float32)

    peak = np.max(np.abs(audio_np))
    if peak > 0: audio_np = (audio_np / peak) * 0.9

    file_name = f"{job_id}_{name}.wav"
    file_path = os.path.join(AUDIO_DIR, file_name)
    wavfile.write(file_path, rate=SAMPLE_RATE, data=(audio_np * 32767).astype(np.int16))

    return f"{base_url.rstrip('/')}/audio/{file_name}"