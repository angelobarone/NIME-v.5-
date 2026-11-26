import json
import re
import time
import logging
import warnings

import api_token

# Silenzia il FutureWarning
warnings.filterwarnings("ignore", category=FutureWarning, module="google.generativeai")

log = logging.getLogger("nime")

GOOGLE_API_KEY = api_token.GeminiAPI
MODEL_ID = api_token.modello1

_SDK_MODE = None
_client = None
_legacy_model = None

try:
    from google import genai as new_genai
    from google.genai import types as genai_types
    _client = new_genai.Client(api_key=GOOGLE_API_KEY)
    _SDK_MODE = "new"
except ImportError:
    try:
        import google.generativeai as old_genai
        old_genai.configure(api_key=GOOGLE_API_KEY)
        is_gemma = "gemma" in MODEL_ID.lower()
        gen_cfg = {"temperature": 0.5, "max_output_tokens": 4096} # Leggermente più creativo
        if not is_gemma:
            gen_cfg["response_mime_type"] = "application/json"
        _legacy_model = old_genai.GenerativeModel(
            model_name=MODEL_ID,
            generation_config=old_genai.GenerationConfig(**gen_cfg),
        )
        _SDK_MODE = "legacy"
    except ImportError as exc:
        raise RuntimeError("Nessun SDK Google AI trovato nel virtualenv.") from exc

NOTES = ["C3", "D3", "E3", "G3", "A3", "C4", "D4", "E4", "G4", "A4"]

# Abbiamo aggiornato il prompt per fargli generare sia i parametri/effetti per il live synth,
# sia una stringa di testo specifica per MusicGen per il sottofondo.
SYSTEM_PROMPT = """You are an expert sound designer and prompt engineer.
Given a user's emotional mood, design BOTH a live synthesizer parameters AND a prompt for an AI background music generator.

Return ONLY a valid JSON with exactly this structure:
{
  "synth_params": {
    "oscillator_type": "sine", // or "square", "triangle", "sawtooth"
    "attack": 0.05, // 0.001 to 2.0
    "decay": 0.2, // 0.1 to 2.0
    "sustain": 0.3, // 0.0 to 1.0
    "release": 1.2, // 0.1 to 5.0
    "filter_cutoff": 2000 // 200 to 10000
  },
  "effects": {
    "distortion_mix": 0.0, // 0.0 (clean) to 1.0 (heavy)
    "reverb_size": 0.8, // 0.0 (dry) to 1.0 (huge)
    "delay_time": 0.0, // 0.0 (off) or float between 0.1 and 1.0 seconds
    "bitcrusher": 0 // 0 (off), or integer between 1 (crushed) and 8
  },
  "scale": "minor_pentatonic", // or "major_pentatonic", "dorian", etc.
  "backing_track_prompt": "A 10-15 words description of a seamless ambient background drone/texture. NO melody, NO drums, NO rhythm. Just sustained atmospheric texture."
}"""

print("LLM READY")

def _estrai_json(testo: str) -> dict:
    match = re.search(r"\{.*\}", testo, re.DOTALL)
    if match:
        return json.loads(match.group(0))
    raise json.JSONDecodeError("Nessun JSON trovato", testo, 0)

def fallback_layers(mood: str) -> dict:
    return {
        "synth_params": {"oscillator_type": "triangle", "attack": 0.01, "decay": 0.2, "sustain": 0.2, "release": 1.0, "filter_cutoff": 2000},
        "effects": {"distortion_mix": 0.0, "reverb_size": 0.3, "delay_time": 0.0, "bitcrusher": 0},
        "scale": "minor_pentatonic",
        "backing_track_prompt": f"{mood} ambient atmospheric pad drone, smooth, seamless loop, no rhythm"
    }

def translate_mood_to_multisample(user_mood: str, tag: str = "-") -> dict:
    prompt_completo = f"{SYSTEM_PROMPT}\n\nUser Mood: '{user_mood}'"
    try:
        log.info("[%s] LLM: Analisi mood '%s' per synth ed effetti...", tag, user_mood)
        if _SDK_MODE == "new":
            cfg_kwargs = {"temperature": 0.5}
            if not "gemma" in MODEL_ID.lower():
                cfg_kwargs["response_mime_type"] = "application/json"
            response = _client.models.generate_content(
                model=MODEL_ID, contents=prompt_completo, config=genai_types.GenerateContentConfig(**cfg_kwargs),
            )
            parts = response.candidates[0].content.parts
        else:
            response = _legacy_model.generate_content(prompt_completo)
            parts = response.candidates[0].content.parts

        raw_text = ""
        for part in reversed(parts):
            try:
                raw_text = part.text
                return _estrai_json(raw_text)
            except (json.JSONDecodeError, AttributeError):
                continue
        raise ValueError("Nessun JSON valido.")
    except Exception as e:
        log.warning("[%s] Errore LLM (%s), uso fallback", tag, e)
        return fallback_layers(user_mood)