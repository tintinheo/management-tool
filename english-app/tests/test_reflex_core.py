import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from reflex_core import clean_reflex_items, pick_reflex_queue, suggest_reflex_rating, words_without_reflex  # noqa: E402


def uses(word, text):
    return word.lower() in text.lower()


def test_clean_keeps_valid_items_and_extras():
    parsed = {
        "items": [{"word": "Push back", "question": "Can we move the meeting?", "answer": "Sure, let's push back the meeting to Friday."}],
        "extra": [{"question": "How was your weekend?", "answer": "Pretty relaxing, thanks."}],
    }
    out = clean_reflex_items(parsed, ["push back"], [], uses)
    assert [(i["word"], i["source"]) for i in out] == [("push back", "vocab"), ("", "ai")]
    assert all(i["id"] and i["created"] for i in out)


def test_clean_drops_bad_items():
    long_answer = " ".join(["word"] * 40)
    parsed = {
        "items": [
            {"word": "unknown", "question": "Q1?", "answer": "unknown here"},           # not requested
            {"word": "keen", "question": "Q2?", "answer": "I'd love to."},              # answer misses the word
            {"word": "keen", "question": "Q3?", "answer": long_answer + " keen"},       # too long
            {"word": "keen", "question": "Seen before?", "answer": "I'm keen."},        # duplicate question
            {"word": "keen", "question": "Up for a hike?", "answer": "Yeah, I'm keen!"},
            {"word": "keen", "question": "Another?", "answer": "Keen as."},            # one item per word
            "not a dict",
        ],
        "extra": [{"question": "", "answer": "x"}, {"question": "Coffee?", "answer": ""}],
    }
    out = clean_reflex_items(parsed, ["keen"], ["Seen before"], uses)
    assert [i["question"] for i in out] == ["Up for a hike?"]


def test_clean_handles_non_dict_reply():
    assert clean_reflex_items(["nope"], ["a"], [], uses) == []
    assert clean_reflex_items({}, ["a"], [], uses) == []


def test_words_without_reflex_skips_covered_and_fixit():
    vocab = [
        {"word": "keen", "due": "2026-01-02"},
        {"word": "arvo", "due": "2026-01-01"},
        {"word": "I goes", "card_type": "fixit"},
        {"word": "Push back", "due": "2026-01-03"},
    ]
    items = [{"word": "push back"}]
    assert [v["word"] for v in words_without_reflex(vocab, items)] == ["arvo", "keen"]


def test_pick_queue_due_only_new_first_and_limited():
    items = [
        {"id": "future", "due": "2026-12-01", "repetitions": 0},
        {"id": "old", "due": "2026-01-01", "repetitions": 3},
        {"id": "new", "due": "2026-11-06", "repetitions": 0},
        {"id": "nodue", "due": "", "repetitions": 0},
    ]
    picked = pick_reflex_queue(items, "2026-11-06", 2, rng=random.Random(0))
    assert sorted(picked) == ["new", "nodue"]
    assert sorted(pick_reflex_queue(items, "2026-11-06", 10)) == ["new", "nodue", "old"]


def test_suggest_rating():
    assert suggest_reflex_rating("", True, 90) is None
    assert suggest_reflex_rating("hi", False, 95) == 0
    assert suggest_reflex_rating("hi", True, 90) == 2
    assert suggest_reflex_rating("hi", True, 50) == 1
    assert suggest_reflex_rating("hi", None, None) == 2
    assert suggest_reflex_rating("hi", None, 40) == 1
