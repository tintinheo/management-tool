import json
import os
from datetime import datetime
import streamlit as st
from groq import Groq
import io
from gtts import gTTS

@st.cache_data(show_spinner=False)
def get_pronunciation_audio(text: str, accent: str = "com") -> bytes:
    """
    Generates MP3 audio bytes for a given text.
    accent options: 'com' (US), 'co.uk' (UK), 'com.au' (AU)
    """
    fp = io.BytesIO()
    tts = gTTS(text=text, lang="en", tld=accent, slow=False)
    tts.write_to_fp(fp)
    return fp.getvalue()

# Page Configuration
st.set_page_config(page_title="Dynamic Leadership & Communication Coach", page_icon="🎭", layout="wide")

# Local Storage Persistence Setup
DATA_FILE = "practice_data.json"

def load_data():
    if not os.path.exists(DATA_FILE):
        return {"history": [], "vocabulary": []}
    try:
        with open(DATA_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"history": [], "vocabulary": []}

def save_data(data):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=4, ensure_ascii=False)

data = load_data()

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
    st.session_state["roleplay_active"] = True
    
    with st.spinner("Generating custom scenario, dynamic framework, & target vocabulary..."):
        # 1. Generate Opening Statement
        opening = client.chat.completions.create(
            model=MODEL_CHOICE,
            messages=st.session_state["chat_history"] + [
                {"role": "user", "content": "Start the scenario by making your initial statement or opening question."}
            ],
            temperature=0.7
        )
        st.session_state["chat_history"].append({"role": "assistant", "content": opening.choices[0].message.content})
        
        # 2. Dynamically Generate Framework Guide AND Target Vocab specifically for this scenario
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
            # Fallback default framework and vocabulary if parsing encounters an issue
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
        
        # --- ENHANCED RETROSPECTIVE PROMPT WITH GRAMMAR & VOCAB AUDIT ---
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
tab1, tab2, tab3, tab4 = st.tabs([
    "🎭 Active Simulation", 
    "📜 Retrospectives & Logs", 
    "📚 Vocabulary Builder",
    "💡 Reference Library"
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
        
        with col_chat:
            st.subheader(f"💬 Live Interaction ({selected_persona})")
            st.caption(f"**Focus:** {selected_framework}")
            
            for msg in st.session_state["chat_history"]:
                if msg["role"] != "system":
                    with st.chat_message(msg["role"]):
                        st.write(msg["content"])

            st.markdown("---")
            audio_file = st.audio_input("Record your verbal response", key="audio_input_widget")
            
            if audio_file:
                audio_bytes = audio_file.getvalue()
                audio_id = hash(audio_bytes)
                
                if st.session_state["processed_audio_id"] != audio_id:
                    st.session_state["processed_audio_id"] = audio_id
                    
                    with st.spinner("Processing turn with Whisper & Groq..."):
                        # 1. Transcribe Audio
                        user_transcript = client.audio.transcriptions.create(
                            file=("turn.wav", audio_bytes),
                            model="whisper-large-v3-turbo",
                            response_format="text"
                        ).strip()
                        
                        st.session_state["chat_history"].append({"role": "user", "content": user_transcript})
                        
                        # 2. AI Counterpart Response
                        ai_response = client.chat.completions.create(
                            model=MODEL_CHOICE,
                            messages=st.session_state["chat_history"],
                            temperature=0.7
                        )
                        ai_reply = ai_response.choices[0].message.content
                        st.session_state["chat_history"].append({"role": "assistant", "content": ai_reply})
                        
                        # --- ENHANCED REAL-TIME TURN COACHING PROMPT ---
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
                        eval_response = client.chat.completions.create(
                            model=MODEL_CHOICE,
                            messages=[{"role": "user", "content": eval_prompt}],
                            temperature=0.2
                        )
                        st.session_state["evaluations"].append({
                            "turn": len(st.session_state["evaluations"]) + 1,
                            "transcript": user_transcript,
                            "feedback": eval_response.choices[0].message.content
                        })
                        st.rerun()

        with col_coach:
            # --- DYNAMIC FRAMEWORK & STRATEGY CARD ---
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

            # --- DYNAMIC TARGET VOCABULARY PANEL ---
            # --- DYNAMIC TARGET VOCABULARY PANEL WITH TTS PREVIEW ---
with st.expander("💡 Scenario Target Vocabulary", expanded=True):
    st.caption("Incorporate these expressions into your turns:")
    
    # Accent selector for pronunciation style
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
            # Generate and render audio player button
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