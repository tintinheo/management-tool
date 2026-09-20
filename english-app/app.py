import io
import json
import os
import re
from datetime import datetime
from gtts import gTTS
import streamlit as st
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

# Local Storage Persistence Setup
DATA_FILE = "practice_data.json"

DEFAULT_DATA = {"history": [], "vocabulary": [], "writing_history": [], "grammar_scores": []}

def load_data():
    if not os.path.exists(DATA_FILE):
        return {**DEFAULT_DATA}
    try:
        with open(DATA_FILE, "r", encoding="utf-8") as f:
            loaded = json.load(f)
    except Exception:
        return {**DEFAULT_DATA}
    for key, default_val in DEFAULT_DATA.items():
        loaded.setdefault(key, default_val)
    return loaded

def save_data(data):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=4, ensure_ascii=False)

data = load_data()

# --- TTS HELPER FUNCTION ---
@st.cache_data(show_spinner=False)
def get_pronunciation_audio(text: str, accent: str = "com") -> bytes:
    """Generates MP3 audio bytes for vocabulary pronunciation."""
    fp = io.BytesIO()
    tts = gTTS(text=text, lang="en", tld=accent, slow=False)
    tts.write_to_fp(fp)
    return fp.getvalue()

# --- SPACED REPETITION (SM-2 LITE) HELPERS ---
def ensure_srs_fields(item: dict) -> bool:
    """Backfills SRS bookkeeping fields on a vocabulary item. Returns True if it was modified."""
    changed = False
    for field, default_val in (("interval", 0), ("ease", 2.5), ("repetitions", 0), ("due", "")):
        if field not in item:
            item[field] = default_val
            changed = True
    if not item["due"]:
        item["due"] = datetime.now().strftime("%Y-%m-%d")
        changed = True
    return changed

def sm2_update(item: dict, quality: int) -> None:
    """Applies a simplified SM-2 spaced-repetition update. quality: 0=Again,1=Hard,2=Good,3=Easy."""
    from datetime import timedelta
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
    item["due"] = (datetime.now() + timedelta(days=item["interval"])).strftime("%Y-%m-%d")

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

# --- GAMIFICATION (XP / STREAKS) HELPER ---
def apply_gamification(prefix: str, feedback_text: str) -> dict:
    """Awards XP/streaks based on whether a turn's grammar was accurate, with toast/balloon reactions."""
    for suffix, default_val in (("xp", 0), ("streak", 0), ("best_streak", 0), ("turns", 0)):
        st.session_state.setdefault(f"{prefix}_{suffix}", default_val)

    quality_good = "grammar was accurate" in feedback_text.lower()
    st.session_state[f"{prefix}_turns"] += 1
    if quality_good:
        st.session_state[f"{prefix}_streak"] += 1
        gained = 15
        st.toast(f"✅ Grammar accurate! +{gained} XP", icon="✨")
    else:
        st.session_state[f"{prefix}_streak"] = 0
        gained = 5
        st.toast(f"📝 Feedback ready · +{gained} XP", icon="💬")
    st.session_state[f"{prefix}_xp"] += gained
    st.session_state[f"{prefix}_best_streak"] = max(st.session_state[f"{prefix}_best_streak"], st.session_state[f"{prefix}_streak"])

    if st.session_state[f"{prefix}_streak"] in (3, 5, 10, 15, 20):
        st.balloons()

    return {
        "xp": st.session_state[f"{prefix}_xp"],
        "streak": st.session_state[f"{prefix}_streak"],
        "best_streak": st.session_state[f"{prefix}_best_streak"],
        "turns": st.session_state[f"{prefix}_turns"],
    }

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
    """General English tutor mode: conversation practice, writing correction, grammar lessons, SRS flashcards, pronunciation drills."""
    for state_key, default_val in (
        ("tutor_chat_history", []), ("tutor_active", False), ("tutor_topic", ""),
        ("tutor_processed_audio_id", None), ("tutor_evaluations", []), ("tutor_reactions", []),
        ("flash_queue", []), ("flash_pos", 0), ("flash_show_answer", False), ("pron_processed_audio_id", None)
    ):
        if state_key not in st.session_state:
            st.session_state[state_key] = default_val

    st.sidebar.markdown("---")
    st.sidebar.caption("🇦🇺 Tutor mode is tuned for natural, native Australian English.")

    st.title("🇦🇺 General English Tutor")
    t_conv, t_write, t_grammar, t_flash, t_pron = st.tabs([
        "🗣️ Conversation", "✍️ Writing Correction", "📖 Grammar Lessons", "📚 Flashcards", "🎤 Pronunciation"
    ])

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
You are a friendly, native Australian English speaking partner helping the user practice everyday spoken/written English.
Topic: {active_topic}
Rules:
- Speak naturally, using authentic Australian expressions and idioms where it fits.
- Keep replies short (2-4 sentences) and ask an engaging follow-up question.
- Stay encouraging and conversational, not formal.
- Begin every reply with exactly one emoji in square brackets showing your current reaction (e.g. [😄], [🙂], [🤔], [😲]), then a space, then your message.
"""
        if st.button("🎬 Start New Conversation", type="primary", use_container_width=True):
            st.session_state["tutor_chat_history"] = [{"role": "system", "content": TUTOR_SYSTEM_PROMPT}]
            st.session_state["tutor_evaluations"] = []
            st.session_state["tutor_reactions"] = []
            st.session_state["tutor_processed_audio_id"] = None
            st.session_state["tutor_active"] = True
            for suffix in ("xp", "streak", "best_streak", "turns"):
                st.session_state[f"tutor_{suffix}"] = 0
            with st.spinner("Starting conversation..."):
                reply, emoji = call_reaction_reply(
                    st.session_state["tutor_chat_history"] + [
                        {"role": "user", "content": "Start the conversation with a friendly opening line or question."}
                    ],
                    MODEL_CHOICE, 0.7, "🇦🇺"
                )
                st.session_state["tutor_chat_history"].append({"role": "assistant", "content": reply})
                st.session_state["tutor_reactions"].append(emoji)
            st.rerun()

        if st.session_state["tutor_active"]:
            col_chat, col_audit = st.columns([3, 2])
            with col_chat:
                xp = st.session_state.get("tutor_xp", 0)
                streak = st.session_state.get("tutor_streak", 0)
                m1, m2, m3 = st.columns(3)
                m1.metric("✨ XP", xp)
                m2.metric("🔥 Streak", streak)
                m3.metric("🏆 Best", st.session_state.get("tutor_best_streak", 0))
                st.progress(min(1.0, st.session_state.get("tutor_turns", 0) / 10), text="Session progress toward 10 turns")

                reaction_idx = 0
                for msg in st.session_state["tutor_chat_history"]:
                    if msg["role"] == "system":
                        continue
                    if msg["role"] == "assistant":
                        avatar = st.session_state["tutor_reactions"][reaction_idx] if reaction_idx < len(st.session_state["tutor_reactions"]) else "🇦🇺"
                        reaction_idx += 1
                        with st.chat_message("assistant", avatar=avatar):
                            st.write(msg["content"])
                    else:
                        with st.chat_message("user"):
                            st.write(msg["content"])

                st.markdown("---")

                def process_tutor_turn(user_text: str):
                    st.session_state["tutor_chat_history"].append({"role": "user", "content": user_text})
                    with st.chat_message("user"):
                        st.write(user_text)

                    reply, emoji = stream_reply_with_reaction(st.session_state["tutor_chat_history"], MODEL_CHOICE, 0.7, "🇦🇺")
                    st.session_state["tutor_chat_history"].append({"role": "assistant", "content": reply})
                    st.session_state["tutor_reactions"].append(emoji)

                    audit_prompt = f"""
Analyze this English learner's turn: "{user_text}"
Give concise coaching in 3 short points:
1. ✍️ **Grammar & Accuracy:** Point out errors with *Original -> Corrected*. If error-free, say "Grammar was accurate."
2. 🇦🇺 **Native AU Upgrade:** Suggest one more natural, native Australian-English way to phrase the same idea (word, idiom, or phrasal verb).
3. 💬 **Fluency Tip:** One short, encouraging tip to sound more natural next time.
"""
                    with st.spinner("Coaching..."):
                        audit_res = client.chat.completions.create(
                            model=MODEL_CHOICE, messages=[{"role": "user", "content": audit_prompt}], temperature=0.2
                        )
                    feedback = audit_res.choices[0].message.content
                    st.session_state["tutor_evaluations"].append({
                        "turn": len(st.session_state["tutor_evaluations"]) + 1,
                        "transcript": user_text,
                        "feedback": feedback
                    })
                    apply_gamification("tutor", feedback)

                audio_file = st.audio_input("Record your response", key="tutor_audio_input_widget")
                if audio_file:
                    audio_bytes = audio_file.getvalue()
                    audio_id = hash(audio_bytes)
                    if st.session_state["tutor_processed_audio_id"] != audio_id:
                        st.session_state["tutor_processed_audio_id"] = audio_id
                        with st.spinner("Transcribing..."):
                            transcript = client.audio.transcriptions.create(
                                file=("turn.wav", audio_bytes), model="whisper-large-v3-turbo", response_format="text"
                            ).strip()
                        process_tutor_turn(transcript)
                        st.rerun()

                text_turn = st.chat_input("...or type your response")
                if text_turn:
                    process_tutor_turn(text_turn)
                    st.rerun()

            with col_audit:
                st.subheader("📊 Turn-by-Turn Coaching")
                if not st.session_state["tutor_evaluations"]:
                    st.caption("Grammar, AU-native phrasing, and fluency feedback will appear here after your first turn.")
                else:
                    for ev in reversed(st.session_state["tutor_evaluations"]):
                        with st.expander(f"Turn {ev['turn']} Audit", expanded=True):
                            st.caption(f"**You said:** \"{ev['transcript']}\"")
                            st.markdown(ev["feedback"])
        else:
            st.info("Pick a topic and click **🎬 Start New Conversation** to begin.")

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
You are a native Australian English writing coach. Review this text:
---
{draft}
---
Respond with these sections:
1. ✅ **Corrected Version:** The full corrected text.
2. 🛠️ **Key Fixes:** Bullet list of *Original -> Corrected* for each notable grammar/spelling/word-choice error, with a one-line reason.
3. 🇦🇺 **Native AU Phrasing Upgrades:** 2-3 suggestions to make the writing sound more natural/native (Australian English).
"""
                    res = client.chat.completions.create(
                        model=MODEL_CHOICE, messages=[{"role": "user", "content": write_prompt}], temperature=0.2
                    )
                    st.session_state["writing_result"] = res.choices[0].message.content
                    st.session_state["writing_last_draft"] = draft
            else:
                st.warning("Please enter some text first.")

        if st.session_state.get("writing_result"):
            st.markdown("---")
            st.markdown(st.session_state["writing_result"])
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
                    st.markdown(entry["result"])

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
Create a short English grammar lesson about: "{custom_grammar_topic}".
Return ONLY a JSON object with keys:
"explanation": "concise markdown explanation",
"examples": ["example 1", "example 2"],
"quiz": [{{"question": "...", "options": ["A", "B", "C"], "answer": 0, "explain": "..."}}, {{"question": "...", "options": ["A", "B", "C"], "answer": 0, "explain": "..."}}]
"""
                    try:
                        res = client.chat.completions.create(
                            model=MODEL_CHOICE, messages=[{"role": "user", "content": gen_prompt}], temperature=0.3,
                            response_format={"type": "json_object"} if "llama-3" in MODEL_CHOICE.lower() else None
                        )
                        content = res.choices[0].message.content.strip().replace("```json", "").replace("```", "").strip()
                        st.session_state["custom_grammar_lesson"] = json.loads(content)
                    except Exception as e:
                        st.error(f"Could not generate lesson: {e}")
            lesson = st.session_state.get("custom_grammar_lesson")
        else:
            lesson = GRAMMAR_TOPICS[chosen_topic]

        if lesson:
            st.markdown(lesson["explanation"])
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
                save_data(data)

    # ---------------- TAB: VOCABULARY FLASHCARDS (SRS) ----------------
    with t_flash:
        st.subheader("📚 Vocabulary Flashcards (Spaced Repetition)")

        with st.form("tutor_add_vocab_form", clear_on_submit=True):
            c1, c2 = st.columns(2)
            with c1:
                new_word = st.text_input("Word / Phrase")
            with c2:
                new_meaning = st.text_input("Meaning")
            new_example = st.text_area("Example Sentence")
            if st.form_submit_button("➕ Add to Vocabulary") and new_word.strip():
                entry = {
                    "word": new_word.strip(), "meaning": new_meaning.strip(), "example": new_example.strip(),
                    "date": datetime.now().strftime("%Y-%m-%d")
                }
                ensure_srs_fields(entry)
                data["vocabulary"].insert(0, entry)
                save_data(data)
                st.success(f"Added '{new_word}'!")
                st.rerun()

        st.markdown("---")
        modified = False
        for item in data["vocabulary"]:
            if ensure_srs_fields(item):
                modified = True
        if modified:
            save_data(data)

        today_str = datetime.now().strftime("%Y-%m-%d")
        due_cards = [it for it in data["vocabulary"] if it.get("due", today_str) <= today_str]

        st.caption(f"**{len(due_cards)}** card(s) due for review out of {len(data['vocabulary'])} total.")

        if st.button("🔁 Start Review Session", disabled=not due_cards):
            st.session_state["flash_queue"] = due_cards.copy()
            st.session_state["flash_pos"] = 0
            st.session_state["flash_show_answer"] = False
            st.rerun()

        queue = st.session_state["flash_queue"]
        pos = st.session_state["flash_pos"]
        if queue and pos < len(queue):
            card = queue[pos]
            st.markdown("---")
            st.markdown(f"### Card {pos + 1} of {len(queue)}")
            st.markdown(f"## **{card['word']}**")
            if not st.session_state["flash_show_answer"]:
                if st.button("👁️ Show Meaning"):
                    st.session_state["flash_show_answer"] = True
                    st.rerun()
            else:
                st.markdown(f"**Meaning:** {card.get('meaning', '')}")
                if card.get("example"):
                    st.caption(f"💬 *\"{card['example']}\"*")
                cols = st.columns(4)
                labels = [("Again", 0), ("Hard", 1), ("Good", 2), ("Easy", 3)]
                for col, (label, quality) in zip(cols, labels):
                    if col.button(label, key=f"flash_{label}_{pos}"):
                        sm2_update(card, quality)
                        save_data(data)
                        st.session_state["flash_pos"] += 1
                        st.session_state["flash_show_answer"] = False
                        st.rerun()
        elif queue:
            st.success("🎉 Review session complete!")

    # ---------------- TAB: PRONUNCIATION DRILL ----------------
    with t_pron:
        st.subheader("🎤 Pronunciation Drill (Australian Accent)")
        st.caption("Listen to the native AU pronunciation, record yourself, and get feedback.")

        vocab_words = [v["word"] for v in data["vocabulary"]]
        source = st.radio("Practice source", ["From my vocabulary list", "Type my own phrase"], horizontal=True)
        if source == "From my vocabulary list" and vocab_words:
            target_phrase = st.selectbox("Choose a word/phrase", vocab_words)
        else:
            target_phrase = st.text_input("Type a word or phrase to practice", key="pron_custom_phrase")

        if target_phrase:
            try:
                st.audio(get_pronunciation_audio(target_phrase, accent="com.au"), format="audio/mp3")
            except Exception:
                st.caption("⚠️ Audio preview unavailable")

            attempt = st.audio_input("Record yourself saying it", key="pron_audio_input_widget")
            if attempt:
                audio_bytes = attempt.getvalue()
                audio_id = hash(audio_bytes)
                if st.session_state["pron_processed_audio_id"] != audio_id:
                    st.session_state["pron_processed_audio_id"] = audio_id
                    with st.spinner("Analyzing pronunciation..."):
                        heard = client.audio.transcriptions.create(
                            file=("attempt.wav", audio_bytes), model="whisper-large-v3-turbo", response_format="text"
                        ).strip()
                        pron_prompt = f"""
Target phrase: "{target_phrase}"
What the speech-to-text engine heard: "{heard}"
As a native Australian English pronunciation coach, briefly (3-4 lines):
1. Say whether the attempt likely matched the target (based on the transcription).
2. Flag specific sounds/words in "{target_phrase}" that Australian English speakers pronounce distinctly (e.g. vowel shifts, non-rhotic 'r', linking) and that the learner should focus on.
3. Give one quick tip to sound more native.
"""
                        pron_res = client.chat.completions.create(
                            model=MODEL_CHOICE, messages=[{"role": "user", "content": pron_prompt}], temperature=0.3
                        )
                        st.markdown(f"**Whisper heard:** \"{heard}\"")
                        st.markdown(pron_res.choices[0].message.content)

# --- SIDEBAR CONFIGURATION ---
st.sidebar.title("⚙️ Simulation Engine")
api_key = st.sidebar.text_input("Groq API Key", type="password")

if not api_key:
    st.info("Please enter your Groq API Key in the sidebar to launch the app.", icon="🔑")
    st.stop()

client = Groq(api_key=api_key)

# Dynamic Model Discovery & Filtering
try:
    all_models = client.models.list().data
    text_models = [
        m.id for m in all_models 
        if "whisper" not in m.id 
        and "orpheus" not in m.id 
        and "vision" not in m.id
        and "guard" not in m.id
    ]
    text_models.sort(key=lambda x: (not ("llama" in x.lower() or "gemma" in x.lower()), x))
    if not text_models:
        text_models = ["llama-3.3-70b-versatile", "llama-3.1-8b-instant"]
except Exception:
    text_models = ["llama-3.3-70b-versatile", "llama-3.1-8b-instant"]

MODEL_CHOICE = st.sidebar.selectbox("Active Groq LLM", text_models)

st.sidebar.caption("📱 On mobile, tap **☰** (top-left) anytime to reopen these settings.")

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

st.sidebar.markdown("---")
# Control Buttons
if st.sidebar.button("🎬 Start New Simulation", type="primary", use_container_width=True):
    st.session_state["chat_history"] = [{"role": "system", "content": SYSTEM_ROLEPLAY_PROMPT}]
    st.session_state["evaluations"] = []
    st.session_state["retro_summary"] = None
    st.session_state["processed_audio_id"] = None
    st.session_state["target_vocab"] = []
    st.session_state["target_framework"] = {}
    st.session_state["reactions"] = []
    st.session_state["roleplay_active"] = True
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
            setup_res = client.chat.completions.create(
                model=MODEL_CHOICE,
                messages=[{"role": "user", "content": setup_prompt}],
                temperature=0.3,
                response_format={"type": "json_object"} if "llama-3" in MODEL_CHOICE.lower() else None
            )
            res_content = setup_res.choices[0].message.content.strip()
            if res_content.startswith("```json"):
                res_content = res_content.replace("```json", "").replace("```", "").strip()
            
            parsed_setup = json.loads(res_content)
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
    if st.sidebar.button("🛑 End & Generate Retrospective", type="secondary", use_container_width=True):
        st.session_state["roleplay_active"] = False
        
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
                    model=MODEL_CHOICE,
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
            for msg in st.session_state["chat_history"]:
                if msg["role"] == "system":
                    continue
                if msg["role"] == "assistant":
                    avatar = st.session_state["reactions"][reaction_idx] if reaction_idx < len(st.session_state["reactions"]) else "🎭"
                    reaction_idx += 1
                    with st.chat_message("assistant", avatar=avatar):
                        st.write(msg["content"])
                else:
                    with st.chat_message("user"):
                        st.write(msg["content"])

            st.markdown("---")
            audio_file = st.audio_input("Record your verbal response", key="audio_input_widget")
            
            if audio_file:
                audio_bytes = audio_file.getvalue()
                audio_id = hash(audio_bytes)
                
                if st.session_state["processed_audio_id"] != audio_id:
                    st.session_state["processed_audio_id"] = audio_id
                    
                    with st.spinner("Transcribing..."):
                        # 1. Transcribe Audio
                        user_transcript = client.audio.transcriptions.create(
                            file=("turn.wav", audio_bytes),
                            model="whisper-large-v3-turbo",
                            response_format="text"
                        ).strip()

                    st.session_state["chat_history"].append({"role": "user", "content": user_transcript})
                    with st.chat_message("user"):
                        st.write(user_transcript)

                    # 2. AI Counterpart Response (live-streamed with in-character reaction)
                    ai_reply, ai_emoji = stream_reply_with_reaction(st.session_state["chat_history"], MODEL_CHOICE, 0.7, "🎭")
                    st.session_state["chat_history"].append({"role": "assistant", "content": ai_reply})
                    st.session_state["reactions"].append(ai_emoji)

                    # 3. REAL-TIME PER-TURN COACHING EVALUATION
                    fw_info = st.session_state.get("target_framework", {})
                    eval_prompt = f"""
                    Analyze this spoken turn from the user:
                    User Transcript: "{user_transcript}"
                    Domain: {selected_domain}
                    Target Framework: {fw_info.get('title', selected_framework)}
                    Framework Steps: {fw_info.get('steps', [])}
                    Target Vocab Checklist: {[v.get('phrase') for v in st.session_state['target_vocab']]}
                    
                    Provide concise coaching structured in these 4 distinct points:
                    1. 🎯 **Framework Adherence:** Did the user apply steps for {fw_info.get('title', 'the framework')}?
                    2. ✍️ **Grammar & Precision Check:** 
                       - Point out any grammatical errors, incorrect prepositions, tense mismatches, or awkward structures.
                       - Provide the **exact corrected sentence** (e.g. *Original:* "..." -> *Corrected:* "..."). If error-free, explicitly state "Grammar was accurate."
                    3. 📚 **Vocabulary Audit & Upgrades:** 
                       - Did they use target expressions? 
                       - Offer 1 native/executive word or phrasal verb upgrade to make the statement sound more natural.
                    4. 🛠️ **Tone & Executive Presence:** Evaluate against standard: ({domain_info['eval_focus']}).
                    """
                    with st.spinner("Coaching..."):
                        eval_response = client.chat.completions.create(
                            model=MODEL_CHOICE,
                            messages=[{"role": "user", "content": eval_prompt}],
                            temperature=0.2
                        )
                    feedback = eval_response.choices[0].message.content
                    st.session_state["evaluations"].append({
                        "turn": len(st.session_state["evaluations"]) + 1,
                        "transcript": user_transcript,
                        "feedback": feedback
                    })
                    apply_gamification("ld", feedback)
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
                        if st.button(f"➕ Add to Vocab", key=btn_key, use_container_width=True):
                            exists = any(v['word'].lower() == phrase.lower() for v in data['vocabulary'])
                            if not exists:
                                data['vocabulary'].insert(0, {
                                    "word": phrase,
                                    "meaning": meaning,
                                    "example": example,
                                    "date": datetime.now().strftime("%Y-%m-%d")
                                })
                                save_data(data)
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
                        st.markdown(eval_item["feedback"])

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
# TAB 3: VOCABULARY BUILDER
# ==========================================
with tab3:
    st.title("📚 Leadership & Communication Vocabulary Builder")
    
    with st.form("add_vocab_form", clear_on_submit=True):
        c1, c2 = st.columns(2)
        with c1:
            word = st.text_input("Phrasal Verb / Idiom (e.g., 'rally the team')")
        with c2:
            meaning = st.text_input("Meaning (e.g., 'inspire and unite people around a goal')")
        example = st.text_area("Example Sentence (e.g., 'We need to rally the team before the deployment window.')")
        
        if st.form_submit_button("➕ Save Expression"):
            if word:
                data["vocabulary"].insert(0, {
                    "word": word.strip(),
                    "meaning": meaning.strip(),
                    "example": example.strip(),
                    "date": datetime.now().strftime("%Y-%m-%d")
                })
                save_data(data)
                st.success(f"Added '{word}'!")
                st.rerun()

    st.markdown("---")
    if not data["vocabulary"]:
        st.info("Your vocabulary log is empty.")
    else:
        for v_idx, item in enumerate(data["vocabulary"]):
            col_v1, col_v2 = st.columns([4, 1])
            with col_v1:
                st.markdown(f"### **{item['word']}**")
                st.markdown(f"**Meaning:** {item['meaning']}")
                if item['example']:
                    st.caption(f"💬 *\"{item['example']}\"*")
            with col_v2:
                if st.button("🗑️ Delete", key=f"del_vocab_{v_idx}"):
                    data["vocabulary"].pop(v_idx)
                    save_data(data)
                    st.rerun()
            st.divider()

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