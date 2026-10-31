import copy
import difflib
import html as html_lib
import io
import json
import math
import os
import random
import re
from collections import Counter
from datetime import date, datetime, timedelta
from gtts import gTTS
import streamlit as st
import streamlit.components.v1 as components
from groq import Groq

# Page Configuration
st.set_page_config(page_title="Dynamic Leadership & Communication Coach", page_icon="🎭", layout="wide", initial_sidebar_state="expanded")

# Mobile/Chrome responsive tweaks: bigger tap targets, no horizontal overflow, scrollable tabs
st.markdown("""
<style>
/* Prevent long words/URLs from forcing horizontal scroll on any viewport */
.stMarkdown p, .stMarkdown li, .stCaption, code, pre {
    word-break: break-word;
    overflow-wrap: anywhere;
}
.stButton > button, .stFormSubmitButton > button {
    border-radius: 8px;
}
@media (max-width: 640px) {
    .block-container {
        padding-top: 1rem;
        padding-left: 0.75rem;
        padding-right: 0.75rem;
        padding-bottom: 2rem;
    }
    /* Bigger tap targets (min 44px per WCAG/Chrome touch guidance) */
    .stButton > button, .stFormSubmitButton > button {
        min-height: 44px;
        font-size: 0.95rem;
        width: 100%;
    }
    /* Scroll instead of squeeze when tabs don't fit the screen width */
    [data-testid="stTabs"] [data-baseweb="tab-list"] {
        overflow-x: auto;
        flex-wrap: nowrap;
    }
    [data-testid="stTabs"] button[role="tab"] {
        white-space: nowrap;
        font-size: 0.82rem;
        padding: 0.4rem 0.6rem;
    }
    [data-testid="stChatMessageContent"] p {
        font-size: 0.95rem;
    }
    [data-testid="stAudioInput"] {
        width: 100% !important;
    }
    [data-testid="stExpander"] summary {
        font-size: 0.9rem;
    }
}
</style>
""", unsafe_allow_html=True)

# --- TOAST QUEUE ---
# st.rerun() discards toasts/balloons raised earlier in the same run, so anything immediately
# followed by a rerun is queued here and shown at the start of the next run instead.
def queue_toast(message: str, icon: str = None):
    st.session_state.setdefault("_pending_toasts", []).append((message, icon))

def queue_balloons():
    st.session_state["_pending_balloons"] = True

def flush_toasts():
    for message, icon in st.session_state.pop("_pending_toasts", []):
        st.toast(message, icon=icon)
    if st.session_state.pop("_pending_balloons", False):
        st.balloons()

flush_toasts()

# Local Storage Persistence Setup
# Resolved next to this script (not the current working directory) so the same practice file is used
# however the app is launched. ENGLISH_APP_DATA_FILE overrides it (e.g. to point at a scratch copy).
DATA_FILE = os.environ.get("ENGLISH_APP_DATA_FILE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "practice_data.json")

# CEFR level -> how the conversation partner should pitch its language.
LEVEL_GUIDES = {
    "A2 (Elementary)": "Use short, simple sentences and very common words. Avoid slang and idioms; if you use one, explain it in brackets.",
    "B1 (Intermediate)": "Use clear everyday English with common phrasal verbs. Use at most one common Australian expression per reply and briefly explain it in brackets.",
    "B2 (Upper-intermediate)": "Speak naturally with common idioms and Australian expressions, but avoid rare or heavy slang.",
    "C1 (Advanced)": "Speak like a native Australian, including natural slang, idioms and humour.",
}
DEFAULT_LEVEL = "B1 (Intermediate)"

DEFAULT_PROFILE = {
    "level": DEFAULT_LEVEL, "total_xp": 0, "practice_days": [],
    "retry_enabled": True, "auto_fixit": True, "legacy_migrated": False,
}

DEFAULT_DATA = {
    "history": [], "vocabulary": [], "writing_history": [], "grammar_scores": [], "tutor_turn_history": [],
    "error_log": [], "task_history": [], "listening_scores": [], "profile": DEFAULT_PROFILE,
}

def load_data():
    loaded = {}
    if os.path.exists(DATA_FILE):
        try:
            with open(DATA_FILE, "r", encoding="utf-8") as f:
                loaded = json.load(f)
        except Exception:
            # Keep the unreadable file aside rather than silently overwriting it with an empty one on the next save.
            try:
                os.replace(DATA_FILE, f"{DATA_FILE}.corrupt-{datetime.now().strftime('%Y%m%d%H%M%S')}")
            except OSError:
                pass
            loaded = {}
    for key, default_val in DEFAULT_DATA.items():
        loaded.setdefault(key, copy.deepcopy(default_val))
    for key, default_val in DEFAULT_PROFILE.items():
        loaded["profile"].setdefault(key, copy.deepcopy(default_val))
    return loaded

def save_data(data):
    # Write to a temp file then swap it in, so a crash mid-write can't leave a truncated practice file.
    tmp_path = f"{DATA_FILE}.tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=4, ensure_ascii=False)
    os.replace(tmp_path, DATA_FILE)

def migrate_legacy_data(data):
    """One-off backfill for files saved before the mistakes notebook / daily streak existed: past incorrect
    tutor turns become 'unclassified' notebook entries, and past practice dates seed the streak."""
    profile = data["profile"]
    if profile.get("legacy_migrated"):
        return
    for turn in data["tutor_turn_history"]:
        if turn.get("grammar_accurate") is False and not turn.get("errors"):
            data["error_log"].append({
                "timestamp": turn.get("timestamp", ""), "source": "tutor", "topic": turn.get("topic", ""),
                "turn_text": turn.get("transcript", ""), "original": turn.get("transcript", ""),
                "corrected": turn.get("corrected_phrase") or "", "error_type": "unclassified",
                "explanation": turn.get("grammar_feedback", ""),
            })
    data["error_log"].sort(key=lambda e: e.get("timestamp", ""), reverse=True)
    past_days = {(t.get("timestamp") or "")[:10] for t in data["tutor_turn_history"]}
    past_days |= {(h.get("id") or "")[:10] for h in data["history"]}
    profile["practice_days"] = sorted(d for d in past_days | set(profile["practice_days"]) if d)
    profile["legacy_migrated"] = True
    save_data(data)

data = load_data()
migrate_legacy_data(data)

def learner_level() -> str:
    level = data["profile"].get("level", DEFAULT_LEVEL)
    return level if level in LEVEL_GUIDES else DEFAULT_LEVEL

# --- LLM HELPERS ---
WHISPER_MODEL = "whisper-large-v3-turbo"

def parse_json_loose(raw: str):
    """Parses a JSON object from an LLM reply, tolerating markdown fences or stray text around it."""
    text = (raw or "").strip().replace("```json", "").replace("```", "").strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start, end = text.find("{"), text.rfind("}")
        if start != -1 and end > start:
            return json.loads(text[start:end + 1])
        raise

def chat_json(prompt: str, temperature: float = 0.2) -> dict:
    """Runs a feedback-model completion that must return a JSON object."""
    messages = [{"role": "user", "content": prompt}]
    try:
        res = client.chat.completions.create(
            model=FEEDBACK_MODEL, messages=messages, temperature=temperature, response_format={"type": "json_object"}
        )
    except Exception:
        # Not every Groq model supports JSON mode; retry as a plain completion and parse leniently.
        res = client.chat.completions.create(model=FEEDBACK_MODEL, messages=messages, temperature=temperature)
    parsed = parse_json_loose(res.choices[0].message.content or "")
    if not isinstance(parsed, dict):
        raise ValueError("Expected a JSON object")
    return parsed

def chat_text(prompt: str, temperature: float = 0.3, model: str = None) -> str:
    res = client.chat.completions.create(
        model=model or FEEDBACK_MODEL, messages=[{"role": "user", "content": prompt}], temperature=temperature
    )
    return res.choices[0].message.content or ""

@st.cache_data(show_spinner=False)
def explain_message(text: str, level: str, model: str) -> str:
    """Plain-English explanation of the tricky words/idioms in a message, pitched at the learner's level."""
    prompt = f"""
A {level} English learner is reading this message from an Australian English speaker:
\"\"\"{text}\"\"\"
In simple English suitable for that level, explain up to 5 words, idioms, slang or phrasal verbs in the message that might be hard to understand.
Use a markdown bullet list: **phrase** — simple meaning.
If nothing is tricky, say so in one short sentence and restate the message in simpler words.
"""
    return chat_text(prompt, 0.2, model=model)

# --- SPEECH-TO-TEXT & FLUENCY HELPERS ---
FILLER_WORDS = {"um", "umm", "uh", "uhh", "uhm", "er", "erm", "ah", "hmm", "mm"}
# Whisper normally drops hesitations; priming it with a disfluent prompt makes it keep them so fillers can be counted.
WHISPER_FILLER_PROMPT = "Umm, let me think like, hmm... Okay, here's what I'm, like, thinking."
LONG_PAUSE_SECONDS = 0.7

def _as_dict(obj) -> dict:
    if isinstance(obj, str):
        return {"text": obj}
    if isinstance(obj, dict):
        return obj
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    return {"text": getattr(obj, "text", "")}

def transcribe_audio(audio_bytes: bytes, filename: str = "turn.wav", keep_fillers: bool = False) -> dict:
    """Transcribes English speech with word/segment timestamps. Returns {"text", "words", "segments"}."""
    kwargs = {
        "file": (filename, audio_bytes), "model": WHISPER_MODEL, "language": "en",
        "response_format": "verbose_json", "timestamp_granularities": ["word", "segment"],
    }
    if keep_fillers:
        kwargs["prompt"] = WHISPER_FILLER_PROMPT
    try:
        payload = _as_dict(client.audio.transcriptions.create(**kwargs))
    except Exception:
        # Fall back to plain text if timestamps aren't available; fluency/clarity stats are then skipped.
        payload = _as_dict(client.audio.transcriptions.create(
            file=(filename, audio_bytes), model=WHISPER_MODEL, language="en", response_format="text"
        ))
    return {
        "text": (payload.get("text") or "").strip(),
        "words": [_as_dict(w) for w in payload.get("words") or []],
        "segments": [_as_dict(s) for s in payload.get("segments") or []],
    }

def fluency_stats(transcription: dict):
    """Speaking-speed, pause and filler stats from Whisper word timestamps (None if too short to measure)."""
    words = [w for w in transcription.get("words", []) if w.get("start") is not None and w.get("end") is not None]
    if len(words) < 2:
        return None
    duration = words[-1]["end"] - words[0]["start"]
    if duration <= 0.5:
        return None
    gaps = [b["start"] - a["end"] for a, b in zip(words, words[1:])]
    fillers = sum(1 for w in words if (normalize_words(w.get("word", "")) or [""])[0] in FILLER_WORDS)
    return {
        "wpm": round(len(words) / duration * 60),
        "long_pauses": sum(1 for g in gaps if g >= LONG_PAUSE_SECONDS),
        "longest_pause": round(max(gaps, default=0), 1),
        "fillers": fillers,
        "seconds": round(duration, 1),
    }

def format_fluency(fluency: dict) -> str:
    return f"🗣️ {fluency['wpm']} words/min · ⏸️ {fluency['long_pauses']} long pause(s) · 🫧 {fluency['fillers']} filler(s)"

def clarity_score(transcription: dict):
    """Rough 0-100 recognition-confidence score from Whisper's per-segment average log-probability."""
    logprobs = [s["avg_logprob"] for s in transcription.get("segments", []) if isinstance(s.get("avg_logprob"), (int, float))]
    if not logprobs:
        return None
    return round(100 * math.exp(sum(logprobs) / len(logprobs)))

# --- WORD COMPARISON HELPERS ---
_WORD_RE = re.compile(r"[a-z0-9']+")

def normalize_words(text: str) -> list:
    return _WORD_RE.findall((text or "").lower().replace("’", "'"))

def similarity(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, normalize_words(a), normalize_words(b), autojunk=False).ratio()

def word_diff(target: str, attempt: str) -> dict:
    """Word-level comparison of an attempt against a target sentence.
    Returns {"score": 0-1 match ratio, "markdown": highlighted diff, "missed": target words not matched}."""
    t_words, a_words = normalize_words(target), normalize_words(attempt)
    matcher = difflib.SequenceMatcher(None, t_words, a_words, autojunk=False)
    parts, missed = [], []
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            parts.extend(t_words[i1:i2])
            continue
        if j2 > j1:
            parts.append(f":orange[~~{' '.join(a_words[j1:j2])}~~]")
        if i2 > i1:
            parts.append(f":red[**{' '.join(t_words[i1:i2])}**]")
            missed.extend(t_words[i1:i2])
    return {"score": matcher.ratio(), "markdown": " ".join(parts), "missed": missed}

DIFF_LEGEND = "Legend: :red[**red**] = words you missed or got wrong · :orange[~~orange~~] = words you said/wrote instead"

# --- TTS HELPER FUNCTION ---
@st.cache_data(show_spinner=False)
def get_pronunciation_audio(text: str, accent: str = "com", slow: bool = False) -> bytes:
    """Generates MP3 audio bytes for vocabulary pronunciation."""
    fp = io.BytesIO()
    tts = gTTS(text=text, lang="en", tld=accent, slow=slow)
    tts.write_to_fp(fp)
    return fp.getvalue()

def render_message_tools(text: str, accent: str, key: str, explain: bool = True):
    """Compact toggles under a message: listen at normal or slow speed (listening practice) and, optionally,
    a plain-English explanation of any slang/idioms in it, pitched at the learner's level."""
    options = ["🔊 Listen", "🐢 Slow"] + (["❓ Explain"] if explain else [])
    picked = st.pills("Message tools", options, selection_mode="multi", key=f"tools_{key}", label_visibility="collapsed") or []
    for choice in (p for p in picked if p in ("🔊 Listen", "🐢 Slow")):
        try:
            st.audio(get_pronunciation_audio(text, accent=accent, slow=choice == "🐢 Slow"), format="audio/mp3")
        except Exception:
            st.caption("⚠️ Audio unavailable")
    if "❓ Explain" in picked:
        try:
            with st.spinner("Explaining..."):
                st.info(explain_message(text, learner_level(), FEEDBACK_MODEL))
        except Exception as e:
            st.caption(f"⚠️ Couldn't explain this message ({e})")

# --- SPACED REPETITION (SM-2 LITE) HELPERS ---
def ensure_srs_fields(item: dict) -> bool:
    """Backfills SRS bookkeeping fields on a vocabulary item. Returns True if it was modified."""
    changed = False
    for field, default_val in (("interval", 0), ("ease", 2.5), ("repetitions", 0), ("due", ""), ("card_type", "vocab")):
        if field not in item:
            item[field] = default_val
            changed = True
    if not item["due"]:
        item["due"] = date.today().isoformat()
        changed = True
    return changed

def sm2_update(item: dict, quality: int) -> None:
    """Applies a simplified SM-2 spaced-repetition update. quality: 0=Again,1=Hard,2=Good,3=Easy."""
    if quality == 0:
        item["repetitions"] = 0
        item["interval"] = 1
        item["ease"] = max(1.3, item["ease"] - 0.2)
    else:
        item["repetitions"] += 1
        if item["repetitions"] == 1:
            item["interval"] = 1
        elif item["repetitions"] == 2:
            item["interval"] = 3
        else:
            item["interval"] = round(item["interval"] * item["ease"])
        item["ease"] = max(1.3, item["ease"] + {1: -0.1, 2: 0.0, 3: 0.15}[quality])
    item["due"] = (date.today() + timedelta(days=item["interval"])).isoformat()

# --- VOCABULARY BANK HELPERS ---
def vocab_key(word: str) -> str:
    return (word or "").strip().lower()

def find_vocab(data: dict, key: str):
    return next((v for v in data["vocabulary"] if vocab_key(v["word"]) == key), None)

def enrich_vocab_fields(word: str, context: str = "") -> dict:
    """Asks the feedback model for a learner-friendly meaning, example and usage tip. Returns {} on failure."""
    context_line = f'\nIt was seen in this message: "{context[:400]}"' if context else ""
    prompt = f"""
Give a {learner_level()} English learner a dictionary-style entry for the word/phrase "{word}" as used in natural Australian English.{context_line}
Return ONLY a JSON object with keys "meaning" (short, simple definition), "example" (one natural example sentence using it), "usage_tip" (one short tip on when/how to use it).
"""
    try:
        parsed = chat_json(prompt, 0.2)
    except Exception:
        return {}
    return {k: str(parsed.get(k) or "").strip() for k in ("meaning", "example", "usage_tip")}

def _combine_meaning(meaning: str, usage_tip: str) -> str:
    meaning, usage_tip = (meaning or "").strip(), (usage_tip or "").strip()
    if usage_tip:
        return f"{meaning} (Use when: {usage_tip})" if meaning else f"Use when: {usage_tip}"
    return meaning

def add_word_to_vocabulary(data: dict, word: str, meaning: str = "", example: str = "", usage_tip: str = "",
                           context: str = "", enrich: bool = True, card_type: str = "vocab", original: str = "") -> bool:
    """Adds a word/phrase to the shared vocabulary bank (auto-filling a missing meaning). Returns True if newly added."""
    word = (word or "").strip()
    if not word or find_vocab(data, vocab_key(word)):
        return False
    meaning, example = (meaning or "").strip(), (example or "").strip()
    if enrich and not meaning:
        fields = enrich_vocab_fields(word, context)
        meaning = fields.get("meaning", "")
        example = example or fields.get("example", "")
        usage_tip = usage_tip or fields.get("usage_tip", "")
    entry = {
        "word": word, "meaning": _combine_meaning(meaning, usage_tip), "example": example,
        "date": date.today().isoformat(), "card_type": card_type
    }
    if card_type == "fixit":
        entry["original"] = (original or "").strip()
    ensure_srs_fields(entry)
    data["vocabulary"].insert(0, entry)
    save_data(data)
    return True

def add_fixit_card(data: dict, original: str, corrected: str, explanation: str = "") -> bool:
    """Saves a 'fix the sentence' flashcard: front = what the learner said, back = the corrected version."""
    return add_word_to_vocabulary(
        data, corrected, meaning=explanation or "Corrected version of your sentence",
        enrich=False, card_type="fixit", original=original
    )

def _vocab_pattern(word: str):
    """Regex matching a saved word/phrase in free text, tolerating simple inflections (pushed back, ironing out)."""
    tokens = normalize_words(word)
    if not tokens or (len(tokens) == 1 and len(tokens[0]) < 3):
        return None
    parts = []
    for tok in tokens:
        stem = tok[:-1] if tok.endswith("e") and len(tok) > 3 else tok
        parts.append(re.escape(stem) + r"(?:e|s|es|ed|d|ing)?")
    return re.compile(r"\b" + r"[\s\-]+".join(parts) + r"\b", re.IGNORECASE)

def register_vocab_usage(data: dict, text: str) -> list:
    """Marks saved vocabulary the learner actually used in their own turn ("active vocabulary"). Caller saves."""
    used, cleaned = [], (text or "").replace("’", "'")
    for item in data["vocabulary"]:
        if item.get("card_type") == "fixit":
            continue
        pattern = _vocab_pattern(item["word"])
        if pattern and pattern.search(cleaned):
            item["used_count"] = item.get("used_count", 0) + 1
            item["last_used"] = date.today().isoformat()
            used.append(item["word"])
    return used

def pick_target_words(data: dict, n: int = 3) -> list:
    """Saved words the learner hasn't used yet (soonest-due first), to weave into the next conversation."""
    candidates = [v for v in data["vocabulary"] if v.get("card_type", "vocab") != "fixit" and _vocab_pattern(v["word"])]
    candidates.sort(key=lambda v: (v.get("used_count", 0), v.get("due", "")))
    return [v["word"] for v in candidates[:n]]

# --- SELECT-TO-ADD-VOCABULARY COMPONENT ---
# Build-free custom component: double-click/select any word or phrase in a rendered
# message to reveal a floating "Add to Vocabulary" button (see components/vocab_selector/index.html).
_VOCAB_SELECTOR_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "components", "vocab_selector")
_vocab_selector_component = components.declare_component("vocab_selector", path=_VOCAB_SELECTOR_DIR)

_MD_BOLD_RE = re.compile(r"\*\*(.+?)\*\*")
_MD_ITALIC_RE = re.compile(r"(?<!\*)\*([^*\n]+?)\*(?!\*)")
_MD_CODE_RE = re.compile(r"`([^`]+?)`")

def _light_markdown_to_html(text: str) -> str:
    """Escapes text for safe HTML embedding, then re-applies a small subset of markdown (bold/italic/code)."""
    escaped = html_lib.escape(text or "")
    escaped = _MD_BOLD_RE.sub(r"<b>\1</b>", escaped)
    escaped = _MD_ITALIC_RE.sub(r"<i>\1</i>", escaped)
    escaped = _MD_CODE_RE.sub(r"<code>\1</code>", escaped)
    return escaped.replace("\n", "<br>")

def render_selectable_text(text: str, key: str, data: dict):
    """Renders text via the vocab_selector component so the user can double-click/select a word or
    phrase and click the floating 'Add to Vocabulary' button to save it into the vocabulary bank."""
    result = _vocab_selector_component(html=_light_markdown_to_html(text), key=key, default=None)
    nonce_state_key = f"_{key}_last_nonce"
    if isinstance(result, dict):
        nonce = result.get("nonce")
        if nonce is not None and st.session_state.get(nonce_state_key) != nonce:
            st.session_state[nonce_state_key] = nonce
            selected = (result.get("text") or "").strip()
            if selected:
                with st.spinner(f"Saving '{selected}'..."):
                    added = add_word_to_vocabulary(data, selected, context=text)
                if added:
                    st.toast(f"Saved '{selected}' to Vocabulary!", icon="✅")
                else:
                    st.toast(f"'{selected}' is already in your list.", icon="ℹ️")

# --- LIVE REACTION & STREAMING HELPERS ---
REACTION_TAG_RE = re.compile(r"^\s*\[([^\]]{1,4})\]\s*(.*)", re.DOTALL)

def call_reaction_reply(messages: list, model: str, temperature: float, default_avatar: str) -> tuple:
    """Non-streaming reply generation that extracts a leading [emoji] reaction tag."""
    res = client.chat.completions.create(model=model, messages=messages, temperature=temperature)
    raw = res.choices[0].message.content or ""
    match = REACTION_TAG_RE.match(raw)
    if match:
        return match.group(2).strip(), match.group(1).strip()
    return raw.strip(), default_avatar

def stream_reply_with_reaction(messages: list, model: str, temperature: float, default_avatar: str) -> tuple:
    """Streams a chat reply token-by-token into a live st.chat_message bubble, extracting a leading [emoji] reaction tag."""
    stream = client.chat.completions.create(model=model, messages=messages, temperature=temperature, stream=True)
    raw, emoji, placeholder = "", None, None
    for chunk in stream:
        delta = chunk.choices[0].delta.content or ""
        if not delta:
            continue
        raw += delta
        if placeholder is None:
            match = REACTION_TAG_RE.match(raw)
            if match:
                emoji = match.group(1).strip()
                placeholder = st.chat_message("assistant", avatar=emoji).empty()
                placeholder.markdown(match.group(2) + "▌")
            continue
        visible = REACTION_TAG_RE.sub(r"\2", raw, count=1)
        placeholder.markdown(visible + "▌")
    if placeholder is None:
        emoji = default_avatar
        visible = raw.strip()
        placeholder = st.chat_message("assistant", avatar=emoji).empty()
    else:
        visible = REACTION_TAG_RE.sub(r"\2", raw, count=1)
    placeholder.markdown(visible)
    return visible.strip(), emoji

# --- GAMIFICATION (XP / STREAKS) HELPERS ---
def record_practice(data: dict, xp: int) -> None:
    """Adds persistent XP and marks today as a practice day (drives the daily streak), then saves."""
    profile = data["profile"]
    profile["total_xp"] = profile.get("total_xp", 0) + xp
    today = date.today().isoformat()
    if today not in profile["practice_days"]:
        profile["practice_days"].append(today)
    save_data(data)

def daily_streak(practice_days: list) -> int:
    """Consecutive practice days ending today (or yesterday, so the streak survives until today's session)."""
    days, day = set(practice_days), date.today()
    if day.isoformat() not in days:
        day -= timedelta(days=1)
    streak = 0
    while day.isoformat() in days:
        streak += 1
        day -= timedelta(days=1)
    return streak

def apply_gamification(prefix: str, accurate, data: dict, bonus_xp: int = 0) -> dict:
    """Awards session XP/streaks for a turn (accurate: True/False, or None if it couldn't be checked) and
    adds the XP to the persistent profile. Toasts are queued because callers rerun straight afterwards."""
    for suffix, default_val in (("xp", 0), ("streak", 0), ("best_streak", 0), ("turns", 0)):
        st.session_state.setdefault(f"{prefix}_{suffix}", default_val)

    st.session_state[f"{prefix}_turns"] += 1
    if accurate is True:
        st.session_state[f"{prefix}_streak"] += 1
        gained = 15
        queue_toast(f"✅ Grammar accurate! +{gained} XP", "✨")
    elif accurate is False:
        st.session_state[f"{prefix}_streak"] = 0
        gained = 5
        queue_toast(f"📝 Feedback ready · +{gained} XP", "💬")
    else:
        gained = 5
        queue_toast(f"💬 Turn recorded · +{gained} XP", "💬")
    gained += bonus_xp
    st.session_state[f"{prefix}_xp"] += gained
    st.session_state[f"{prefix}_best_streak"] = max(st.session_state[f"{prefix}_best_streak"], st.session_state[f"{prefix}_streak"])

    if accurate is True and st.session_state[f"{prefix}_streak"] in (3, 5, 10, 15, 20):
        queue_balloons()

    record_practice(data, gained)
    return {
        "xp": st.session_state[f"{prefix}_xp"],
        "streak": st.session_state[f"{prefix}_streak"],
        "best_streak": st.session_state[f"{prefix}_best_streak"],
        "turns": st.session_state[f"{prefix}_turns"],
    }

# --- MISTAKES NOTEBOOK HELPERS ---
ERROR_TYPES = [
    "verb form / tense", "subject-verb agreement", "articles", "prepositions", "word order", "word choice",
    "plurals / countable nouns", "pronouns", "question form", "missing word", "sentence structure / run-on",
    "spelling / typo", "other",
]
TYPO_TYPE = "spelling / typo"

def normalize_errors(raw_errors) -> list:
    """Cleans the model's error list: drops non-corrections (only punctuation/case changed) and maps types onto ERROR_TYPES."""
    cleaned = []
    for err in raw_errors or []:
        if not isinstance(err, dict):
            continue
        original = str(err.get("original") or "").strip()
        corrected = str(err.get("corrected") or "").strip()
        if not original or not corrected or normalize_words(original) == normalize_words(corrected):
            continue
        etype = str(err.get("error_type") or "other").strip().lower()
        if etype not in ERROR_TYPES:
            etype = next((t for t in ERROR_TYPES if etype and (etype in t or t.split(" ")[0] in etype)), "other")
        cleaned.append({
            "original": original, "corrected": corrected, "error_type": etype,
            "explanation": str(err.get("explanation") or "").strip(),
        })
    return cleaned

def format_grammar_feedback(errors: list) -> str:
    if not errors:
        return "Grammar was accurate."
    return "\n".join(
        f"- `{e['error_type']}` *{e['original']}* → **{e['corrected']}**" + (f" — {e['explanation']}" if e["explanation"] else "")
        for e in errors
    )

def log_errors(data: dict, errors: list, source: str, topic: str, turn_text: str) -> None:
    """Records each mistake in the notebook and (if enabled) saves non-typo ones as Fix-it cards. Caller saves."""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    for err in reversed(errors):
        data["error_log"].insert(0, {"timestamp": timestamp, "source": source, "topic": topic, "turn_text": turn_text, **err})
    if data["profile"].get("auto_fixit", True):
        for err in errors:
            if err["error_type"] != TYPO_TYPE:
                add_fixit_card(data, err["original"], err["corrected"], err["explanation"])

def top_error_types(data: dict, days: int = None) -> list:
    """[(error_type, count)] most common first, optionally limited to the last `days` days."""
    cutoff = (date.today() - timedelta(days=days - 1)).isoformat() if days else ""
    counts = Counter(
        e["error_type"] for e in data["error_log"]
        if e.get("error_type") != "unclassified" and (e.get("timestamp") or "")[:10] >= cutoff
    )
    return counts.most_common()

def _as_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None

def classify_unclassified_errors(data: dict) -> tuple:
    """Classifies legacy 'unclassified' notebook entries. Returns (updated_count, removed_count)."""
    pending = {i: e for i, e in enumerate(data["error_log"]) if e.get("error_type") == "unclassified"}
    batch = dict(list(pending.items())[:15])
    payload = [{"index": i, "learner_said": e.get("turn_text") or e.get("original"), "earlier_correction": e.get("corrected")} for i, e in batch.items()]
    prompt = f"""
These are sentences an English learner said, each with a rough earlier correction. For each one, identify its single most important genuine error.
{json.dumps(payload, ensure_ascii=False, indent=1)}
Return ONLY a JSON object {{"items": [{{"index": <same index>, "has_error": true or false, "original": "the shortest phrase containing the error, copied exactly", "corrected": "that phrase corrected", "error_type": one of {json.dumps(ERROR_TYPES)}, "explanation": "one short reason"}}]}}
Set "has_error" to false if the sentence is actually fine or too garbled to interpret.
"""
    parsed = chat_json(prompt, 0.1)
    updated, remove = 0, set()
    for item in parsed.get("items") or []:
        if not isinstance(item, dict):
            continue
        idx = _as_int(item.get("index"))
        if idx not in batch:
            continue
        fixed = normalize_errors([item]) if item.get("has_error") else []
        if fixed:
            data["error_log"][idx].update(fixed[0])
            updated += 1
        else:
            remove.add(idx)
    data["error_log"] = [e for i, e in enumerate(data["error_log"]) if i not in remove]
    save_data(data)
    return updated, len(remove)

def judge_answers(items: list) -> list:
    """Grades 'fix the sentence' answers. items: [(sentence_with_error, reference_answer, learner_answer)].
    Near-exact matches pass locally; the rest are judged in one batch so alternative correct fixes count."""
    results, pending = [None] * len(items), []
    for i, (_, expected, attempt) in enumerate(items):
        if not (attempt or "").strip():
            results[i] = (False, "No answer given.")
        elif similarity(expected, attempt) >= 0.9:
            results[i] = (True, "")
        else:
            pending.append(i)
    if pending:
        payload = [{"index": i, "sentence_with_error": items[i][0], "reference_answer": items[i][1], "learner_answer": items[i][2]} for i in pending]
        prompt = f"""
An English learner was asked to correct each sentence below.
{json.dumps(payload, ensure_ascii=False, indent=1)}
For each item, decide whether the learner's answer fixes the error, is grammatical and natural, and keeps the meaning.
Small punctuation or capitalisation differences don't matter, and a different but correct fix counts as correct.
Return ONLY a JSON object {{"results": [{{"index": <same index>, "correct": true or false, "feedback": "one short, encouraging sentence"}}]}}
"""
        try:
            parsed = chat_json(prompt, 0.1)
            by_index = {_as_int(r.get("index")): r for r in parsed.get("results") or [] if isinstance(r, dict)}
        except Exception:
            by_index = {}
        for i in pending:
            verdict = by_index.get(i)
            if verdict is None:
                results[i] = (similarity(items[i][1], items[i][2]) >= 0.75, "Couldn't check automatically — compare with the answer.")
            else:
                results[i] = (bool(verdict.get("correct")), str(verdict.get("feedback") or ""))
    return results

def generate_drill(error_type: str, examples: list) -> list:
    """Five 'fix the sentence' questions built from the learner's own mistakes of one type."""
    prompt = f"""
Create a 5-question "fix the sentence" drill for a {learner_level()} English learner on the mistake type "{error_type}".
Real mistakes this learner made (original -> corrected):
{json.dumps(examples, ensure_ascii=False, indent=1)}
Questions 1-2 should reuse the learner's own mistakes (lightly tidied into full sentences); questions 3-5 are new everyday sentences with the same kind of mistake.
Each sentence must contain exactly one error of this type.
Return ONLY a JSON object {{"items": [{{"wrong": "sentence containing the error", "answer": "corrected sentence", "explain": "one short reason"}}]}}
"""
    parsed = chat_json(prompt, 0.5)
    items = [i for i in parsed.get("items") or [] if isinstance(i, dict) and i.get("wrong") and i.get("answer")]
    return [{"wrong": str(i["wrong"]), "answer": str(i["answer"]), "explain": str(i.get("explain") or "")} for i in items[:5]]

# --- TURN AUDIT ---
def audit_learner_turn(user_text: str, topic: str, context_msgs: list, is_voice: bool) -> dict:
    """Checks one learner turn (with recent conversation context) and returns structured, consistent feedback."""
    level = learner_level()
    context = "\n".join(
        f"{'Partner' if m['role'] == 'assistant' else 'Learner'}: {m['content']}" for m in context_msgs
    ) or "(start of conversation)"
    input_note = (
        "This turn came from speech-to-text: ignore punctuation, capitalisation and filler words (um, uh, er)."
        if is_voice else
        "This turn was typed: ignore capitalisation and missing final punctuation, but do flag misspelled words such as 'dont'."
    )
    prompt = f"""
You are a careful English teacher checking ONE turn from a {level} learner who is practising natural Australian English.
Conversation topic: "{topic}"
Conversation so far (most recent last):
{context}

Learner's turn to check: "{user_text}"

Rules:
- Report only genuine errors in the learner's turn (grammar, word choice, word order, missing words, misspellings). Never "correct" something that is already natural.
- Very short replies such as "ok", "yeah" or "I don't know" are fine. If a turn is a typo you can't interpret, report one "{TYPO_TYPE}" error and don't invent a meaning.
- {input_note}
- Write explanations in simple English suitable for a {level} learner.

Return ONLY a JSON object with these keys:
"errors": a list (empty if there are no errors) of {{"original": "the shortest complete phrase or clause containing the error, copied exactly", "corrected": "that same phrase corrected", "error_type": one of {json.dumps(ERROR_TYPES)}, "explanation": "one short reason"}},
"corrected_sentence": "the learner's whole turn rewritten correctly and naturally, keeping their meaning, or null if there are no errors",
"corrected_phrase": "the short corrected phrase worth saving to a vocabulary list, or null if there are no errors",
"corrected_phrase_usage": "one short tip on when/how to naturally use the corrected phrase, or null",
"au_upgrade": {{"phrase": "a more natural, native Australian-English word/idiom/phrasal verb for the same idea", "meaning": "short meaning", "example": "one example sentence using it", "usage_tip": "one short tip on when/how to use this naturally"}} or null if the turn already sounds natural,
"topic_vocabulary": [
    {{"word": "a useful word/phrase related to the topic that the learner could use next", "meaning": "short, clear meaning", "example": "one example sentence in the context of this topic", "usage_tip": "one short tip on when/how to use it"}},
    {{"word": "...", "meaning": "...", "example": "...", "usage_tip": "..."}},
    {{"word": "...", "meaning": "...", "example": "...", "usage_tip": "..."}}
],
"fluency_tip": "one short, encouraging tip to sound more natural next time"
"""
    try:
        parsed = chat_json(prompt, 0.2)
    except Exception as e:
        return {
            "grammar_accurate": None, "errors": [], "grammar_feedback": f"⚠️ Couldn't check this turn ({e}).",
            "corrected_sentence": None, "corrected_phrase": None, "corrected_phrase_usage": None,
            "au_upgrade": None, "topic_vocabulary": [], "fluency_tip": "",
        }
    errors = normalize_errors(parsed.get("errors"))
    upgrade = parsed.get("au_upgrade")
    return {
        # Accuracy is derived from the error list itself so the verdict and the feedback can't contradict each other.
        "grammar_accurate": not errors,
        "errors": errors,
        "grammar_feedback": format_grammar_feedback(errors),
        "corrected_sentence": (parsed.get("corrected_sentence") or None) if errors else None,
        "corrected_phrase": (parsed.get("corrected_phrase") or None) if errors else None,
        "corrected_phrase_usage": (parsed.get("corrected_phrase_usage") or None) if errors else None,
        "au_upgrade": upgrade if isinstance(upgrade, dict) and upgrade.get("phrase") else None,
        "topic_vocabulary": [v for v in parsed.get("topic_vocabulary") or [] if isinstance(v, dict)],
        "fluency_tip": str(parsed.get("fluency_tip") or ""),
    }

# --- "SAY IT AGAIN" RETRY ---
RETRY_PASS_SCORE = 0.8

def build_retry(audit: dict, user_text: str, turn: int):
    """Sets up a 'say the corrected version' step after a turn with real (non-typo) mistakes."""
    errors = audit.get("errors") or []
    if not errors or all(e["error_type"] == TYPO_TYPE for e in errors):
        return None
    original, target = user_text, audit.get("corrected_sentence")
    if not target or len(normalize_words(target)) > 30:
        # Long turns: drill just the first mistake rather than the whole paragraph.
        original, target = errors[0]["original"], errors[0]["corrected"]
    return {
        "id": datetime.now().strftime("%H%M%S%f"), "turn": turn, "original": original, "target": target,
        "attempts": 0, "processed_audio_id": None, "feedback": None,
    }

def render_retry_gate(ns: str, data: dict, accent: str, evaluations_key: str = None) -> bool:
    """Renders the pending 'say it again' step for a chat namespace. Returns True while it's blocking the normal input."""
    evaluations_key = evaluations_key or f"{ns}_evaluations"
    retry = st.session_state.get(f"{ns}_retry")
    if not retry:
        return False
    rid = retry["id"]
    with st.container(border=True):
        st.markdown("#### 🔁 Say it again — the corrected way")
        st.caption("Producing the correct form yourself is what makes it stick. Say (or type) the corrected version before you carry on.")
        st.markdown(f"❌ You said: *{retry['original']}*")
        hidden = st.toggle("🙈 Hide the correction (try from memory)", key=f"{ns}_retry_hide_{rid}")
        if not hidden:
            st.markdown(f"✅ Corrected: **{retry['target']}**")
            render_message_tools(retry["target"], accent, f"{ns}_retry_{rid}", explain=False)

        attempt = None
        spoken = st.audio_input("Say the corrected version", key=f"{ns}_retry_audio_{rid}")
        if spoken:
            audio_id = hash(spoken.getvalue())
            if retry.get("processed_audio_id") != audio_id:
                retry["processed_audio_id"] = audio_id
                with st.spinner("Checking..."):
                    attempt = transcribe_audio(spoken.getvalue(), "retry.wav")["text"]
        typed = st.text_input("...or type it", key=f"{ns}_retry_text_{rid}")
        c1, c2 = st.columns(2)
        if c1.button("✅ Check", key=f"{ns}_retry_check_{rid}", width="stretch") and typed.strip():
            attempt = typed
        if c2.button("⏭️ Skip for now", key=f"{ns}_retry_skip_{rid}", width="stretch"):
            st.session_state[f"{ns}_retry"] = None
            queue_toast("Skipped — it's in your Mistakes notebook to practise later.", "⏭️")
            st.rerun()

        if attempt:
            retry["attempts"] += 1
            diff = word_diff(retry["target"], attempt)
            if diff["score"] >= RETRY_PASS_SCORE:
                for ev in st.session_state.get(evaluations_key, []):
                    if ev.get("turn") == retry["turn"]:
                        ev["retry_passed"] = True
                st.session_state[f"{ns}_retry"] = None
                record_practice(data, 10)
                queue_toast("🔁 Nailed it! +10 XP", "🎉")
                st.rerun()
            retry["feedback"] = {"attempt": attempt, **diff}

        feedback = retry.get("feedback")
        if feedback:
            st.warning(f"Not quite yet ({round(feedback['score'] * 100)}% match) — have another go. You said: “{feedback['attempt']}”")
            if not hidden:
                st.markdown(feedback["markdown"])
                st.caption(DIFF_LEGEND)
    return True

# --- SHARED CHAT SESSION (free conversation + real-life tasks) ---
TURN_HISTORY_FIELDS = (
    "timestamp", "topic", "transcript", "tutor_reply", "grammar_accurate", "grammar_feedback", "errors",
    "corrected_sentence", "corrected_phrase", "corrected_phrase_usage", "au_upgrade", "topic_vocabulary",
    "fluency_tip", "fluency", "used_words",
)

def init_chat_state(ns: str):
    for suffix, factory in (
        ("chat_history", list), ("active", bool), ("evaluations", list), ("reactions", list),
        ("processed_audio_id", lambda: None), ("retry", lambda: None), ("target_words", list),
        ("used_words", list), ("revealed", list),
    ):
        st.session_state.setdefault(f"{ns}_{suffix}", factory())

def start_chat_session(ns: str, system_prompt: str, default_avatar: str, data: dict, opening_instruction: str):
    """Resets a chat namespace and generates the partner's opening line. Weaves in saved words to practise."""
    targets = pick_target_words(data)
    if targets:
        system_prompt += (
            f"\n- The learner is trying to start actively using these words/phrases: {', '.join(targets)}. "
            "Naturally steer the conversation so they get chances to use them. Never list them or quiz the learner on them."
        )
    st.session_state[f"{ns}_chat_history"] = [{"role": "system", "content": system_prompt}]
    st.session_state[f"{ns}_evaluations"] = []
    st.session_state[f"{ns}_reactions"] = []
    st.session_state[f"{ns}_processed_audio_id"] = None
    st.session_state[f"{ns}_retry"] = None
    st.session_state[f"{ns}_target_words"] = targets
    st.session_state[f"{ns}_used_words"] = []
    st.session_state[f"{ns}_revealed"] = []
    st.session_state[f"{ns}_active"] = True
    for suffix in ("xp", "streak", "best_streak", "turns"):
        st.session_state[f"{ns}_{suffix}"] = 0
    reply, emoji = call_reaction_reply(
        st.session_state[f"{ns}_chat_history"] + [{"role": "user", "content": opening_instruction}],
        MODEL_CHOICE, 0.7, default_avatar
    )
    st.session_state[f"{ns}_chat_history"].append({"role": "assistant", "content": reply})
    st.session_state[f"{ns}_reactions"].append(emoji)

def process_chat_turn(ns: str, user_text: str, topic: str, default_avatar: str, data: dict, source: str, transcription: dict = None):
    history = st.session_state[f"{ns}_chat_history"]
    context_msgs = [m for m in history if m["role"] != "system"][-4:]
    history.append({"role": "user", "content": user_text})
    with st.chat_message("user"):
        st.write(user_text)

    if st.session_state.get("listen_first"):
        # Listen-first hides the reply text, so don't stream it onto the screen.
        with st.spinner("Replying..."):
            reply, emoji = call_reaction_reply(history, MODEL_CHOICE, 0.7, default_avatar)
    else:
        reply, emoji = stream_reply_with_reaction(history, MODEL_CHOICE, 0.7, default_avatar)
    history.append({"role": "assistant", "content": reply})
    st.session_state[f"{ns}_reactions"].append(emoji)

    with st.spinner("Coaching..."):
        audit = audit_learner_turn(user_text, topic, context_msgs, is_voice=transcription is not None)

    used = register_vocab_usage(data, user_text)
    turn_no = len(st.session_state[f"{ns}_evaluations"]) + 1
    eval_entry = {
        "turn": turn_no,
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "topic": topic,
        "transcript": user_text,
        "tutor_reply": reply,
        **audit,
        "fluency": fluency_stats(transcription) if transcription else None,
        "used_words": used,
    }
    st.session_state[f"{ns}_evaluations"].append(eval_entry)
    # Audit history persists both what you said and the partner's reply, so the saved
    # record is reviewable across sessions.
    data["tutor_turn_history"].insert(0, {**{k: eval_entry.get(k) for k in TURN_HISTORY_FIELDS}, "mode": source})
    log_errors(data, audit["errors"], source, topic, user_text)
    save_data(data)

    if used:
        st.session_state[f"{ns}_used_words"].extend(used)
        queue_toast(f"🎯 You used {', '.join(used)} — that's active vocabulary! +{10 * len(used)} XP", "🎉")
    if data["profile"].get("retry_enabled", True):
        st.session_state[f"{ns}_retry"] = build_retry(audit, user_text, turn_no)
    apply_gamification(ns, audit["grammar_accurate"], data, bonus_xp=10 * len(used))

def render_vocab_suggestion(label: str, item: dict, key: str, data: dict):
    """One suggested word/phrase with sample, usage tip and an Add-to-Vocab button."""
    st.markdown(f"**{label}** `{item['phrase']}` — {item.get('meaning', '')}" if label else f"**`{item['phrase']}`** — {item.get('meaning', '')}")
    if item.get("example"):
        st.caption(f"💬 Sample: *\"{item['example']}\"*")
    if item.get("usage_tip"):
        st.caption(f"📌 How to use it: {item['usage_tip']}")
    if st.button("➕ Add to Vocab", key=key, width="stretch"):
        if add_word_to_vocabulary(data, item["phrase"], item.get("meaning", ""), item.get("example", ""), item.get("usage_tip", "")):
            st.toast(f"Saved '{item['phrase']}'!", icon="✅")
        else:
            st.toast(f"'{item['phrase']}' is already in your list.", icon="ℹ️")

def render_turn_audits(ns: str, default_avatar: str, data: dict):
    st.subheader("📊 Turn-by-Turn Coaching")
    evaluations = st.session_state[f"{ns}_evaluations"]
    if not evaluations:
        st.caption("Grammar, AU-native phrasing, and fluency feedback will appear here after your first turn.")
        return
    auto_fixit = data["profile"].get("auto_fixit", True)
    # Scrolls within its own fixed-height box (matching the chat column) so a long
    # conversation doesn't keep growing the whole page taller with every turn -- that
    # unbounded growth was what pushed "Record your response"/chat controls far down
    # the page and made the page keep auto-scrolling further away from them.
    audit_box = st.container(height=420)
    for audit_idx, ev in enumerate(reversed(evaluations)):
        turn = ev["turn"]
        with audit_box, st.expander(f"Turn {turn} Audit", expanded=(audit_idx == 0)):
            # "You said" gets its own chat bubble so it's visually obvious this is
            # your own text, distinct from the partner's reply and coaching suggestions below.
            with st.chat_message("user"):
                st.caption("You said")
                st.write(ev["transcript"])
                if ev.get("fluency"):
                    st.caption(format_fluency(ev["fluency"]))

            with st.chat_message("assistant", avatar=default_avatar):
                st.caption("Reply")
                render_selectable_text(ev.get("tutor_reply", ""), key=f"{ns}_live_reply_{turn}", data=data)

            # Fallback: if the audit failed, just show the raw message.
            if ev.get("grammar_accurate") is None and not ev.get("au_upgrade") and not ev.get("fluency_tip") and not ev.get("topic_vocabulary"):
                render_selectable_text(ev.get("grammar_feedback", ""), key=f"{ns}_live_fallback_{turn}", data=data)
                continue

            # Coaching suggestions are grouped in their own bordered box, set apart
            # from "You said" and the conversational reply above.
            with st.container(border=True):
                st.markdown("#### 💡 Suggestions")

                if ev.get("used_words"):
                    st.success(f"🎯 You used your saved vocabulary: {', '.join(ev['used_words'])}")

                st.markdown("**✍️ Grammar & Accuracy:**")
                render_selectable_text(ev.get("grammar_feedback", ""), key=f"{ns}_live_grammar_{turn}", data=data)
                if ev.get("corrected_sentence"):
                    st.caption(f"✅ Whole turn, corrected: _{ev['corrected_sentence']}_")
                if ev.get("retry_passed"):
                    st.caption("🔁 You said it again correctly — nice work!")
                fixable = [e for e in ev.get("errors") or [] if e["error_type"] != TYPO_TYPE]
                if fixable and not auto_fixit:
                    if st.button("🛠️ Save as Fix-it card(s)", key=f"{ns}_fixit_{turn}", width="stretch"):
                        saved = sum(add_fixit_card(data, e["original"], e["corrected"], e["explanation"]) for e in fixable)
                        st.toast(f"Saved {saved} Fix-it card(s)!" if saved else "Already saved.", icon="🛠️")

                corrected = ev.get("corrected_phrase")
                if corrected:
                    st.caption(f"Corrected phrase: _{corrected}_")
                    usage = ev.get("corrected_phrase_usage")
                    if usage:
                        st.caption(f"📌 How to use it: {usage}")
                    if st.button("➕ Add to Vocab", key=f"{ns}_add_corr_{turn}", width="stretch"):
                        if add_word_to_vocabulary(data, corrected, "Corrected during turn-by-turn coaching", ev["transcript"], usage or "", enrich=False):
                            st.toast(f"Saved '{corrected}'!", icon="✅")
                        else:
                            st.toast(f"'{corrected}' is already in your list.", icon="ℹ️")

                upgrade = ev.get("au_upgrade") or {}
                if isinstance(upgrade, dict) and upgrade.get("phrase"):
                    st.divider()
                    render_vocab_suggestion("🇦🇺 Native AU Upgrade:", upgrade, f"{ns}_add_upgrade_{turn}", data)

                topic_vocab = [tv for tv in ev.get("topic_vocabulary") or [] if isinstance(tv, dict) and tv.get("word")]
                if topic_vocab:
                    st.divider()
                    st.markdown(f"**📚 Vocabulary Ideas for \"{ev.get('topic', '')}\":**")
                    for tv_idx, tv in enumerate(topic_vocab):
                        render_vocab_suggestion("", {**tv, "phrase": tv["word"]}, f"{ns}_add_topicvocab_{turn}_{tv_idx}", data)

                if ev.get("fluency_tip"):
                    st.divider()
                    st.markdown("**💬 Fluency Tip:**")
                    render_selectable_text(ev["fluency_tip"], key=f"{ns}_live_tip_{turn}", data=data)

def render_chat_session(ns: str, topic: str, default_avatar: str, data: dict, source: str):
    """Chat column (history, retry step, voice/text input) + turn-by-turn coaching column for a chat namespace."""
    col_chat, col_audit = st.columns([3, 2])
    with col_chat:
        m1, m2, m3 = st.columns(3)
        m1.metric("✨ XP", st.session_state.get(f"{ns}_xp", 0))
        m2.metric("🔥 Streak", st.session_state.get(f"{ns}_streak", 0))
        m3.metric("🏆 Best", st.session_state.get(f"{ns}_best_streak", 0))
        st.progress(min(1.0, st.session_state.get(f"{ns}_turns", 0) / 10), text="Session progress toward 10 turns")

        targets = st.session_state[f"{ns}_target_words"]
        if targets:
            used_now = {w.lower() for w in st.session_state[f"{ns}_used_words"]}
            chips = " · ".join(f"✅ ~~{w}~~" if w.lower() in used_now else f"`{w}`" for w in targets)
            st.caption(f"🎯 Try to use these saved words in this chat: {chips}")

        # Chat history scrolls within its own fixed-height box so the recorder/text input
        # below always stay visible without needing to scroll past the whole conversation.
        chat_box = st.container(height=420)
        listen_first = st.session_state.get("listen_first", False)
        revealed = st.session_state[f"{ns}_revealed"]
        reaction_idx = 0
        for t_idx, msg in enumerate(st.session_state[f"{ns}_chat_history"]):
            if msg["role"] == "system":
                continue
            if msg["role"] == "assistant":
                reactions = st.session_state[f"{ns}_reactions"]
                avatar = reactions[reaction_idx] if reaction_idx < len(reactions) else default_avatar
                reaction_idx += 1
                with chat_box, st.chat_message("assistant", avatar=avatar):
                    if listen_first and t_idx not in revealed:
                        st.caption("🎧 Listen first, then reveal the text.")
                        try:
                            st.audio(get_pronunciation_audio(msg["content"], "com.au"), format="audio/mp3")
                        except Exception:
                            st.caption("⚠️ Audio unavailable")
                        if st.button("👁️ Show text", key=f"{ns}_reveal_{t_idx}"):
                            revealed.append(t_idx)
                            st.rerun()
                    else:
                        render_selectable_text(msg["content"], key=f"{ns}_sel_{t_idx}", data=data)
                        render_message_tools(msg["content"], "com.au", f"{ns}_{t_idx}")
            else:
                with chat_box, st.chat_message("user"):
                    render_selectable_text(msg["content"], key=f"{ns}_sel_{t_idx}", data=data)

        st.markdown("---")
        if not render_retry_gate(ns, data, "com.au"):
            audio_file = st.audio_input("Record your response", key=f"{ns}_audio_input_widget")
            if audio_file:
                audio_bytes = audio_file.getvalue()
                audio_id = hash(audio_bytes)
                if st.session_state[f"{ns}_processed_audio_id"] != audio_id:
                    st.session_state[f"{ns}_processed_audio_id"] = audio_id
                    with st.spinner("Transcribing..."):
                        transcription = transcribe_audio(audio_bytes, keep_fillers=True)
                    if transcription["text"]:
                        process_chat_turn(ns, transcription["text"], topic, default_avatar, data, source, transcription)
                        st.rerun()
                    else:
                        st.warning("I couldn't hear anything in that recording — please try again.")

            text_turn = st.chat_input("...or type your response", key=f"{ns}_chat_input")
            if text_turn:
                process_chat_turn(ns, text_turn, topic, default_avatar, data, source)
                st.rerun()

    with col_audit:
        render_turn_audits(ns, default_avatar, data)

# --- REAL-LIFE TASKS ---
REAL_LIFE_TASKS = {
    "🩺 Book a GP appointment": {
        "role": "a friendly but busy receptionist at a suburban GP clinic in Melbourne",
        "situation": "You've had a sore throat and a fever for three days and want to see a doctor soon.",
        "goals": ["Say who you are and that you'd like an appointment", "Briefly explain your symptoms",
                  "Agree on a day and time that suits you", "Ask whether it's bulk-billed or how much it costs"],
    },
    "📦 Chase a late parcel": {
        "role": "a customer service agent for a parcel delivery company, working from a script",
        "situation": "Your online order was due four days ago. Tracking has said 'in transit' ever since.",
        "goals": ["Give your tracking number and explain the problem", "Ask what has happened to the parcel",
                  "Get a clear next step (a redelivery date or a refund)", "Confirm how you'll be kept updated"],
    },
    "🔧 Report a repair to your real estate agent": {
        "role": "a property manager at a real estate agency who looks after many rentals",
        "situation": "The hot water system in your rental flat stopped working yesterday.",
        "goals": ["Explain the problem and when it started", "Explain why it's urgent",
                  "Arrange when a tradesperson can come", "Confirm who pays for the repair"],
    },
    "☕ Small talk in the office kitchen": {
        "role": "a chatty Australian colleague from another team, making a coffee on Monday morning",
        "situation": "It's Monday morning and you run into a colleague you don't know well in the office kitchen.",
        "goals": ["Greet them and ask about their weekend", "Share something about your own weekend",
                  "Ask a follow-up question to keep the chat going", "Wrap up the chat politely and naturally"],
    },
    "🙋 Ask for clarification in a meeting": {
        "role": "a project manager running a team meeting who speaks quickly and uses jargon",
        "situation": "The project manager has just explained a new process in a team meeting and you didn't fully follow it.",
        "goals": ["Politely interrupt or get their attention", "Say which part you didn't understand",
                  "Check your understanding by repeating it in your own words", "Confirm what you need to do next"],
    },
    "📞 Phone screening interview": {
        "role": "a recruiter doing a 10-minute phone screening for a mid-level role",
        "situation": "A recruiter is calling you about a job you applied for.",
        "goals": ["Introduce yourself and your current role", "Give one specific example of an achievement",
                  "Answer the question about salary expectations", "Ask one good question about the role"],
    },
    "🧾 Return a faulty item at a shop": {
        "role": "a shop assistant at an electronics store who first offers store credit",
        "situation": "The headphones you bought last week stopped working in the left ear. You have the receipt.",
        "goals": ["Explain the problem and that you have the receipt", "Ask for a refund or replacement",
                  "Politely push back on the store-credit offer", "Agree on an outcome"],
    },
    "☕ Order at a café": {
        "role": "a busy Australian barista at a popular café",
        "situation": "You're ordering coffee and something to eat for yourself and a friend.",
        "goals": ["Order two different drinks clearly (e.g. a flat white and a long black)", "Ask about a food option",
                  "Answer the barista's questions (size, milk, name, dine in or takeaway)", "Pay and thank them naturally"],
    },
}
CUSTOM_TASK = "✏️ Custom task (describe your own)"

def build_task_prompt(task: dict) -> str:
    goals = "\n".join(f"  - {g}" for g in task["goals"])
    return f"""
You are role-playing a real-life situation so an English learner can practise practical Australian English.
Your role: {task['role']}
Situation (from the learner's side): {task['situation']}
The learner is trying to:
{goals}
Rules:
- Stay in character as a realistic person in this role. Don't complete the learner's goals for them; let them ask.
- If the learner is unclear, react like a real person would (ask them to clarify or repeat).
- Keep replies short (1-3 sentences), like a real conversation.
- Language level: {LEVEL_GUIDES[learner_level()]}
- Begin every reply with exactly one emoji in square brackets showing your current reaction (e.g. [😄], [🙂], [🤔], [😲]), then a space, then your message.
"""

def review_task(task: dict, history: list) -> dict:
    transcript = "\n".join(
        f"{'Them' if m['role'] == 'assistant' else 'Learner'}: {m['content']}" for m in history if m["role"] != "system"
    )
    prompt = f"""
You are reviewing a real-life English role-play. The learner ({learner_level()}) was practising practical Australian English.
Situation: {task['situation']}
The other person: {task['role']}
Learner's goals: {json.dumps(task['goals'], ensure_ascii=False)}

Transcript:
{transcript}

Return ONLY a JSON object with keys:
"goals": [{{"goal": "the goal text", "achieved": true or false, "evidence": "a short quote from the learner, or why it wasn't achieved"}}] (one per goal, same order),
"completed": true if the learner achieved the main purpose of the task, otherwise false,
"native_versions": [{{"you_said": "a line the learner actually said", "native": "how a native Australian would naturally say it here", "why": "one short reason"}}] (up to 4 of the learner's lines that would benefit most),
"overall_tip": "one practical tip for doing this task in real life"
"""
    return chat_json(prompt, 0.2)

def render_task_review(review: dict, data: dict):
    st.markdown("---")
    st.header("📋 Task Review")
    if review.get("error"):
        st.error(f"Couldn't review this task: {review['error']}")
        return
    goals = [g for g in review.get("goals") or [] if isinstance(g, dict)]
    achieved = sum(1 for g in goals if g.get("achieved"))
    if review.get("completed"):
        st.success(f"✅ Task completed — {achieved}/{len(goals)} goals achieved.")
    else:
        st.warning(f"⏳ Not quite there yet — {achieved}/{len(goals)} goals achieved. Try the task again!")
    for g in goals:
        st.markdown(f"{'✅' if g.get('achieved') else '⬜'} **{g.get('goal', '')}** — {g.get('evidence', '')}")

    natives = [n for n in review.get("native_versions") or [] if isinstance(n, dict) and n.get("native")]
    if natives:
        st.markdown("#### 🇦🇺 How a native speaker might say it")
        for n_idx, nv in enumerate(natives):
            with st.container(border=True):
                st.markdown(f"🗨️ You said: *{nv.get('you_said', '')}*")
                st.markdown(f"🇦🇺 Native: **{nv['native']}**")
                if nv.get("why"):
                    st.caption(nv["why"])
                render_message_tools(nv["native"], "com.au", f"task_native_{n_idx}", explain=False)
                if nv.get("you_said") and st.button("🛠️ Practise this as a Fix-it card", key=f"task_native_fix_{n_idx}"):
                    if add_fixit_card(data, nv["you_said"], nv["native"], nv.get("why", "")):
                        st.toast("Saved as a Fix-it card!", icon="🛠️")
                    else:
                        st.toast("Already in your cards.", icon="ℹ️")
    if review.get("overall_tip"):
        st.info(f"💡 {review['overall_tip']}")

# --- FLASHCARDS & VOCABULARY MANAGER (shared by both modes) ---
REVIEW_STYLES = {
    "🔀 Mixed": "mixed",
    "🗣️ Produce (meaning → phrase)": "produce",
    "🧩 Fill the gap": "cloze",
    "🔤 Recognise (phrase → meaning)": "recognise",
}

def _mask_word(text: str, word: str) -> str:
    pattern = _vocab_pattern(word)
    return pattern.sub("_____", text) if pattern and text else text

def _card_mode(item: dict, style: str) -> str:
    if item.get("card_type") == "fixit":
        return "fixit"
    can_cloze = bool(item.get("example")) and _mask_word(item["example"], item["word"]) != item["example"]
    can_produce = bool(item.get("meaning"))
    if style == "mixed":
        return random.choice(["recognise"] + (["produce"] if can_produce else []) + (["cloze"] if can_cloze else []))
    if style == "cloze" and can_cloze:
        return "cloze"
    if style in ("cloze", "produce") and can_produce:
        return "produce"
    return "recognise"

def render_flashcard_review(data: dict, ns: str):
    st.subheader("📚 Flashcards (Spaced Repetition)")
    st.caption("Fix-it cards come from your own mistakes. Produce and Fill-the-gap cards make you *say* the phrase, which builds speaking recall.")
    modified = False
    for item in data["vocabulary"]:
        if ensure_srs_fields(item):
            modified = True
    if modified:
        save_data(data)

    today_str = date.today().isoformat()
    due_cards = [it for it in data["vocabulary"] if it.get("due", today_str) <= today_str]
    due_fixit = sum(1 for it in due_cards if it.get("card_type") == "fixit")
    st.caption(f"**{len(due_cards)}** card(s) due ({due_fixit} Fix-it) out of {len(data['vocabulary'])} total.")

    style_label = st.radio("Review style", list(REVIEW_STYLES), horizontal=True, key=f"{ns}_flash_style")
    if st.button("🔁 Start Review Session", disabled=not due_cards, key=f"{ns}_flash_start", type="primary"):
        queue = [{"key": vocab_key(it["word"]), "mode": _card_mode(it, REVIEW_STYLES[style_label])} for it in due_cards]
        random.shuffle(queue)
        # The queue stores word keys, not card dicts: `data` is reloaded from disk on every rerun, so
        # reviews must update the freshly loaded card or they'd never be saved.
        st.session_state[f"{ns}_flash_queue"] = queue
        st.session_state[f"{ns}_flash_pos"] = 0
        st.session_state[f"{ns}_flash_show_answer"] = False
        st.session_state[f"{ns}_flash_result"] = None
        st.session_state[f"{ns}_flash_session"] = st.session_state.get(f"{ns}_flash_session", 0) + 1
        st.rerun()

    queue = st.session_state.get(f"{ns}_flash_queue", [])
    pos = st.session_state.get(f"{ns}_flash_pos", 0)
    if not queue:
        return
    if pos >= len(queue):
        st.success("🎉 Review session complete!")
        return

    entry = queue[pos]
    card = find_vocab(data, entry["key"])
    if card is None:  # deleted mid-session
        st.session_state[f"{ns}_flash_pos"] += 1
        st.rerun()
    mode = entry["mode"]
    result = st.session_state.get(f"{ns}_flash_result")
    card_key = f"{ns}_flash_{st.session_state.get(f'{ns}_flash_session', 0)}_{pos}"

    st.markdown("---")
    st.markdown(f"### Card {pos + 1} of {len(queue)}")
    ready_to_rate, suggested = False, None
    if mode == "recognise":
        st.markdown(f"## **{card['word']}**")
        render_message_tools(card["word"], "com.au", f"{card_key}_listen", explain=False)
        if not st.session_state.get(f"{ns}_flash_show_answer"):
            if st.button("👁️ Show Meaning", key=f"{card_key}_show"):
                st.session_state[f"{ns}_flash_show_answer"] = True
                st.rerun()
        else:
            st.markdown(f"**Meaning:** {card.get('meaning', '')}")
            if card.get("example"):
                st.caption(f"💬 *\"{card['example']}\"*")
            ready_to_rate = True
    else:
        if mode == "fixit":
            st.markdown("#### 🛠️ Fix this sentence")
            st.markdown(f"> {card.get('original', '')}")
        elif mode == "produce":
            st.markdown("#### 🗣️ Say or type the word/phrase that means:")
            st.markdown(f"> {_mask_word(card.get('meaning', ''), card['word'])}")
        else:
            st.markdown("#### 🧩 Fill the gap:")
            st.markdown(f"> {_mask_word(card.get('example', ''), card['word'])}")
            if card.get("meaning"):
                st.caption(f"Hint: {_mask_word(card['meaning'], card['word'])}")

        if result is None:
            attempt = None
            typed = st.text_input("Your answer", key=f"{card_key}_text")
            spoken = st.audio_input("...or say it", key=f"{card_key}_audio")
            if spoken:
                with st.spinner("Listening..."):
                    attempt = transcribe_audio(spoken.getvalue(), "card.wav")["text"]
                if not attempt:
                    st.warning("I couldn't hear anything — please try again.")
            b1, b2 = st.columns(2)
            if b1.button("✅ Check", key=f"{card_key}_check", width="stretch") and typed.strip():
                attempt = typed
            if b2.button("🤷 Show answer", key=f"{card_key}_giveup", width="stretch"):
                st.session_state[f"{ns}_flash_result"] = {"attempt": "", "correct": False, "close": False, "feedback": ""}
                st.rerun()
            if attempt:
                if mode == "fixit":
                    with st.spinner("Checking..."):
                        correct, feedback = judge_answers([(card.get("original", ""), card["word"], attempt)])[0]
                    close = False
                else:
                    ratio = similarity(card["word"], attempt)
                    pattern = _vocab_pattern(card["word"])
                    correct = ratio >= 0.8 or bool(pattern and pattern.search(attempt))
                    close, feedback = (not correct and ratio >= 0.5), ""
                st.session_state[f"{ns}_flash_result"] = {"attempt": attempt, "correct": correct, "close": close, "feedback": feedback}
                st.rerun()
        else:
            if result["correct"]:
                st.success("✅ Correct!")
            elif result["close"]:
                st.warning("🟡 Close!")
            elif result["attempt"]:
                st.error("❌ Not quite.")
            if result["attempt"]:
                st.caption(f"You answered: “{result['attempt']}”")
            if result.get("feedback"):
                st.caption(result["feedback"])
            st.markdown(f"**Answer:** {card['word']}")
            if mode == "fixit":
                if result["attempt"] and not result["correct"]:
                    st.markdown(word_diff(card["word"], result["attempt"])["markdown"])
                    st.caption(DIFF_LEGEND)
                if card.get("meaning"):
                    st.caption(f"💡 {card['meaning']}")
            else:
                st.caption(f"**Meaning:** {card.get('meaning', '')}")
                if card.get("example"):
                    st.caption(f"💬 *\"{card['example']}\"*")
            render_message_tools(card["word"], "com.au", f"{card_key}_answer", explain=False)
            ready_to_rate = True
            suggested = 2 if result["correct"] else (1 if result["close"] else 0)

    if ready_to_rate:
        cols = st.columns(4)
        for col, (label, quality) in zip(cols, [("Again", 0), ("Hard", 1), ("Good", 2), ("Easy", 3)]):
            if col.button(label, key=f"{card_key}_{label}", type="primary" if quality == suggested else "secondary", width="stretch"):
                sm2_update(card, quality)
                record_practice(data, 2)
                st.session_state[f"{ns}_flash_pos"] += 1
                st.session_state[f"{ns}_flash_show_answer"] = False
                st.session_state[f"{ns}_flash_result"] = None
                st.rerun()

def render_vocabulary_manager(data: dict, ns: str):
    st.subheader("📒 My Vocabulary")
    with st.expander("➕ Add a word or phrase"):
        with st.form(f"{ns}_add_vocab_form", clear_on_submit=True):
            c1, c2 = st.columns(2)
            with c1:
                new_word = st.text_input("Word / Phrase (e.g. 'rally the team')")
            with c2:
                new_meaning = st.text_input("Meaning (leave empty to fill it in automatically)")
            new_example = st.text_area("Example Sentence")
            if st.form_submit_button("➕ Add to Vocabulary") and new_word.strip():
                with st.spinner("Saving..."):
                    added = add_word_to_vocabulary(data, new_word, new_meaning, new_example)
                if added:
                    st.success(f"Added '{new_word.strip()}'!")
                else:
                    st.info(f"'{new_word.strip()}' is already in your list.")

    missing = [v for v in data["vocabulary"] if not v.get("meaning")]
    if missing and st.button(f"✨ Fill in {len(missing)} missing meaning(s)", key=f"{ns}_enrich_missing"):
        with st.spinner("Looking up meanings..."):
            for item in missing:
                fields = enrich_vocab_fields(item["word"])
                if fields.get("meaning"):
                    item["meaning"] = _combine_meaning(fields["meaning"], fields.get("usage_tip", ""))
                    item["example"] = item.get("example") or fields.get("example", "")
        save_data(data)
        st.rerun()

    with st.expander(f"📖 Word list ({len(data['vocabulary'])})"):
        f1, f2 = st.columns([2, 3])
        search = f1.text_input("Search", key=f"{ns}_vocab_search").strip().lower()
        kind = f2.radio("Show", ["All", "Words & phrases", "Fix-it cards"], horizontal=True, key=f"{ns}_vocab_kind")
        shown = 0
        for v_idx, item in enumerate(data["vocabulary"]):
            is_fixit = item.get("card_type") == "fixit"
            if (kind == "Words & phrases" and is_fixit) or (kind == "Fix-it cards" and not is_fixit):
                continue
            if search and search not in f"{item['word']} {item.get('original', '')} {item.get('meaning', '')}".lower():
                continue
            shown += 1
            col_v1, col_v2 = st.columns([4, 1])
            with col_v1:
                if is_fixit:
                    st.markdown(f"🛠️ ❌ ~~{item.get('original', '')}~~ → ✅ **{item['word']}**")
                else:
                    st.markdown(f"**{item['word']}**")
                if item.get("meaning"):
                    st.caption(f"**Meaning:** {item['meaning']}")
                if item.get("example"):
                    st.caption(f"💬 *\"{item['example']}\"*")
                used = f" · used by you {item['used_count']}×" if item.get("used_count") else ""
                st.caption(f"Next review: {item.get('due', 'today')}{used}")
            with col_v2:
                if st.button("🗑️ Delete", key=f"{ns}_del_vocab_{v_idx}"):
                    data["vocabulary"].pop(v_idx)
                    save_data(data)
                    st.rerun()
        if not shown:
            st.caption("Nothing to show.")

# --- LISTENING PRACTICE HELPERS ---
LISTENING_TOPICS = ["Everyday life", "At work", "Shopping & cafés", "Transport & directions",
                    "Health & appointments", "Weather & weekend plans", "Renting & home"]
LISTENING_SOURCES = ["New sentence for my level", "My vocabulary & Fix-it cards", "Recent tutor replies"]
SENTENCE_LENGTH_BY_LEVEL = {"A2": "6-10 words", "B1": "8-14 words", "B2": "12-20 words", "C1": "15-25 words"}

def _speakable(text: str) -> str:
    """Strips emoji/reaction tags so TTS and comparisons only see words."""
    text = re.sub(r"\[[^\]]{1,4}\]", "", text or "")
    return re.sub(r"[^\w\s.,!?'’\-:;\"()]", "", text).strip()

def next_listening_sentence(source: str, topic: str, data: dict) -> str:
    if source == LISTENING_SOURCES[1]:
        vocab = data["vocabulary"]
        pool = [v["example"] for v in vocab if v.get("example") and v.get("card_type") != "fixit"]
        pool += [v["word"] for v in vocab if v.get("card_type") == "fixit" and len(v["word"].split()) >= 4]
    elif source == LISTENING_SOURCES[2]:
        pool = []
        for turn in data["tutor_turn_history"][:40]:
            for sentence in re.split(r"(?<=[.!?])\s+", _speakable(turn.get("tutor_reply", ""))):
                if 6 <= len(sentence.split()) <= 25:
                    pool.append(sentence.strip())
    else:
        level = learner_level()
        prompt = f"""
Write one natural sentence that an Australian might say out loud in everyday life about "{topic}".
It should suit a {level} listener and be {SENTENCE_LENGTH_BY_LEVEL.get(level.split(' ')[0], '8-14 words')} long, using natural spoken features like contractions.
Return ONLY a JSON object {{"sentence": "..."}}
"""
        try:
            return _speakable(str(chat_json(prompt, 0.9).get("sentence") or ""))
        except Exception:
            return ""
    pool = [_speakable(p) for p in pool if _speakable(p)]
    return random.choice(pool) if pool else ""

def accuracy_by_week(turns: list) -> tuple:
    """Grammar-accuracy % for the last 7 days and the 7 days before that (None where there's no data)."""
    today = date.today()
    def pct(start: date, end: date):
        graded = [
            t for t in turns
            if t.get("grammar_accurate") is not None and start.isoformat() <= (t.get("timestamp") or "")[:10] <= end.isoformat()
        ]
        return round(100 * sum(1 for t in graded if t["grammar_accurate"]) / len(graded)) if graded else None
    return pct(today - timedelta(days=6), today), pct(today - timedelta(days=13), today - timedelta(days=7))

# --- STATIC GRAMMAR LESSON LIBRARY ---
GRAMMAR_TOPICS = {
    "Present Simple vs Present Perfect": {
        "explanation": "Use **present simple** for habits, facts, and routines (*I work in Sydney*). Use **present perfect** for past actions with present relevance, or unfinished time periods (*I have worked in Sydney for two years*).",
        "examples": ["She **visits** her parents every weekend.", "She **has visited** Melbourne three times this year."],
        "quiz": [
            {"question": "Choose the correct sentence:", "options": ["I have lived here since 2020.", "I live here since 2020."], "answer": 0, "explain": "'Since' marks a starting point for an unfinished period, which requires present perfect."},
            {"question": "Choose the correct sentence:", "options": ["He has finished his homework, so he watches TV now.", "He finished his homework, so he is watching TV now."], "answer": 1, "explain": "A completed action followed by a specific current result is usually simple past + present continuous."}
        ]
    },
    "Articles (a / an / the)": {
        "explanation": "Use **a/an** for a non-specific, first mention (*I saw a dog*). Use **the** for something specific or already mentioned (*the dog barked*). No article for general plural/uncountable nouns (*Dogs are loyal*).",
        "examples": ["I bought **an** umbrella yesterday.", "**The** umbrella I bought yesterday broke already."],
        "quiz": [
            {"question": "Fill the gap: 'She is ___ engineer.'", "options": ["a", "an", "the"], "answer": 1, "explain": "'Engineer' starts with a vowel sound, so use 'an'."},
            {"question": "Fill the gap: 'I love ___ music.'", "options": ["a", "the", "(no article)"], "answer": 2, "explain": "General/uncountable nouns used generically take no article."}
        ]
    },
    "Prepositions of Time & Place": {
        "explanation": "Time: **in** (months/years), **on** (days/dates), **at** (clock times). Place: **in** (enclosed spaces), **on** (surfaces), **at** (points/addresses).",
        "examples": ["The meeting is **at** 3pm **on** Friday **in** March.", "She's waiting **at** the station **on** the platform."],
        "quiz": [
            {"question": "Fill the gap: 'I'll see you ___ Monday.'", "options": ["in", "on", "at"], "answer": 1, "explain": "Days of the week take 'on'."},
            {"question": "Fill the gap: 'The keys are ___ the table.'", "options": ["in", "on", "at"], "answer": 1, "explain": "A flat surface takes 'on'."}
        ]
    },
    "Conditionals (Zero, First, Second)": {
        "explanation": "**Zero**: facts (*If you heat water, it boils*). **First**: real future possibility (*If it rains, I'll take an umbrella*). **Second**: hypothetical/unlikely (*If I won the lottery, I would travel*).",
        "examples": ["If I **have** time, I **will call** you.", "If I **were** you, I **would apologise**."],
        "quiz": [
            {"question": "Choose the correct sentence:", "options": ["If I was rich, I would travel the world.", "If I were rich, I would travel the world."], "answer": 1, "explain": "Second conditional traditionally uses 'were' for all subjects in formal register."},
            {"question": "Choose the correct sentence:", "options": ["If you heat ice, it melts.", "If you heat ice, it will melt."], "answer": 0, "explain": "Zero conditional (general truths) uses present simple in both clauses."}
        ]
    },
    "Subject-Verb Agreement": {
        "explanation": "The verb must match its subject in number. Watch out for collective nouns, indefinite pronouns, and phrases between subject and verb.",
        "examples": ["Each of the students **has** a laptop.", "The team **is** meeting tomorrow."],
        "quiz": [
            {"question": "Choose the correct sentence:", "options": ["Neither of them are ready.", "Neither of them is ready."], "answer": 1, "explain": "'Neither' is singular and takes a singular verb."},
            {"question": "Choose the correct sentence:", "options": ["The list of items are on the desk.", "The list of items is on the desk."], "answer": 1, "explain": "The subject is 'list' (singular); 'items' is part of a modifying phrase."}
        ]
    }
}

def render_english_tutor():
    """General English tutor mode: conversation practice, real-life tasks, writing correction, grammar lessons,
    mistakes notebook, SRS flashcards, listening, pronunciation drills and progress tracking."""
    init_chat_state("tutor")
    init_chat_state("task")
    for state_key, default_val in (("tutor_topic", ""), ("pron_processed_audio_id", None), ("task_current", None), ("task_review", None)):
        if state_key not in st.session_state:
            st.session_state[state_key] = default_val

    st.sidebar.markdown("---")
    st.sidebar.caption("🇦🇺 Tutor mode is tuned for natural, native Australian English.")
    st.sidebar.toggle(
        "🎧 Listen-first mode", key="listen_first",
        help="Hide the partner's text until you've listened to it, to train real listening comprehension."
    )

    st.title("🇦🇺 General English Tutor")
    t_conv, t_task, t_write, t_grammar, t_mistakes, t_flash, t_listen, t_pron, t_progress = st.tabs([
        "🗣️ Conversation", "🎯 Real-life Tasks", "✍️ Writing", "📖 Grammar", "🧠 My Mistakes",
        "📚 Flashcards", "🎧 Listening", "🎤 Pronunciation", "📈 Progress"
    ])
    level = learner_level()

    # ---------------- TAB: CONVERSATION PRACTICE ----------------
    with t_conv:
        st.subheader("🗣️ Free Conversation Practice")
        st.caption("Chat by voice or text. Every turn is audited for grammar, natural AU phrasing, and fluency.")

        topics = [
            "Everyday Life & Hobbies", "Travel & Culture", "Work & Career (casual)",
            "Food & Health", "News & Opinions", "Free Topic (type your own)"
        ]
        col_t1, col_t2 = st.columns([2, 3])
        with col_t1:
            topic_choice = st.selectbox("Conversation Topic", topics)
        with col_t2:
            custom_topic = st.text_input("Custom topic (used if 'Free Topic' selected)", disabled=topic_choice != "Free Topic (type your own)")

        active_topic = custom_topic.strip() if topic_choice == "Free Topic (type your own)" and custom_topic.strip() else topic_choice

        TUTOR_SYSTEM_PROMPT = f"""
You are a friendly, native Australian English speaking partner helping the user practise everyday spoken/written English.
Topic: {active_topic}
Rules:
- Speak naturally, using authentic Australian English.
- Language level: {LEVEL_GUIDES[level]}
- Keep replies short (2-4 sentences) and ask an engaging follow-up question.
- Stay encouraging and conversational, not formal. Don't correct the learner's grammar yourself; a separate coach does that.
- Begin every reply with exactly one emoji in square brackets showing your current reaction (e.g. [😄], [🙂], [🤔], [😲]), then a space, then your message.
"""
        if st.button("🎬 Start New Conversation", type="primary", width="stretch"):
            with st.spinner("Starting conversation..."):
                start_chat_session("tutor", TUTOR_SYSTEM_PROMPT, "🇦🇺", data, "Start the conversation with a friendly opening line or question.")
            st.session_state["tutor_topic"] = active_topic
            st.rerun()

        if st.session_state["tutor_active"]:
            render_chat_session("tutor", st.session_state["tutor_topic"] or active_topic, "🇦🇺", data, source="tutor")
        else:
            st.info("Pick a topic and click **🎬 Start New Conversation** to begin.")

        # ---- PERSISTED PROGRESS TRACKING ACROSS ALL SESSIONS ----
        # Tracks both what you said and the partner's reply so the saved audit trail is a reviewable
        # log of the full exchange, across every past conversation session.
        turn_log = data.get("tutor_turn_history", [])
        st.markdown("---")
        with st.expander(f"📈 Coaching Progress History ({len(turn_log)} turn(s) tracked)"):
            if not turn_log:
                st.caption("No turns have been audited yet. Start a conversation above to begin tracking your progress.")
            else:
                graded = [t for t in turn_log if t.get("grammar_accurate") is not None]
                accurate = sum(1 for t in graded if t.get("grammar_accurate") is True)
                if graded:
                    st.caption(f"✅ Grammar accurate on **{accurate}/{len(graded)}** tracked turns ({round(100 * accurate / len(graded))}%).")
                for h_idx, h in enumerate(turn_log[:30]):
                    st.markdown(f"**{h.get('timestamp', '')}** · _{h.get('topic', '')}_")
                    st.caption(f"**You said:** \"{h.get('transcript', '')}\"")
                    st.caption("**Reply:**")
                    render_selectable_text(h.get("tutor_reply", ""), key=f"tutor_hist_reply_{h_idx}", data=data)
                    st.caption(h.get("grammar_feedback", ""))
                    st.divider()

    # ---------------- TAB: REAL-LIFE TASKS ----------------
    with t_task:
        st.subheader("🎯 Real-life Tasks")
        st.caption("Practise the conversations you actually have in Australia. The tutor plays the other person — you complete the goals.")
        task_choice = st.selectbox("Choose a task", list(REAL_LIFE_TASKS) + [CUSTOM_TASK], key="task_choice")
        if task_choice == CUSTOM_TASK:
            situation = st.text_area("Situation (what's happening, from your side)", key="custom_task_situation")
            role = st.text_input("Who should the tutor play?", key="custom_task_role")
            goals_text = st.text_area("Your goals (one per line)", key="custom_task_goals")
            task = {"situation": situation.strip(), "role": role.strip(), "goals": [g.strip() for g in goals_text.splitlines() if g.strip()]}
            task_name = f"Custom: {situation.strip()[:50]}"
        else:
            task, task_name = REAL_LIFE_TASKS[task_choice], task_choice

        with st.container(border=True):
            st.markdown(f"**📍 Situation:** {task['situation'] or '_describe it above_'}")
            st.markdown(f"**🎭 The tutor plays:** {task['role'] or '_describe it above_'}")
            st.markdown("**✅ Your goals:**")
            for goal in task["goals"]:
                st.markdown(f"- {goal}")

        task_ready = bool(task["situation"] and task["role"] and task["goals"])
        if st.button("🎬 Start Task", type="primary", width="stretch", disabled=not task_ready, key="task_start"):
            with st.spinner("Setting the scene..."):
                start_chat_session(
                    "task", build_task_prompt(task), "🎭", data,
                    "Start the scenario with your natural opening line for this situation (for example, answering the phone or greeting the customer)."
                )
            st.session_state["task_current"] = {"name": task_name, **task}
            st.session_state["task_review"] = None
            st.rerun()

        if st.session_state["task_active"] and st.session_state["task_current"]:
            current = st.session_state["task_current"]
            c_info, c_finish = st.columns([3, 1])
            c_info.info(f"In progress: **{current['name']}**")
            if c_finish.button("🏁 Finish & Review", width="stretch", key="task_finish"):
                with st.spinner("Reviewing how you did..."):
                    try:
                        review = review_task(current, st.session_state["task_chat_history"])
                    except Exception as e:
                        review = {"error": str(e)}
                st.session_state["task_review"] = review
                st.session_state["task_active"] = False
                st.session_state["task_retry"] = None
                if not review.get("error"):
                    goals = [g for g in review.get("goals") or [] if isinstance(g, dict)]
                    data["task_history"].insert(0, {
                        "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "task": current["name"],
                        "completed": bool(review.get("completed")),
                        "goals": f"{sum(1 for g in goals if g.get('achieved'))}/{len(goals)}",
                        "turns": len(st.session_state["task_evaluations"]),
                    })
                    record_practice(data, 20 if review.get("completed") else 10)
                st.rerun()
            render_chat_session("task", current["name"], "🎭", data, source="task")
        elif st.session_state.get("task_review"):
            render_task_review(st.session_state["task_review"], data)

    # ---------------- TAB: WRITING CORRECTION ----------------
    with t_write:
        st.subheader("✍️ Writing Correction & Coaching")
        st.caption("Paste an email, message, or essay to get a corrected version with native Australian-English suggestions.")
        draft = st.text_area("Your text", height=200, key="writing_draft_input")

        if draft.strip():
            sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", draft.strip()) if s.strip()]
            words = draft.split()
            long_sentences = [s for s in sentences if len(s.split()) > 30]
            repeats = re.findall(r"\b(\w+)\s+\1\b", draft, flags=re.IGNORECASE)
            signal_bits = [f"📝 {len(words)} words · {len(sentences)} sentence(s)"]
            if long_sentences:
                signal_bits.append(f"⚠️ {len(long_sentences)} sentence(s) over 30 words — consider splitting them")
            if repeats:
                signal_bits.append(f"⚠️ Repeated word(s): {', '.join(sorted(set(w.lower() for w in repeats)))}")
            st.caption(" · ".join(signal_bits))

        if st.button("🔍 Correct & Coach My Writing", type="primary"):
            if draft.strip():
                with st.spinner("Reviewing your writing..."):
                    write_prompt = f"""
You are a native Australian English writing coach. Review this text from a {level} learner:
---
{draft}
---
Respond with these sections:
1. ✅ **Corrected Version:** The full corrected text.
2. 🛠️ **Key Fixes:** Bullet list of *Original -> Corrected* for each notable grammar/spelling/word-choice error, with a one-line reason in simple English.
3. 🇦🇺 **Native AU Phrasing Upgrades:** 2-3 suggestions to make the writing sound more natural/native (Australian English).
"""
                    st.session_state["writing_result"] = chat_text(write_prompt, 0.2)
                    st.session_state["writing_last_draft"] = draft
            else:
                st.warning("Please enter some text first.")

        if st.session_state.get("writing_result"):
            st.markdown("---")
            render_selectable_text(st.session_state["writing_result"], key="writing_result_sel", data=data)
            if st.button("💾 Save to Writing History"):
                data["writing_history"].insert(0, {
                    "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "draft": st.session_state.get("writing_last_draft", ""),
                    "result": st.session_state["writing_result"]
                })
                save_data(data)
                st.success("Saved!")

        if data["writing_history"]:
            st.markdown("---")
            st.subheader("📜 Writing History")
            for w_idx, entry in enumerate(data["writing_history"]):
                with st.expander(f"🗓️ {entry['id']}"):
                    st.caption("**Original draft:**")
                    st.write(entry["draft"])
                    render_selectable_text(entry["result"], key=f"writing_hist_sel_{w_idx}", data=data)

    # ---------------- TAB: GRAMMAR LESSONS ----------------
    with t_grammar:
        st.subheader("📖 Grammar Lessons & Quizzes")
        topic_names = list(GRAMMAR_TOPICS.keys()) + ["Custom Topic (ask the tutor)"]
        chosen_topic = st.selectbox("Choose a grammar topic", topic_names)

        lesson = None
        if chosen_topic == "Custom Topic (ask the tutor)":
            custom_grammar_topic = st.text_input("What grammar topic would you like to learn? (e.g. 'reported speech')")
            if st.button("📘 Generate Lesson") and custom_grammar_topic.strip():
                with st.spinner("Preparing your lesson..."):
                    gen_prompt = f"""
Create a short English grammar lesson about: "{custom_grammar_topic}" for a {level} learner.
Return ONLY a JSON object with keys:
"explanation": "concise markdown explanation",
"examples": ["example 1", "example 2"],
"quiz": [{{"question": "...", "options": ["A", "B", "C"], "answer": 0, "explain": "..."}}, {{"question": "...", "options": ["A", "B", "C"], "answer": 0, "explain": "..."}}]
"""
                    try:
                        st.session_state["custom_grammar_lesson"] = chat_json(gen_prompt, 0.3)
                    except Exception as e:
                        st.error(f"Could not generate lesson: {e}")
            lesson = st.session_state.get("custom_grammar_lesson")
        else:
            lesson = GRAMMAR_TOPICS[chosen_topic]

        if lesson:
            st.markdown(lesson.get("explanation", ""))
            st.markdown("**Examples:**")
            for ex in lesson.get("examples", []):
                st.markdown(f"- {ex}")

            st.markdown("---")
            st.markdown("**Quick Quiz**")
            with st.form(f"quiz_form_{chosen_topic}"):
                user_answers = []
                for q_idx, q in enumerate(lesson.get("quiz", [])):
                    user_answers.append(st.radio(q["question"], q["options"], key=f"quiz_{chosen_topic}_{q_idx}", index=None))
                submitted = st.form_submit_button("✅ Check Answers")

            if submitted:
                correct = 0
                for q_idx, q in enumerate(lesson.get("quiz", [])):
                    is_correct = user_answers[q_idx] == q["options"][q["answer"]]
                    correct += is_correct
                    icon = "✅" if is_correct else "❌"
                    st.markdown(f"{icon} **Q{q_idx + 1}:** {q['explain']}")
                score_pct = round(100 * correct / max(1, len(lesson.get("quiz", []))))
                st.info(f"Score: {correct}/{len(lesson.get('quiz', []))} ({score_pct}%)")
                data["grammar_scores"].insert(0, {
                    "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "topic": chosen_topic, "score": f"{correct}/{len(lesson.get('quiz', []))}"
                })
                record_practice(data, 3 * correct)

        if data["grammar_scores"]:
            with st.expander(f"🗂️ Quiz & drill history ({len(data['grammar_scores'])})"):
                st.dataframe(
                    [{"Date": s.get("id", ""), "Topic": s.get("topic", ""), "Score": s.get("score", "")} for s in data["grammar_scores"][:30]],
                    hide_index=True, width="stretch"
                )

    # ---------------- TAB: MISTAKES NOTEBOOK ----------------
    with t_mistakes:
        st.subheader("🧠 My Mistakes Notebook")
        st.caption("Every mistake found in your conversations, tasks and leadership simulations, grouped by type so you can drill the ones that keep coming back.")
        error_log = data["error_log"]
        if not error_log:
            st.info("No mistakes logged yet. They'll appear here as you practise.")
        else:
            unclassified = sum(1 for e in error_log if e.get("error_type") == "unclassified")
            if unclassified:
                st.info(f"🏷️ {unclassified} older mistake(s) from before this notebook existed haven't been sorted into types yet.")
                if st.button("🏷️ Classify older mistakes", key="classify_errors"):
                    with st.spinner("Classifying..."):
                        try:
                            updated, removed = classify_unclassified_errors(data)
                            queue_toast(f"Classified {updated}; removed {removed} that weren't real mistakes.", "🏷️")
                        except Exception as e:
                            queue_toast(f"Couldn't classify: {e}", "⚠️")
                    st.rerun()

            week_top = top_error_types(data, days=7)
            all_time = top_error_types(data)
            st.markdown("#### 🔥 Top recurring mistakes this week")
            if week_top:
                for col, (etype, count) in zip(st.columns(3), week_top[:3]):
                    col.metric(etype, f"{count}×")
            else:
                st.caption("No mistakes logged in the last 7 days 🎉")
            if all_time:
                week_map = dict(week_top)
                st.dataframe(
                    [{"Mistake type": t, "Last 7 days": week_map.get(t, 0), "All time": c} for t, c in all_time],
                    hide_index=True, width="stretch"
                )

                st.markdown("#### 🏋️ Personal drill")
                st.caption("Questions are built from your own sentences, then similar new ones. Type each sentence correctly.")
                drill_type = st.selectbox("Mistake type to drill", [t for t, _ in all_time], key="drill_type_select")
                if st.button("🏋️ Generate a 5-question drill", type="primary", key="drill_generate"):
                    examples = [{"original": e["original"], "corrected": e["corrected"]} for e in error_log if e.get("error_type") == drill_type][:6]
                    with st.spinner("Building your drill..."):
                        try:
                            st.session_state["drill"] = {"id": datetime.now().strftime("%H%M%S%f"), "type": drill_type, "items": generate_drill(drill_type, examples), "results": None}
                        except Exception as e:
                            st.error(f"Could not build a drill: {e}")
                drill = st.session_state.get("drill")
                if drill and drill["items"]:
                    with st.form(f"drill_form_{drill['id']}"):
                        st.markdown(f"**Drill: {drill['type']}**")
                        answers = [
                            st.text_input(f"{i + 1}. {item['wrong']}", key=f"drill_{drill['id']}_{i}", placeholder="Rewrite the sentence correctly")
                            for i, item in enumerate(drill["items"])
                        ]
                        drill_submitted = st.form_submit_button("✅ Check my answers")
                    if drill_submitted:
                        with st.spinner("Checking..."):
                            verdicts = judge_answers([(item["wrong"], item["answer"], ans) for item, ans in zip(drill["items"], answers)])
                        drill["results"] = [{"attempt": ans, "correct": ok, "feedback": fb} for ans, (ok, fb) in zip(answers, verdicts)]
                        correct = sum(1 for r in drill["results"] if r["correct"])
                        data["grammar_scores"].insert(0, {
                            "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                            "topic": f"Drill: {drill['type']}", "score": f"{correct}/{len(drill['items'])}"
                        })
                        record_practice(data, 3 * correct)
                    if drill["results"]:
                        correct = sum(1 for r in drill["results"] if r["correct"])
                        st.info(f"Score: {correct}/{len(drill['items'])}")
                        for i, (item, res) in enumerate(zip(drill["items"], drill["results"])):
                            st.markdown(f"{'✅' if res['correct'] else '❌'} **{i + 1}.** {item['answer']}")
                            if res["attempt"]:
                                st.caption(f"You wrote: “{res['attempt']}”")
                            if res["feedback"]:
                                st.caption(res["feedback"])
                            if item["explain"]:
                                st.caption(f"💡 {item['explain']}")
                            if not res["correct"] and st.button("🛠️ Save as Fix-it card", key=f"drill_fix_{drill['id']}_{i}"):
                                if add_fixit_card(data, item["wrong"], item["answer"], item["explain"]):
                                    st.toast("Saved as a Fix-it card!", icon="🛠️")
                                else:
                                    st.toast("Already in your cards.", icon="ℹ️")

            st.markdown("#### 📒 Mistake log")
            groups = [t for t, _ in all_time] + (["unclassified"] if unclassified else [])
            for etype in groups:
                entries = [(i, e) for i, e in enumerate(error_log) if e.get("error_type") == etype]
                with st.expander(f"{etype} ({len(entries)})"):
                    for log_idx, e in entries[:30]:
                        c_e1, c_e2 = st.columns([5, 1])
                        c_e1.markdown(f"❌ *{e.get('original', '')}* → ✅ **{e.get('corrected', '')}**")
                        c_e1.caption(f"{e.get('timestamp', '')} · {e.get('source', '')} · {e.get('explanation', '')}")
                        if c_e2.button("🗑️", key=f"err_del_{log_idx}", help="Remove from notebook"):
                            error_log.pop(log_idx)
                            save_data(data)
                            st.rerun()

    # ---------------- TAB: VOCABULARY FLASHCARDS (SRS) ----------------
    with t_flash:
        render_flashcard_review(data, "tutor")
        st.markdown("---")
        render_vocabulary_manager(data, "tutor")

    # ---------------- TAB: LISTENING PRACTICE ----------------
    with t_listen:
        st.subheader("🎧 Listening Practice")
        st.caption("Dictation trains you to catch every word at natural speed; shadowing trains your mouth to copy native rhythm.")
        listen_mode = st.radio("Exercise", ["✍️ Dictation — type what you hear", "🗣️ Shadowing — repeat what you hear"], horizontal=True, key="listen_mode")
        l1, l2 = st.columns(2)
        listen_source = l1.radio("Sentence source", LISTENING_SOURCES, key="listen_source")
        listen_topic = l2.selectbox("Topic (for new sentences)", LISTENING_TOPICS, key="listen_topic", disabled=listen_source != LISTENING_SOURCES[0])
        if st.button("🎲 New sentence", type="primary", key="listen_new"):
            with st.spinner("Preparing audio..."):
                sentence = next_listening_sentence(listen_source, listen_topic, data)
            if sentence:
                st.session_state["listen_item"] = {"id": datetime.now().strftime("%H%M%S%f"), "sentence": sentence, "result": None, "processed_audio_id": None}
            else:
                st.warning("No sentences available from that source yet — try another source.")

        item = st.session_state.get("listen_item")
        if item:
            speed = st.radio("Speed", ["Normal", "🐢 Slow"], horizontal=True, key="listen_speed")
            try:
                st.audio(get_pronunciation_audio(item["sentence"], "com.au", slow=speed != "Normal"), format="audio/mp3")
            except Exception:
                st.caption("⚠️ Audio unavailable")

            result = None
            if listen_mode.startswith("✍️"):
                with st.form(f"dictation_form_{item['id']}"):
                    typed = st.text_input("Type exactly what you hear")
                    if st.form_submit_button("✅ Check") and typed.strip():
                        result = {"mode": "dictation", "attempt": typed, **word_diff(item["sentence"], typed)}
            else:
                st.caption("Listen, then record yourself repeating it straight away — copy the rhythm and stress, not just the words.")
                shadow = st.audio_input("Record yourself repeating it", key=f"shadow_audio_{item['id']}")
                if shadow:
                    audio_id = hash(shadow.getvalue())
                    if item.get("processed_audio_id") != audio_id:
                        item["processed_audio_id"] = audio_id
                        with st.spinner("Listening..."):
                            heard = transcribe_audio(shadow.getvalue(), "shadow.wav", keep_fillers=True)
                        result = {"mode": "shadowing", "attempt": heard["text"], **word_diff(item["sentence"], heard["text"]),
                                  "fluency": fluency_stats(heard), "clarity": clarity_score(heard)}
            if result:
                item["result"] = result
                data["listening_scores"].insert(0, {
                    "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "mode": result["mode"],
                    "score": round(result["score"] * 100), "sentence": item["sentence"]
                })
                record_practice(data, 5)

            if item.get("result"):
                res = item["result"]
                st.metric("Match", f"{round(res['score'] * 100)}%")
                st.caption(f"You {'wrote' if res['mode'] == 'dictation' else 'said'}: “{res['attempt']}”")
                st.markdown(res["markdown"])
                st.caption(DIFF_LEGEND)
                if res.get("fluency"):
                    st.caption(format_fluency(res["fluency"]))
                if res.get("clarity") is not None:
                    st.caption(f"🎯 Recognition clarity: {res['clarity']}%")
            with st.expander("👁️ Reveal the sentence (try first!)"):
                st.markdown(item["sentence"])
                render_message_tools(item["sentence"], "com.au", f"listen_reveal_{item['id']}", explain=True)

    # ---------------- TAB: PRONUNCIATION DRILL ----------------
    with t_pron:
        st.subheader("🎤 Pronunciation Drill (Australian Accent)")
        st.caption("Listen to the native AU pronunciation, record yourself, and get feedback on the words the speech recogniser struggled with.")

        vocab_words = [v["word"] for v in data["vocabulary"]]
        source = st.radio("Practice source", ["From my vocabulary list", "Type my own phrase"], horizontal=True)
        if source == "From my vocabulary list" and vocab_words:
            target_phrase = st.selectbox("Choose a word/phrase", vocab_words)
        else:
            target_phrase = st.text_input("Type a word or phrase to practice", key="pron_custom_phrase")

        if target_phrase:
            render_message_tools(target_phrase, "com.au", f"pron_{vocab_key(target_phrase)}", explain=False)

            attempt = st.audio_input("Record yourself saying it", key="pron_audio_input_widget")
            if attempt:
                audio_bytes = attempt.getvalue()
                audio_id = hash(audio_bytes)
                if st.session_state["pron_processed_audio_id"] != audio_id:
                    st.session_state["pron_processed_audio_id"] = audio_id
                    with st.spinner("Analyzing pronunciation..."):
                        # No Whisper prompt here: priming it with the target phrase would bias it towards
                        # "hearing" the right words and hide the very mistakes we're looking for.
                        heard_t = transcribe_audio(audio_bytes, "attempt.wav")
                        diff = word_diff(target_phrase, heard_t["text"])
                        clarity = clarity_score(heard_t)
                        pron_prompt = f"""
You are a native Australian English pronunciation coach.
Target phrase: "{target_phrase}"
What speech recognition heard: "{heard_t['text']}"
Target words the recogniser did NOT hear as intended: {', '.join(diff['missed']) or 'none'}
Recognition confidence: {f'{clarity}%' if clarity is not None else 'unknown'} (lower means the speech was harder to understand)
Speech-to-text can't hear individual sounds precisely, so base your feedback on the mismatched words and the confidence score; don't invent problems.
In 3-5 short lines:
1. Say how close the attempt was.
2. For each mismatched word (max 3), explain the sound that probably caused it and how to say it in Australian English (vowels, non-rhotic 'r', word stress, linking).
3. If everything matched, give one refinement tip for sounding more natural (stress, rhythm, linking).
"""
                        tips = chat_text(pron_prompt, 0.3)
                    st.session_state["pron_result"] = {"target": target_phrase, "heard": heard_t["text"], "clarity": clarity, "tips": tips, **diff}
                    data["listening_scores"].insert(0, {
                        "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "mode": "pronunciation",
                        "score": round(diff["score"] * 100), "sentence": target_phrase
                    })
                    record_practice(data, 5)

            pron = st.session_state.get("pron_result")
            if pron and pron["target"] == target_phrase:
                p1, p2 = st.columns(2)
                p1.metric("Word match", f"{round(pron['score'] * 100)}%")
                p2.metric("Clarity", f"{pron['clarity']}%" if pron["clarity"] is not None else "—",
                          help="How confident the speech recogniser was. Treat it as a rough signal, not a precise score.")
                st.markdown(f"**Whisper heard:** \"{pron['heard']}\"")
                st.markdown(pron["markdown"])
                st.caption(DIFF_LEGEND)
                st.markdown(pron["tips"])

    # ---------------- TAB: PROGRESS ----------------
    with t_progress:
        st.subheader("📈 My Progress")
        profile = data["profile"]
        word_items = [v for v in data["vocabulary"] if v.get("card_type", "vocab") != "fixit"]
        active_vocab = sum(1 for v in word_items if v.get("used_count", 0) > 0)
        last_30 = (date.today() - timedelta(days=29)).isoformat()
        p1, p2, p3, p4 = st.columns(4)
        p1.metric("🔥 Day streak", daily_streak(profile["practice_days"]))
        p2.metric("✨ Total XP", profile.get("total_xp", 0))
        p3.metric("📅 Days practised (30d)", sum(1 for d in profile["practice_days"] if d >= last_30))
        p4.metric("🎯 Active vocabulary", f"{active_vocab}/{len(word_items)}",
                  help="Saved words/phrases you've actually used yourself in a conversation — the real test of learning a word.")

        st.markdown("#### ✍️ Grammar accuracy")
        this_week, last_week = accuracy_by_week(data["tutor_turn_history"])
        a1, a2 = st.columns(2)
        a1.metric(
            "Last 7 days", f"{this_week}%" if this_week is not None else "—",
            delta=f"{this_week - last_week:+d} pts" if this_week is not None and last_week is not None else None
        )
        a2.metric("Previous 7 days", f"{last_week}%" if last_week is not None else "—")

        st.markdown("#### 🗣️ Speaking fluency (last 20 voice turns)")
        recent_fluency = [t["fluency"] for t in data["tutor_turn_history"] if t.get("fluency")][:20]
        if recent_fluency:
            n = len(recent_fluency)
            f1, f2, f3 = st.columns(3)
            f1.metric("Avg. speed", f"{round(sum(f['wpm'] for f in recent_fluency) / n)} wpm")
            f2.metric("Fillers per turn", f"{sum(f['fillers'] for f in recent_fluency) / n:.1f}")
            f3.metric("Long pauses per turn", f"{sum(f['long_pauses'] for f in recent_fluency) / n:.1f}")
            st.caption("Relaxed native conversation is roughly 120–160 words per minute. Fewer fillers and long pauses = smoother speech.")
        else:
            st.caption("Record some voice turns in Conversation or Real-life Tasks to see fluency stats.")

        st.markdown("#### 🧠 Top mistakes this week")
        week_top = top_error_types(data, days=7)
        if week_top:
            st.markdown("  \n".join(f"**{t}** — {c}×" for t, c in week_top[:3]))
            st.caption("Drill them in the 🧠 My Mistakes tab.")
        else:
            st.caption("No mistakes logged in the last 7 days.")

        if data["task_history"]:
            st.markdown("#### 🎯 Real-life tasks")
            st.dataframe(
                [{"Date": t["id"], "Task": t["task"], "Completed": "✅" if t["completed"] else "⏳", "Goals": t["goals"]} for t in data["task_history"][:10]],
                hide_index=True, width="stretch"
            )
        if data["listening_scores"]:
            st.markdown("#### 🎧 Listening & pronunciation")
            st.dataframe(
                [{"Date": s["id"], "Exercise": s["mode"], "Score": f"{s['score']}%", "Sentence": s["sentence"]} for s in data["listening_scores"][:10]],
                hide_index=True, width="stretch"
            )
        if data["grammar_scores"]:
            st.markdown("#### 📖 Quizzes & drills")
            st.dataframe(
                [{"Date": s.get("id", ""), "Topic": s.get("topic", ""), "Score": s.get("score", "")} for s in data["grammar_scores"][:10]],
                hide_index=True, width="stretch"
            )

# --- SIDEBAR CONFIGURATION ---
def configured_api_key() -> str:
    """Groq key from .streamlit/secrets.toml or the GROQ_API_KEY environment variable, if set."""
    try:
        key = st.secrets.get("GROQ_API_KEY", "")
    except Exception:  # no secrets file
        key = ""
    return key or os.environ.get("GROQ_API_KEY", "")

# Large models first: grammar judgement from small (8B) models is unreliable.
PREFERRED_MODELS = [
    "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905",
    "moonshotai/kimi-k2-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct",
]
FALLBACK_MODELS = ["llama-3.3-70b-versatile", "llama-3.1-8b-instant"]

@st.cache_data(ttl=3600, show_spinner=False)
def _fetch_model_ids(key: str) -> list:
    return [m.id for m in Groq(api_key=key).models.list().data]

def list_text_models(key: str) -> list:
    try:
        ids = _fetch_model_ids(key)
    except Exception:
        ids = []
    text_models = [m for m in ids if not any(tag in m for tag in ("whisper", "orpheus", "vision", "guard", "tts", "playai"))]
    text_models.sort(key=lambda m: (PREFERRED_MODELS.index(m) if m in PREFERRED_MODELS else len(PREFERRED_MODELS), m))
    return text_models or FALLBACK_MODELS

st.sidebar.title("⚙️ Simulation Engine")
saved_key = configured_api_key()
typed_key = st.sidebar.text_input(
    "Groq API Key", type="password",
    help="Tip: put GROQ_API_KEY in .streamlit/secrets.toml (or set it as an environment variable) to skip this step."
)
api_key = typed_key or saved_key
if saved_key and not typed_key:
    st.sidebar.caption("🔐 Using the saved key from secrets/environment.")

if not api_key:
    st.info("Please enter your Groq API Key in the sidebar to launch the app.", icon="🔑")
    st.stop()

client = Groq(api_key=api_key)

text_models = list_text_models(api_key)
MODEL_CHOICE = st.sidebar.selectbox("Conversation model", text_models, help="Plays the conversation partner / role-play counterpart.")
FEEDBACK_MODEL = st.sidebar.selectbox(
    "Feedback model", text_models,
    help="Checks grammar, grades answers and writes lessons. A large model gives much more reliable corrections."
)

st.sidebar.caption("📱 On mobile, tap **☰** (top-left) anytime to reopen these settings.")

# --- LEARNER PROFILE ---
st.sidebar.markdown("---")
st.sidebar.subheader("🧑‍🎓 My Learning")
_profile = data["profile"]
s1, s2 = st.sidebar.columns(2)
s1.metric("🔥 Day streak", daily_streak(_profile["practice_days"]))
s2.metric("✨ Total XP", _profile.get("total_xp", 0))
_level_options = list(LEVEL_GUIDES)
_chosen_level = st.sidebar.selectbox(
    "My English level", _level_options, index=_level_options.index(learner_level()),
    help="Controls how simple the partner's language is and how feedback is explained."
)
_retry_enabled = st.sidebar.toggle("🔁 Say it again after a mistake", value=_profile.get("retry_enabled", True))
_auto_fixit = st.sidebar.toggle("🛠️ Save my mistakes as Fix-it cards", value=_profile.get("auto_fixit", True))
if (_chosen_level, _retry_enabled, _auto_fixit) != (_profile.get("level"), _profile.get("retry_enabled"), _profile.get("auto_fixit")):
    _profile.update({"level": _chosen_level, "retry_enabled": _retry_enabled, "auto_fixit": _auto_fixit})
    save_data(data)

st.sidebar.markdown("---")
APP_MODE = st.sidebar.radio(
    "Practice Mode",
    ["🎭 Leadership & Communication Coach", "🇦🇺 General English Tutor"]
)

if APP_MODE == "🇦🇺 General English Tutor":
    render_english_tutor()
    st.stop()

# --- DYNAMIC DOMAIN & PERSONA CONFIGURATION ---
SCENARIO_DOMAINS = {
    "👥 People Leadership & Team Management": {
        "frameworks": ["1:1 Coaching & Feedback", "Conflict Resolution", "Underperformance Alignment", "Delegation & Empowerment"],
        "personas": [
            "Demotivated Senior Engineer (Resists change & shows burnout)",
            "Ambitious Mid-Level Developer (Pushing hard for immediate promotion)",
            "Conflicting Peer Leaders (Two leads blaming each other for integration failures)",
            "New Direct Report (Lacks confidence, hesitant to make architectural decisions)"
        ],
        "eval_focus": "Empathy, Active Listening, Psychological Safety, Constructive Delivery"
    },
    "👔 Executive & Strategic Communication": {
        "frameworks": ["Executive Status Reporting (BLUF)", "Managing Up & Board Pitches", "Resource Allocation Justification", "Crisis Management"],
        "personas": [
            "Impatient C-Suite Executive (Demands high-level metrics, zero technical fluff)",
            "Budget-Conscious Finance VP (Challenging team headcount & cloud spend)",
            "Skeptical Enterprise Client (Demanding immediate RCA for system outage)"
        ],
        "eval_focus": "BLUF Structure, Executive Presence, Strategic Clarity, Value Framing"
    },
    "💼 Professional Career & High-Stakes Negotiations": {
        "frameworks": ["Salary & Banding Negotiation", "International Job Interview (AU/US/UK Style)", "Setting Boundaries & Saying No"],
        "personas": [
            "Hiring Director (Testing executive presence, behavioral responses, & cultural fit)",
            "Engineering Director (Pushing back on salary expectations during promotion review)",
            "Over-promising Product VP (Attempting to dump unscopeable requests into your team's backlog)"
        ],
        "eval_focus": "Assertiveness, Professional Boundaries, Value Quantification, Negotiation Nuance"
    },
    "🛠️ Technical & Delivery Leadership": {
        "frameworks": ["PMBOK Project Management", "BABOK Business Analysis", "Architecture Trade-off Negotiation", "Agile Retrospectives"],
        "personas": [
            "Skeptical Tech Lead (Pushes back on technical debt vs feature trade-offs)",
            "Demanding Product Owner (Defends scope creep & aggressive sprint deadlines)",
            "Offshore Delivery Partner (Misaligned on quality standards & handover protocols)"
        ],
        "eval_focus": "Trade-off Logic, Technical Directness, Risk Identification, Delivery Precision"
    },
    "🗣️ Daily Workplace & Social Rapport": {
        "frameworks": ["Executive Small Talk & Networking", "Cross-Functional Alignment", "Casual Stakeholder Updates"],
        "personas": [
            "Senior Stakeholder at a Social Mixer (Casual conversation, building professional rapport)",
            "Cross-Functional Marketing Lead (Needs technical concepts explained in plain English)",
            "Peer Lead in 1:1 Coffee Chat (Building trust across department silos)"
        ],
        "eval_focus": "Conversational Naturalness, Idiomatic Fluency, Tone Adaptability, Rapport Building"
    }
}

selected_domain = st.sidebar.selectbox("Communication Domain", list(SCENARIO_DOMAINS.keys()))
domain_info = SCENARIO_DOMAINS[selected_domain]
selected_framework = st.sidebar.selectbox("Scenario Focus", domain_info["frameworks"])
selected_persona = st.sidebar.selectbox("Counterpart Persona", domain_info["personas"])

# Session State Initializations
if "chat_history" not in st.session_state:
    st.session_state["chat_history"] = []
if "evaluations" not in st.session_state:
    st.session_state["evaluations"] = []
if "roleplay_active" not in st.session_state:
    st.session_state["roleplay_active"] = False
if "retro_summary" not in st.session_state:
    st.session_state["retro_summary"] = None
if "processed_audio_id" not in st.session_state:
    st.session_state["processed_audio_id"] = None
if "target_vocab" not in st.session_state:
    st.session_state["target_vocab"] = []
if "target_framework" not in st.session_state:
    st.session_state["target_framework"] = {}
if "reactions" not in st.session_state:
    st.session_state["reactions"] = []
st.session_state.setdefault("ld_retry", None)
for _suffix in ("xp", "streak", "best_streak", "turns"):
    st.session_state.setdefault(f"ld_{_suffix}", 0)

SYSTEM_ROLEPLAY_PROMPT = f"""
You are participating in an interactive, turn-based real-world simulation.
Domain: {selected_domain}
Scenario Focus: {selected_framework}
Your Role: {selected_persona}

Rules:
- Stay strictly IN CHARACTER at all times.
- Keep responses concise (2-4 sentences max) to mirror natural executive conversation.
- Push back, ask sharp questions, or display realistic emotional nuances based on what the user says.
- Adapt tone dynamically: professional, direct, hesitant, or demanding depending on your persona.
- Begin every reply with exactly one emoji in square brackets showing your current in-character reaction (e.g. [😠], [🙂], [😕], [😲]), then a space, then your message.
"""

def evaluate_leadership_turn(user_transcript: str, prev_line: str, fw_info: dict) -> dict:
    """Structured per-turn coaching for the simulator: framework, language errors (fed to the notebook), vocab and tone."""
    prompt = f"""
You are coaching a {learner_level()} English speaker in a workplace communication simulation.
Domain: {selected_domain}
Target Framework: {fw_info.get('title', selected_framework)}
Framework Steps: {fw_info.get('steps', [])}
Target Vocab Checklist: {[v.get('phrase') for v in st.session_state['target_vocab']]}
Counterpart's previous line: "{prev_line}"
User's spoken turn (from speech-to-text; ignore punctuation, capitalisation and filler words): "{user_transcript}"

Return ONLY a JSON object with keys:
"framework_feedback": "1-2 sentences: did the user apply the framework steps?",
"errors": a list (empty if none) of genuine grammar/word-choice/structure errors: {{"original": "the shortest phrase or clause containing the error, copied exactly", "corrected": "that phrase corrected", "error_type": one of {json.dumps(ERROR_TYPES)}, "explanation": "one short reason"}},
"corrected_sentence": "the whole turn rewritten correctly and naturally, or null if there are no errors",
"target_vocab_used": ["target expressions the user actually used"],
"vocab_upgrade": {{"phrase": "one native/executive word or phrasal verb that would make the turn sound more natural", "meaning": "short meaning", "example": "example sentence in this scenario"}},
"tone_feedback": "1-2 sentences evaluated against: {domain_info['eval_focus']}"
"""
    try:
        parsed = chat_json(prompt, 0.2)
    except Exception as e:
        return {"raw": f"⚠️ Couldn't analyse this turn ({e}).", "grammar_accurate": None, "errors": [], "corrected_sentence": None}
    errors = normalize_errors(parsed.get("errors"))
    upgrade = parsed.get("vocab_upgrade")
    return {
        "framework_feedback": str(parsed.get("framework_feedback") or ""),
        "errors": errors,
        "grammar_accurate": not errors,
        "grammar_feedback": format_grammar_feedback(errors),
        "corrected_sentence": (parsed.get("corrected_sentence") or None) if errors else None,
        "target_vocab_used": [str(v) for v in parsed.get("target_vocab_used") or [] if v],
        "vocab_upgrade": upgrade if isinstance(upgrade, dict) and upgrade.get("phrase") else None,
        "tone_feedback": str(parsed.get("tone_feedback") or ""),
    }

def render_leadership_feedback(ev: dict, data: dict):
    if "feedback" in ev:  # evaluation made before structured coaching (same browser session)
        st.markdown(ev["feedback"])
        return
    if ev.get("raw"):
        st.markdown(ev["raw"])
        return
    st.markdown(f"**🎯 Framework Adherence:** {ev.get('framework_feedback', '')}")
    st.markdown("**✍️ Grammar & Precision:**")
    st.markdown(ev.get("grammar_feedback", ""))
    if ev.get("corrected_sentence"):
        st.caption(f"✅ Whole turn, corrected: _{ev['corrected_sentence']}_")
    if ev.get("retry_passed"):
        st.caption("🔁 You said it again correctly — nice work!")
    st.markdown("**📚 Vocabulary:**")
    if ev.get("target_vocab_used"):
        st.caption(f"Target expressions used: {', '.join(ev['target_vocab_used'])}")
    if ev.get("used_words"):
        st.caption(f"🎯 Saved vocabulary used: {', '.join(ev['used_words'])}")
    upgrade = ev.get("vocab_upgrade")
    if upgrade:
        render_vocab_suggestion("Upgrade:", upgrade, f"ld_add_upgrade_{ev['turn']}", data)
    st.markdown(f"**🛠️ Tone & Executive Presence:** {ev.get('tone_feedback', '')}")
    if ev.get("fluency"):
        st.caption(format_fluency(ev["fluency"]))

st.sidebar.markdown("---")
# Control Buttons
if st.sidebar.button("🎬 Start New Simulation", type="primary", width="stretch"):
    st.session_state["chat_history"] = [{"role": "system", "content": SYSTEM_ROLEPLAY_PROMPT}]
    st.session_state["evaluations"] = []
    st.session_state["retro_summary"] = None
    st.session_state["processed_audio_id"] = None
    st.session_state["target_vocab"] = []
    st.session_state["target_framework"] = {}
    st.session_state["reactions"] = []
    st.session_state["roleplay_active"] = True
    st.session_state["ld_retry"] = None
    for _suffix in ("xp", "streak", "best_streak", "turns"):
        st.session_state[f"ld_{_suffix}"] = 0

    with st.spinner("Generating custom scenario, dynamic framework, & target vocabulary..."):
        # 1. Generate Opening Statement
        opening_reply, opening_emoji = call_reaction_reply(
            st.session_state["chat_history"] + [
                {"role": "user", "content": "Start the scenario by making your initial statement or opening question."}
            ],
            MODEL_CHOICE, 0.7, "🎭"
        )
        st.session_state["chat_history"].append({"role": "assistant", "content": opening_reply})
        st.session_state["reactions"].append(opening_emoji)

        # 2. Dynamically Generate Framework Guide AND Target Vocab
        setup_prompt = f"""
        Generate tailored tactical coaching material for a simulation:
        - Domain: {selected_domain}
        - Focus: {selected_framework}
        - Counterpart: {selected_persona}

        Return ONLY a JSON object with these two exact keys:
        1. "framework_guide": {{
            "title": "Name of best-fit framework/technique",
            "overview": "Brief summary of why this framework applies.",
            "steps": ["Step 1 concise action", "Step 2 concise action", "Step 3 concise action"],
            "model_phrase": "An example sentence demonstrating this technique in this exact scenario."
        }}
        2. "target_vocab": [
            {{"phrase": "phrasal verb / expression", "meaning": "definition", "example": "sample sentence in scenario context"}},
            {{"phrase": "...", "meaning": "...", "example": "..."}},
            {{"phrase": "...", "meaning": "...", "example": "..."}},
            {{"phrase": "...", "meaning": "...", "example": "..."}}
        ]
        """
        try:
            parsed_setup = chat_json(setup_prompt, 0.3)
            st.session_state["target_framework"] = parsed_setup.get("framework_guide", {})
            st.session_state["target_vocab"] = parsed_setup.get("target_vocab", [])[:4]
        except Exception:
            st.session_state["target_framework"] = {
                "title": f"{selected_framework} Guidance",
                "overview": "Focus on clear, direct, and structured executive communication.",
                "steps": [
                    "Lead with the core outcome or recommendation (BLUF)",
                    "Acknowledge the counterpart's perspective and state trade-offs",
                    "Propose concrete next steps or a decision boundary"
                ],
                "model_phrase": "The bottom line is that we need to align on deliverables before extending scope."
            }
            st.session_state["target_vocab"] = [
                {"phrase": "push back on", "meaning": "Firmly oppose or negotiate a constraint.", "example": "I need to push back on the deadline."},
                {"phrase": "walk through", "meaning": "Explain step-by-step.", "example": "Let me walk you through the proposal."},
                {"phrase": "iron out", "meaning": "Resolve details or conflicts.", "example": "We should iron out integration risks early."},
                {"phrase": "touch base", "meaning": "Briefly connect for an update.", "example": "Let's touch base tomorrow morning."}
            ]

    st.rerun()

if st.session_state.get("roleplay_active"):
    if st.sidebar.button("🛑 End & Generate Retrospective", type="secondary", width="stretch"):
        st.session_state["roleplay_active"] = False
        st.session_state["ld_retry"] = None

        full_transcript = []
        for msg in st.session_state["chat_history"]:
            if msg["role"] != "system":
                speaker = "Counterpart" if msg["role"] == "assistant" else "User"
                full_transcript.append(f"{speaker}: {msg['content']}")

        conversation_text = "\n".join(full_transcript)
        fw_title = st.session_state.get("target_framework", {}).get("title", selected_framework)

        RETRO_PROMPT = f"""
        You are an expert executive leadership and native English communication coach. Review this simulation transcript:
        Domain: {selected_domain}
        Focus: {selected_framework}
        Framework Evaluated: {fw_title}
        Persona: {selected_persona}
        Evaluation Criteria Target: {domain_info['eval_focus']}

        Transcript:
        {conversation_text}

        Generate a Session Retrospective Summary with these sections:
        1. 📊 **Executive Summary (BLUF):** Overall score (1-10) and core takeaway.
        2. 💪 **Key Strengths:** 2 specific moments where the user handled tone, pushback, or strategy well.
        3. 🎯 **Framework Adherence ({fw_title}):** How effectively the user applied the recommended framework steps.
        4. ✍️ **Grammar & Linguistic Precision Audit:**
           - Identify recurring grammatical mistakes, improper preposition/tense usages, or awkward syntax across the session.
           - Provide explicit **Original vs. Corrected Native Version** comparisons.
        5. 💬 **Vocabulary Range & Native Phrasing Audit:**
           - Evaluate vocabulary sophistication, precision, and usage of target expressions.
           - Offer 3 high-impact native phrases/idioms/phrasal verbs that could replace plain phrasing used during the session.
        6. 🚀 **Actionable Focus Areas:** Top 2 high-leverage focus points for future interactions.
        """

        with st.spinner("Generating Retrospective..."):
            try:
                retro_response = client.chat.completions.create(
                    model=FEEDBACK_MODEL,
                    messages=[{"role": "user", "content": RETRO_PROMPT}],
                    temperature=0.3
                )
                st.session_state["retro_summary"] = retro_response.choices[0].message.content
            except Exception as e:
                st.error(f"Error generating retrospective: {e}")
        st.rerun()

# --- MAIN TAB NAVIGATION ---
# Short labels keep the tab bar usable (scrollable, not cramped) on narrow mobile screens
tab1, tab2, tab3, tab4 = st.tabs([
    "🎭 Simulation",
    "📜 Logs",
    "📚 Vocabulary",
    "💡 Library"
])

# ==========================================
# TAB 1: ROLEPLAY SIMULATION
# ==========================================
with tab1:
    st.title("🎭 Leadership & Communication Simulator")

    if not st.session_state["roleplay_active"]:
        st.info("Select your domain and scenario in the sidebar, then click **🎬 Start New Simulation**.")

        if st.session_state.get("retro_summary"):
            st.markdown("---")
            st.header("📋 Session Retrospective Report")
            st.markdown(st.session_state["retro_summary"])

            if st.button("💾 Save Retrospective to Audit Log"):
                new_entry = {
                    "id": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "domain": selected_domain,
                    "framework": st.session_state.get("target_framework", {}).get("title", selected_framework),
                    "persona": selected_persona,
                    "turns": len(st.session_state["evaluations"]),
                    "retro": st.session_state["retro_summary"],
                    "note": ""
                }
                data["history"].insert(0, new_entry)
                save_data(data)
                st.success("Saved to Audit Log!")
    else:
        col_chat, col_coach = st.columns([3, 2])

        # LEFT COLUMN: CONVERSATION & AUDIO RECORDING
        with col_chat:
            st.subheader(f"💬 Live Interaction ({selected_persona})")
            st.caption(f"**Focus:** {selected_framework}")

            m1, m2, m3 = st.columns(3)
            m1.metric("✨ XP", st.session_state.get("ld_xp", 0))
            m2.metric("🔥 Streak", st.session_state.get("ld_streak", 0))
            m3.metric("🏆 Best", st.session_state.get("ld_best_streak", 0))
            st.progress(min(1.0, st.session_state.get("ld_turns", 0) / 10), text="Session progress toward 10 turns")

            reaction_idx = 0
            for c_idx, msg in enumerate(st.session_state["chat_history"]):
                if msg["role"] == "system":
                    continue
                if msg["role"] == "assistant":
                    avatar = st.session_state["reactions"][reaction_idx] if reaction_idx < len(st.session_state["reactions"]) else "🎭"
                    reaction_idx += 1
                    with st.chat_message("assistant", avatar=avatar):
                        st.write(msg["content"])
                        render_message_tools(msg["content"], "com", f"ld_{c_idx}")
                else:
                    with st.chat_message("user"):
                        st.write(msg["content"])

            st.markdown("---")
            if not render_retry_gate("ld", data, "com", evaluations_key="evaluations"):
                audio_file = st.audio_input("Record your verbal response", key="audio_input_widget")

                if audio_file:
                    audio_bytes = audio_file.getvalue()
                    audio_id = hash(audio_bytes)

                    if st.session_state["processed_audio_id"] != audio_id:
                        st.session_state["processed_audio_id"] = audio_id

                        with st.spinner("Transcribing..."):
                            # 1. Transcribe Audio (with word timestamps for fluency stats)
                            transcription = transcribe_audio(audio_bytes, keep_fillers=True)
                        user_transcript = transcription["text"]

                        if not user_transcript:
                            st.warning("I couldn't hear anything in that recording — please try again.")
                        else:
                            prev_line = next((m["content"] for m in reversed(st.session_state["chat_history"]) if m["role"] == "assistant"), "")
                            st.session_state["chat_history"].append({"role": "user", "content": user_transcript})
                            with st.chat_message("user"):
                                st.write(user_transcript)

                            # 2. AI Counterpart Response (live-streamed with in-character reaction)
                            ai_reply, ai_emoji = stream_reply_with_reaction(st.session_state["chat_history"], MODEL_CHOICE, 0.7, "🎭")
                            st.session_state["chat_history"].append({"role": "assistant", "content": ai_reply})
                            st.session_state["reactions"].append(ai_emoji)

                            # 3. REAL-TIME PER-TURN COACHING EVALUATION
                            with st.spinner("Coaching..."):
                                evaluation = evaluate_leadership_turn(user_transcript, prev_line, st.session_state.get("target_framework", {}))
                            used = register_vocab_usage(data, user_transcript)
                            log_errors(data, evaluation["errors"], "leadership", selected_framework, user_transcript)
                            save_data(data)
                            turn_no = len(st.session_state["evaluations"]) + 1
                            st.session_state["evaluations"].append({
                                "turn": turn_no,
                                "transcript": user_transcript,
                                **evaluation,
                                "fluency": fluency_stats(transcription),
                                "used_words": used,
                            })
                            if used:
                                queue_toast(f"🎯 You used {', '.join(used)} — that's active vocabulary! +{10 * len(used)} XP", "🎉")
                            if data["profile"].get("retry_enabled", True):
                                st.session_state["ld_retry"] = build_retry(evaluation, user_transcript, turn_no)
                            apply_gamification("ld", evaluation["grammar_accurate"], data, bonus_xp=10 * len(used))
                            st.rerun()

        # RIGHT COLUMN: REAL-TIME COACHING & REFERENCE PANELS
        with col_coach:
            # 1. Dynamic Framework & Strategy Card
            fw_data = st.session_state.get("target_framework", {})
            with st.expander("🎯 Tactical Framework & Strategy Guide", expanded=True):
                if fw_data:
                    st.markdown(f"### **{fw_data.get('title', selected_framework)}**")
                    st.caption(fw_data.get("overview", ""))
                    st.markdown("**Recommended Steps to Follow:**")
                    for step in fw_data.get("steps", []):
                        st.markdown(f"- {step}")
                    if fw_data.get("model_phrase"):
                        st.info(f"💡 **Model Opening/Pivot:** \"{fw_data.get('model_phrase')}\"")
                else:
                    st.caption("Framework guidance will load when starting a simulation.")

            # 2. Dynamic Target Vocabulary Panel with TTS Audio Preview
            with st.expander("💡 Scenario Target Vocabulary", expanded=True):
                st.caption("Incorporate these expressions into your turns:")

                accent_choice = st.radio(
                    "Pronunciation Accent:",
                    options=["🇺🇸 US", "🇬🇧 UK", "🇦🇺 AU"],
                    horizontal=True,
                    key="vocab_accent_selector"
                )
                tld_map = {"🇺🇸 US": "com", "🇬🇧 UK": "co.uk", "🇦🇺 AU": "com.au"}
                selected_tld = tld_map[accent_choice]

                st.divider()

                for idx, item in enumerate(st.session_state.get("target_vocab", [])):
                    phrase = item.get("phrase", "")
                    meaning = item.get("meaning", "")
                    example = item.get("example", "")

                    st.markdown(f"**`{phrase}`** — {meaning}")
                    st.caption(f"💬 *\"{example}\"*")

                    col_audio, col_add = st.columns([2, 2])

                    with col_audio:
                        try:
                            audio_bytes = get_pronunciation_audio(phrase, accent=selected_tld)
                            st.audio(audio_bytes, format="audio/mp3")
                        except Exception:
                            st.caption("⚠️ Audio preview unavailable")

                    with col_add:
                        btn_key = f"add_dyn_vocab_{idx}"
                        if st.button(f"➕ Add to Vocab", key=btn_key, width="stretch"):
                            if add_word_to_vocabulary(data, phrase, meaning, example, enrich=False):
                                st.toast(f"Saved '{phrase}'!", icon="✅")
                            else:
                                st.toast(f"'{phrase}' is already in your list.", icon="ℹ️")

                    st.divider()

            # 3. REAL-TIME PER-TURN COACHING NOTES
            st.subheader("📊 Real-Time Coaching Notes")
            if not st.session_state["evaluations"]:
                st.caption(f"Target metrics: **{domain_info['eval_focus']}**.\n\nGrammar, vocabulary, framework, and tone audits will render here after your first spoken response.")
            else:
                for eval_item in reversed(st.session_state["evaluations"]):
                    with st.expander(f"Turn {eval_item['turn']} Audit", expanded=True):
                        st.caption(f"**You said:** \"{eval_item['transcript']}\"")
                        render_leadership_feedback(eval_item, data)

# ==========================================
# TAB 2: AUDIT LOG & RETROSPECTIVES
# ==========================================
with tab2:
    st.title("📜 Practice History & Audit Logs")

    if not data["history"]:
        st.info("No saved retrospectives yet. Complete a session and click 'Save Retrospective' to build history.")
    else:
        for idx, entry in enumerate(data["history"]):
            domain_label = entry.get('domain', 'General')
            framework_label = entry.get('framework', '')
            with st.expander(f"🗓️ {entry['id']} | {domain_label} - {framework_label}"):
                st.markdown(f"**Persona:** {entry.get('persona', 'N/A')}")
                st.markdown(entry.get("retro", "No retrospective text."))

                existing_note = entry.get("note", "")
                new_note = st.text_area("Personal Learning Note", value=existing_note, key=f"hist_note_{idx}")
                if st.button("Save Note", key=f"hist_btn_{idx}"):
                    data["history"][idx]["note"] = new_note
                    save_data(data)
                    st.success("Note saved!")

# ==========================================
# TAB 3: VOCABULARY & FLASHCARDS
# ==========================================
with tab3:
    st.title("📚 Leadership & Communication Vocabulary")
    render_flashcard_review(data, "ld")
    st.markdown("---")
    render_vocabulary_manager(data, "ld")

# ==========================================
# TAB 4: REFERENCE LIBRARY
# ==========================================
with tab4:
    st.title("💡 Core Leadership Communication Reference Library")

    t_lead, t_exec, t_social = st.tabs(["👥 People Leadership", "👔 Executive Presence", "🗣️ Everyday Workplace"])

    with t_lead:
        st.markdown("### The SBI Feedback Framework (Situation-Behavior-Impact)")
        st.markdown("""
        * **Situation:** Define the exact context (*"During yesterday's architecture review..."*).
        * **Behavior:** Describe observable actions without judgment (*"...you interrupted the junior engineer three times..."*).
        * **Impact:** State the result (*"...which caused the team to stop raising critical security questions."*).
        """)

    with t_exec:
        st.markdown("### Executive Communication (BLUF & Minto Pyramid)")
        st.markdown("""
        * **BLUF:** Answer first, context second. Lead with decision outcomes, dates, or financial impact.
        * **Rule of 3:** Group risks or trade-offs into maximum 3 structured buckets.
        """)

    with t_social:
        st.markdown("### Professional Small Talk & Rapport")
        st.markdown("""
        * **ARE Method:** Anchor, Reveal, Encourage. Comment on the setting, share a brief personal note, ask open questions.
        """)
