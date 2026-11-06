"""Smoke test: runs the real app headless (Streamlit AppTest) on a scratch data file and drives the ⚡ Reflex section."""
import json
import os

import pytest
from streamlit.testing.v1 import AppTest

APP = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app.py")


@pytest.fixture
def app(tmp_path, monkeypatch):
    data_file = tmp_path / "practice_data.json"
    items = [
        {"id": f"r{i}", "word": "keen" if i == 0 else "", "question": f"Question {i}?", "answer": f"I'm keen, answer {i}.",
         "source": "vocab" if i == 0 else "ai", "created": "2026-01-01",
         "interval": 0, "ease": 2.5, "repetitions": 0, "due": "2026-01-01"}
        for i in range(12)
    ]
    data_file.write_text(json.dumps({
        "vocabulary": [], "reflex_items": items,
        "profile": {"app_mode": "🇦🇺 General English Tutor", "legacy_migrated": True},
    }), encoding="utf-8")
    monkeypatch.setenv("ENGLISH_APP_DATA_FILE", str(data_file))
    monkeypatch.setenv("GROQ_API_KEY", "test-key")
    at = AppTest.from_file(APP, default_timeout=120)
    at.session_state["tutor_nav"] = "⚡ Reflex"
    return at, data_file


def test_reflex_section_starts_a_session(app):
    at, data_file = app
    at.run()
    assert not at.exception
    assert any("Reflex Drill" in s.value for s in at.subheader)
    assert any("12** question(s) due" in c.value for c in at.caption)

    next(b for b in at.button if b.label == "⚡ Start session").click().run()
    assert not at.exception
    queue = at.session_state["reflex_queue"]
    assert len(queue) == 10  # capped at the session size; nothing generated because enough were due
    assert any("Question 1 of 10" in m.value for m in at.markdown)
    # Before the component reports the reveal, no rating buttons are shown.
    assert not [b for b in at.button if b.label in ("Again", "Good")]

    saved = json.loads(data_file.read_text(encoding="utf-8"))
    assert len(saved["reflex_items"]) == 12


def test_reveal_then_rate_reschedules_the_card(app):
    at, data_file = app
    at.run()
    next(b for b in at.button if b.label == "⚡ Start session").click().run()
    first_id = at.session_state["reflex_queue"][0]["id"]
    card_id = f"{at.session_state['reflex_session']}-0-{first_id}"

    # Stand-in for the component reporting that the answer was revealed (AppTest can't click inside its iframe).
    revealed = {"card_id": card_id, "event": "revealed"}
    at.session_state["reflex_player"] = revealed
    at.run()
    assert not at.exception
    good = next(b for b in at.button if b.label == "Good")
    # AppTest doesn't carry a custom component's value into the next run (a real browser does), so re-send it.
    at.session_state["reflex_player"] = revealed
    good.click().run()
    assert not at.exception

    assert at.session_state["reflex_pos"] == 1
    saved = json.loads(data_file.read_text(encoding="utf-8"))
    card = next(i for i in saved["reflex_items"] if i["id"] == first_id)
    assert card["repetitions"] == 1 and card["due"] > "2026-01-01" and card["last_practised"]
    assert saved["profile"]["total_xp"] == 3


def test_hands_free_next_skips_without_rating(app):
    at, data_file = app
    at.run()
    next(b for b in at.button if b.label == "⚡ Start session").click().run()
    first_id = at.session_state["reflex_queue"][0]["id"]
    at.session_state["reflex_player"] = {"card_id": f"{at.session_state['reflex_session']}-0-{first_id}", "event": "next"}
    at.run()
    assert not at.exception
    assert at.session_state["reflex_pos"] == 1
    card = next(i for i in json.loads(data_file.read_text(encoding="utf-8"))["reflex_items"] if i["id"] == first_id)
    assert card["repetitions"] == 0  # still due next session
