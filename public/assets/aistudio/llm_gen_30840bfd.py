import json
import re
import time
import logging
import warnings

import api_token

# Silenzia il FutureWarning del pacchetto legacy google.generativeai
warnings.filterwarnings("ignore", category=FutureWarning, module="google.generativeai")

log = logging.getLogger("nime")

GOOGLE_API_KEY = api_token.GeminiAPI
MODEL_ID = api_token.modello1

# Supporto sia per il nuovo SDK ufficiale (google-genai, compatibile con Python 3.14)
# sia per il precedente (google-generativeai)
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
        gen_cfg = {"temperature": 0.4, "max_output_tokens": 4096}  # Alzata un po' la temp per maggiore creatività
        if not is_gemma:
            gen_cfg["response_mime_type"] = "application/json"

        _legacy_model = old_genai.GenerativeModel(
            model_name=MODEL_ID,
            generation_config=old_genai.GenerationConfig(**gen_cfg),
        )
        _SDK_MODE = "legacy"
    except ImportError as exc:
        raise RuntimeError(
            "Nessun SDK Google AI trovato nel virtualenv (.venv)!\n"
        ) from exc

NOTES = ["C3", "D3", "E3", "G3", "A3", "C4", "D4", "E4", "G4", "A4"]

PENTATONIC_SHIFTS = {
    "C3": 0, "D3": 2, "E3": 4, "G3": 7, "A3": 9,
    "C4": 12, "D4": 14, "E4": 16, "G4": 19, "A4": 21
}

SYSTEM_PROMPT = """You are an expert synthesizer sound designer.
Your goal is to translate a user's emotional mood into a precise set of synthesizer parameters for a WebAudio/Tone.js engine.
Return ONLY a valid JSON object with:
1. "oscillator_type": "sine", "square", "triangle", "sawtooth", "pwm", or "fmsine".
2. "attack": Float between 0.001 (instant) and 2.0 (slow swell).
3. "decay": Float between 0.1 and 2.0.
4. "sustain": Float between 0.0 and 1.0.
5. "release": Float between 0.1 and 5.0.
6. "filter_cutoff": Integer between 200 and 10000.
7. "filter_type": "lowpass", "highpass", or "bandpass".
8. "distortion_amount": Float between 0.0 (clean) and 1.0 (heavy distortion).
9. "reverb_wet": Float between 0.0 (dry) and 1.0 (cavernous).
10. "scale": The best musical scale for this mood ("minor_pentatonic", "major_pentatonic", "dorian", "lydian", "phrygian").
Respond ONLY with raw JSON."""

print("LLM READY")


def _estrai_json(testo: str) -> dict:
    match = re.search(r"\{.*\}", testo, re.DOTALL)
    if match:
        return json.loads(match.group(0))
    raise json.JSONDecodeError("Nessun JSON trovato", testo, 0)


def fallback_layers(mood: str) -> dict:
    return {
        "oscillator_type": "triangle",
        "attack": 0.01,
        "decay": 0.2,
        "sustain": 0.2,
        "release": 1.0,
        "filter_cutoff": 2000,
        "filter_type": "lowpass",
        "distortion_amount": 0.0,
        "reverb_wet": 0.2,
        "scale": "minor_pentatonic"
    }


def translate_mood_to_multisample(user_mood: str, tag: str = "-") -> dict:
    fb = fallback_layers(user_mood)
    prompt_completo = f"{SYSTEM_PROMPT}\n\nUser Mood: '{user_mood}'"

    try:
        log.info("[%s] LLM Online: genero i prompt per il mood '%s'...", tag, user_mood)

        if _SDK_MODE == "new":
            cfg_kwargs = {"temperature": 0.4, "max_output_tokens": 4096}
            if not "gemma" in MODEL_ID.lower():
                cfg_kwargs["response_mime_type"] = "application/json"
            response = _client.models.generate_content(
                model=MODEL_ID,
                contents=prompt_completo,
                config=genai_types.GenerateContentConfig(**cfg_kwargs),
            )
            parts = response.candidates[0].content.parts
        else:
            response = _legacy_model.generate_content(prompt_completo)
            parts = response.candidates[0].content.parts

        raw_text = ""
        risposta_json = None
        for part in reversed(parts):
            try:
                raw_text = part.text
                risposta_json = _estrai_json(raw_text)
                break
            except (json.JSONDecodeError, AttributeError):
                continue

        if risposta_json is None:
            raise ValueError("Nessuna part conteneva JSON valido")

        log.info("[%s] LLM Online: JSON output: %s", tag, raw_text)

        # Usiamo il JSON generato e riempiamo i vuoti col fallback
        params = fallback_layers(user_mood)
        for key in params.keys():
            if key in risposta_json:
                params[key] = risposta_json[key]

        return params

    except Exception as e:
        log.warning("[%s] Errore LLM (%s), uso fallback", tag, e)
        return fallback_layers(user_mood)