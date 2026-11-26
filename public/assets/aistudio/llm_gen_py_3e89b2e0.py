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
        # Nota: i modelli Gemma su AI Studio non sempre supportano response_mime_type="application/json"
        is_gemma = "gemma" in MODEL_ID.lower()
        gen_cfg = {"temperature": 0.1, "max_output_tokens": 4096}
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
            "Esegui nel terminale (con .venv attivo):\n"
            "    pip install google-genai\n"
            "oppure:\n"
            "    pip install google-generativeai"
        ) from exc

# ESTENSIONE A 2 OTTAVE (10 note invece di 5)
NOTES = ["C3", "D3", "E3", "G3", "A3", "C4", "D4", "E4", "G4", "A4"]

# Mappatura dei semitoni rispetto alla nota fondamentale (C3) per il pitch shifting
PENTATONIC_SHIFTS = {
    "C3": 0,
    "D3": 2,
    "E3": 4,
    "G3": 7,
    "A3": 9,
    "C4": 12, # Un'ottava sopra
    "D4": 14,
    "E4": 16,
    "G4": 19,
    "A4": 21
}

# PROMPT MIGLIORATO: Istruisce l'LLM a usare nomi di strumenti reali, aggettivi ed effetti.
SYSTEM_PROMPT = """You are an expert prompt engineer for the MusicGen audio AI.
Your goal is to translate a user's emotional mood into a precise, highly detailed synthesizer or acoustic instrument prompt.
Return ONLY a valid JSON object with:
1. "category": A short snake_case category (e.g., "ethereal_ambient", "gritty_cyberpunk").
2. "scale": Best musical scale ("minor_pentatonic", "major_pentatonic", "dorian", "lydian", "phrygian").
3. "base_cutoff_hz": Integer between 400 and 6000.
4. "core_timbre": A highly descriptive prompt (15-30 words) for a SINGLE unified instrument. 
   MUST include specific instrument names (e.g., "analog moog bass", "glassy FM pad", "distorted electric cello"), texture adjectives (e.g., "warm, lush, gritty"), and acoustic effects (e.g., "heavy plate reverb, tape delay"). 
   CRITICAL: The prompt MUST strictly describe a single, isolated, sustained monophonic note. Explicitly forbid melodies. Example: "A lush, warm analog pad synth with heavy chorus and cavernous reverb, playing a single steady isolated sustained note, ambient texture."
Respond ONLY with raw JSON."""

print("LLM READY")


def _estrai_json(testo: str) -> dict:
    """Estrae il primo blocco JSON valido da un testo che può contenere ragionamento o markdown."""
    match = re.search(r"\{.*\}", testo, re.DOTALL)
    if match:
        return json.loads(match.group(0))
    raise json.JSONDecodeError("Nessun JSON trovato", testo, 0)


def fallback_layers(mood: str) -> dict:
    """Restituisce il timbro di emergenza nel caso in cui la chiamata API fallisca."""
    m = mood.strip()
    return {
        "category": "default_fallback",
        "scale": "minor_pentatonic",
        "base_cutoff_hz": 1800,
        "core_timbre": f"{m} warm thick chorus synthesizer, heavy reverb, single note",
    }


def translate_mood_to_multisample(user_mood: str, tag: str = "-") -> dict:
    """Interroga il modello online tramite Google Generative AI e costruisce i prompt per il multisample."""
    fb = fallback_layers(user_mood)
    prompt_completo = f"{SYSTEM_PROMPT}\n\nUser Mood: '{user_mood}'"

    try:
        log.info("[%s] LLM Online (%s, sdk=%s): genero i prompt per il mood '%s'...",
                 tag, MODEL_ID, _SDK_MODE, user_mood)
        t0 = time.perf_counter()

        risposta_json = None
        raw_text = ""

        if _SDK_MODE == "new":
            is_gemma = "gemma" in MODEL_ID.lower()
            cfg_kwargs = {"temperature": 0.1, "max_output_tokens": 4096}
            if not is_gemma:
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

        # Prova ogni part dall'ultima alla prima finché non trova JSON valido
        for part in reversed(parts):
            try:
                raw_text = part.text
                risposta_json = _estrai_json(raw_text)
                break
            except (json.JSONDecodeError, AttributeError):
                continue

        if risposta_json is None:
            raise ValueError("Nessuna part conteneva JSON valido")

        log.info("[%s] LLM Online: risposta ricevuta in %.1fs (%d caratteri)",
                 tag, time.perf_counter() - t0, len(raw_text))
        log.debug("[%s] LLM Online output grezzo:\n%s", tag, raw_text)
        log.info("[%s] LLM Online: JSON valido, chiavi trovate: %s", tag, list(risposta_json.keys()))

        category = str(risposta_json.get("category", "unknown_category")).lower().replace(" ", "_")

        core_timbre = risposta_json.get("core_timbre")
        if not core_timbre or not isinstance(core_timbre, str) or not core_timbre.strip():
            log.warning("[%s] LLM Online: 'core_timbre' mancante o non valido -> uso il fallback", tag)
            core_timbre = fb["core_timbre"]
            
        core_timbre = core_timbre.strip()

        scale = risposta_json.get("scale")
        if scale not in {"minor_pentatonic", "major_pentatonic", "dorian", "lydian", "phrygian"}:
            log.warning("[%s] LLM Online: scala '%s' non valida -> uso '%s'", tag, scale, fb["scale"])
            scale = fb["scale"]

        try:
            cutoff = int(risposta_json.get("base_cutoff_hz", fb["base_cutoff_hz"]))
        except (TypeError, ValueError):
            log.warning("[%s] LLM Online: base_cutoff_hz non valido -> uso %d", tag, fb["base_cutoff_hz"])
            cutoff = fb["base_cutoff_hz"]
            
        clamped = max(400, min(cutoff, 6000))
        if clamped != cutoff:
            log.warning("[%s] LLM Online: cutoff %d fuori range -> %d", tag, cutoff, clamped)
        cutoff = clamped

        # Genera un layer separato per ogni nota (serve per mantenere compatibilità con main.py)
        clean_layers = {}
        for note in NOTES:
            clean_layers[note] = f"{core_timbre}, sustained {note} pitch"

        log.info("[%s] LLM Online: category=%s | scala=%s | cutoff=%d Hz", tag, category, scale, cutoff)
        log.info("[%s] LLM Online: core_timbre -> '%s'", tag, core_timbre)

        return {"category": category, "scale": scale, "base_cutoff_hz": cutoff, "layers": clean_layers}

    except Exception as e:
        log.warning("[%s] LLM Online: generazione/parsing fallito (%s: %s) -> uso i prompt di fallback",
                    tag, type(e).__name__, e)
        
        clean_layers = {}
        for note in NOTES:
            clean_layers[note] = f"{fb['core_timbre']}, sustained {note} pitch"
            
        return {
            "category": fb["category"], 
            "scale": fb["scale"], 
            "base_cutoff_hz": fb["base_cutoff_hz"], 
            "layers": clean_layers
        }


# TEST
def test():
    mood_test = "Dark cyberpunk rain, melancholic and futuristic"
    print(f"Inoltro richiesta a Google AI Studio per il mood: '{mood_test}'...")
    risultato = translate_mood_to_multisample(mood_test, tag="TEST")
    print("\n[OUTPUT PER MUSICGEN]:")
    print(json.dumps(risultato, indent=2))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    test()