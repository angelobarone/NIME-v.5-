import os
import time
import logging
import threading
from typing import List

import torch
import numpy as np
import scipy.io.wavfile as wavfile
from transformers import AutoProcessor, MusicgenForConditionalGeneration

import api_token

log = logging.getLogger("nime")

# ---------------------------------------------------------------------------
# Configurazione e Inizializzazione Globale
# ---------------------------------------------------------------------------
AUDIO_DIR = "generated_audio"
os.makedirs(AUDIO_DIR, exist_ok=True)

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
DTYPE = torch.float16 if DEVICE == "cuda" else torch.float32

# Prende l'ID del modello da api_token (con fallback a musicgen-small)
MODEL_ID = getattr(api_token, "modello2", "musicgen-stereo-small")

TOKENS_PER_SECOND = 50      # frame rate di EnCodec in MusicGen
MIN_DURATION = 2
MAX_DURATION = 6
GUIDANCE_SCALE = 3.0

# Suffisso aggiunto a ogni prompt per garantire qualità e stabilità.
PROMPT_SUFFIX = ", high quality, studio recording"

print(f"Accelerazione hardware attivata sul dispositivo: {DEVICE}")
print(f"Caricamento del modello MusicGen ({MODEL_ID})...")

t0 = time.perf_counter()

def _load_musicgen(local_only: bool):
    proc = AutoProcessor.from_pretrained(MODEL_ID, local_files_only=local_only)
    if DEVICE == "cuda":
        mdl = MusicgenForConditionalGeneration.from_pretrained(
            MODEL_ID,
            dtype=DTYPE,
            device_map="auto",
            local_files_only=local_only,
        )
    else:
        mdl = MusicgenForConditionalGeneration.from_pretrained(
            MODEL_ID,
            dtype=DTYPE,
            local_files_only=local_only,
        ).to(DEVICE)
    return proc, mdl

# Prova prima a caricare dalla cache locale (evita i retry di rete WinError 10054)
try:
    processor, model = _load_musicgen(local_only=True)
except Exception:
    print("Modello non trovato nella cache locale, scaricamento da HuggingFace Hub...")
    processor, model = _load_musicgen(local_only=False)

model.eval()
SAMPLE_RATE = model.config.audio_encoder.sampling_rate  # 32000
N_PARAMS_M = sum(p.numel() for p in model.parameters()) / 1e6
LOAD_TIME_S = time.perf_counter() - t0

# Lock globale per impedire l'accesso simultaneo al modello
modello_lock = threading.Lock()
MODEL_LOADED = True

print(f"MUSIC GEN READY ({LOAD_TIME_S:.1f}s | {N_PARAMS_M:.0f}M parametri | {SAMPLE_RATE} Hz)")


# ---------------------------------------------------------------------------
# Elaborazione e Generazione Audio
# ---------------------------------------------------------------------------
def apply_fade_and_normalize(audio_np: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Rimuove offset DC, applica un fade-in/out di 50ms e normalizza il picco a 0.9."""
    audio_np = audio_np.astype(np.float32).copy()

    # Rimuove eventuale offset DC
    audio_np -= np.mean(audio_np)

    fade_samples = int(0.05 * sr)
    if len(audio_np) > fade_samples * 2:
        audio_np[:fade_samples] *= np.linspace(0.0, 1.0, fade_samples, dtype=np.float32)
        audio_np[-fade_samples:] *= np.linspace(1.0, 0.0, fade_samples, dtype=np.float32)

    peak = np.max(np.abs(audio_np))
    if peak > 0:
        audio_np = (audio_np / peak) * 0.9
    return audio_np


def generate_audio(prompts: List[str], duration_s: float, tag: str = "-", abort_event: threading.Event = None) -> List[np.ndarray]:
    """Genera un array mono float32 per ogni prompt usando il modello MusicGen (diviso in batch)."""
    full_prompts = [p.rstrip(" .,") + PROMPT_SUFFIX for p in prompts]
    max_new_tokens = int(duration_s * TOKENS_PER_SECOND)
    log.info("[%s] MusicGen: genero %d campione/i da %.1fs (%d token, guidance=%.1f)...",
             tag, len(full_prompts), duration_s, max_new_tokens, GUIDANCE_SCALE)

    # TRUCCO MULTITRACCIA: Allineamento dei layer tramite Seed condiviso.
    try:
        seed = int(tag, 16) % (2 ** 32 - 1)
    except ValueError:
        seed = 42

    results = []
    # Protezione VRAM: adatta il batch size alla grandezza del modello
    if "large" in MODEL_ID.lower():
        batch_size = 1  # GPU sotto sforzo estremo, una nota alla volta
    elif "medium" in MODEL_ID.lower():
        batch_size = 2  # GPU sotto sforzo moderato
    else:
        batch_size = 4  # Modello small, possiamo osare
        
    log.info("[%s] Generazione in batch da %d note alla volta", tag, batch_size)
    
    t0 = time.perf_counter()
    
    for i in range(0, len(full_prompts), batch_size):
        if abort_event and abort_event.is_set():
            log.info("[%s] MusicGen: generazione batch interrotta anticipatamente", tag)
            break

        batch_prompts = full_prompts[i:i+batch_size]
        for p in batch_prompts:
            log.debug("[%s] MusicGen prompt: %s", tag, p)
            
        inputs = processor(text=batch_prompts, padding=True, return_tensors="pt").to(DEVICE)

        # Ripristina il seed prima di OGNI passaggio di batch per mantenere 
        # le strutture latenti coerenti per tutte le note
        torch.manual_seed(seed)
        if DEVICE == "cuda":
            torch.cuda.manual_seed_all(seed)

        with modello_lock:
            with torch.no_grad():
                audio_values = model.generate(
                    **inputs,
                    do_sample=True,
                    guidance_scale=GUIDANCE_SCALE,
                    max_new_tokens=max_new_tokens,
                )

        # shape: (batch, canali, campioni) -> lista di array mono float32
        audio_values = audio_values.cpu().float().numpy()
        for item in audio_values:
            if item.ndim > 1:
                item = np.mean(item, axis=0)
            results.append(item)

    elapsed = time.perf_counter() - t0
    generated_s = len(results) * (len(results[0]) / SAMPLE_RATE)
    log.info("[%s] MusicGen: completato in %.1fs | %.1fs di audio generato (%.2fx tempo reale)",
             tag, elapsed, generated_s, generated_s / elapsed if elapsed > 0 else 0)
    return results


def save_wav(audio_np: np.ndarray, job_id: str, name: str, base_url: str, tag: str = "-") -> str:
    """Applica il post-processing, salva il file .wav su disco e restituisce l'URL pubblico."""
    peak_raw = float(np.max(np.abs(audio_np))) if len(audio_np) else 0.0
    rms_raw = float(np.sqrt(np.mean(audio_np ** 2))) if len(audio_np) else 0.0
    if peak_raw < 1e-4:
        log.warning("[%s] %s: audio quasi silenzioso (peak=%.6f), controlla il prompt", tag, name, peak_raw)

    audio_np = apply_fade_and_normalize(audio_np, SAMPLE_RATE)
    file_name = f"{job_id}_{name}.wav"
    file_path = os.path.join(AUDIO_DIR, file_name)
    wavfile.write(file_path, rate=SAMPLE_RATE, data=(audio_np * 32767).astype(np.int16))

    url = f"{base_url.rstrip('/')}/audio/{file_name}"
    log.info("[%s] salvato %-13s | %.2fs | peak=%.3f rms=%.3f | %.0f KB -> %s",
             tag, name, len(audio_np) / SAMPLE_RATE, peak_raw, rms_raw,
             os.path.getsize(file_path) / 1024, url)
    return url


# ---------------------------------------------------------------------------
# Test rapido del modulo
# ---------------------------------------------------------------------------
def test():
    test_prompts = [
        "warm lush synthesizer pad, sustained C3 note",
        "warm lush synthesizer pad, sustained G3 note"
    ]
    print(f"\nTest generazione batch (durata 3s) con {len(test_prompts)} note...")
    audios = generate_audio(test_prompts, duration_s=3.0, tag="TEST")
    
    for i, audio in enumerate(audios):
        url = save_wav(audio, job_id="testjob", name=f"note_{i}", base_url="http://localhost:8000", tag="TEST")
        print(f"File {i} salvato! URL simulato: {url}")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    test()