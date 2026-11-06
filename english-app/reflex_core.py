"""Pure logic for the ⚡ Reflex drill (no Streamlit import), so it can be unit-tested without running the app.

A reflex item is a short conversational question plus a model spoken answer. Items made for a saved word use
that word in the answer; "ai" items are extra everyday questions not tied to a saved word."""
import random
import uuid
from datetime import date

MAX_QUESTION_WORDS = 25
MAX_ANSWER_WORDS = 35
GOOD_CLARITY = 70


def _norm(text) -> str:
    return " ".join(str(text or "").split())


def _qkey(question: str) -> str:
    return _norm(question).lower().rstrip("?!. ")


def new_reflex_item(question: str, answer: str, word: str = "", source: str = "vocab", today: date = None) -> dict:
    return {
        "id": uuid.uuid4().hex[:12], "word": word, "question": question, "answer": answer, "source": source,
        "created": (today or date.today()).isoformat(),
    }


def clean_reflex_items(parsed, requested_words: list, existing_questions: list, uses_word) -> list:
    """Validates an LLM reply shaped like {"items": [{"word", "question", "answer"}], "extra": [{"question", "answer"}]}.

    requested_words: saved words that were asked for (original spelling). uses_word(word, text) -> bool says whether
    a text uses the word. Keeps at most one item per word, drops anything too long, duplicated or whose answer
    doesn't actually use its word. Returns new item dicts without SRS fields."""
    if not isinstance(parsed, dict):
        return []
    by_key = {_norm(w).lower(): w for w in requested_words if _norm(w)}
    seen = {_qkey(q) for q in existing_questions}
    covered, out = set(), []

    def usable(question: str, answer: str) -> bool:
        if not question or not answer or _qkey(question) in seen:
            return False
        return len(question.split()) <= MAX_QUESTION_WORDS and len(answer.split()) <= MAX_ANSWER_WORDS

    for raw in parsed.get("items") or []:
        if not isinstance(raw, dict):
            continue
        word = by_key.get(_norm(raw.get("word")).lower())
        question, answer = _norm(raw.get("question")), _norm(raw.get("answer"))
        if word is None or word in covered or not usable(question, answer) or not uses_word(word, answer):
            continue
        covered.add(word)
        seen.add(_qkey(question))
        out.append(new_reflex_item(question, answer, word=word, source="vocab"))

    for raw in parsed.get("extra") or []:
        if not isinstance(raw, dict):
            continue
        question, answer = _norm(raw.get("question")), _norm(raw.get("answer"))
        if not usable(question, answer):
            continue
        seen.add(_qkey(question))
        out.append(new_reflex_item(question, answer, word="", source="ai"))
    return out


def words_without_reflex(vocabulary: list, reflex_items: list, can_drill=lambda word: True) -> list:
    """Saved vocab entries (not Fix-it cards) that don't have a reflex question yet, soonest-due first."""
    have = {_norm(it.get("word")).lower() for it in reflex_items if it.get("word")}
    missing = [
        v for v in vocabulary
        if v.get("card_type", "vocab") != "fixit" and _norm(v.get("word")).lower() not in have and can_drill(v.get("word", ""))
    ]
    return sorted(missing, key=lambda v: v.get("due", ""))


def pick_reflex_queue(reflex_items: list, today_str: str, limit: int, rng: random.Random = None) -> list:
    """Ids of up to `limit` due items: never-practised and most overdue first, then shuffled so the order isn't predictable."""
    due = [it for it in reflex_items if (it.get("due") or today_str) <= today_str]
    due.sort(key=lambda it: (it.get("repetitions", 0) > 0, it.get("due") or today_str))
    picked = [it["id"] for it in due[:limit]]
    (rng or random).shuffle(picked)
    return picked


def suggest_reflex_rating(heard: str, target_used, clarity) -> int | None:
    """Suggested SRS rating (0=Again, 1=Hard, 2=Good) for a recorded answer, or None if nothing was heard.

    A conversational answer can differ from the model answer and still be right, so this doesn't grade against it.
    It only checks that the target word was used (target_used is None for items without one) and that the speech
    was clear enough for the recogniser."""
    if not _norm(heard):
        return None
    if target_used is False:
        return 0
    clear = clarity is None or clarity >= GOOD_CLARITY
    return 2 if clear else 1
