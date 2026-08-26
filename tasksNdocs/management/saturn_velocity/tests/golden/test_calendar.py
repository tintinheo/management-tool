"""Golden tests — working-calendar service (PM-06)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from datetime import date
from src.domain.pmi_models import Calendar
from src.pmi import calendar_service as cs

# Anchor facts: 2026-07-30 Thu, 07-31 Fri, 08-01 Sat, 08-02 Sun, 08-03 Mon.
MON = date(2026, 8, 3)
TUE = date(2026, 8, 4)
WED = date(2026, 8, 5)
THU = date(2026, 8, 6)
FRI = date(2026, 8, 7)
SAT = date(2026, 8, 1)


def cal() -> Calendar:
    return Calendar(name="MF")


def test_is_working_day():
    assert cs.is_working_day(cal(), MON) is True
    assert cs.is_working_day(cal(), SAT) is False


def test_is_working_day_holiday():
    c = Calendar(holidays=[WED])
    assert cs.is_working_day(c, WED) is False
    assert cs.is_working_day(c, TUE) is True


def test_next_working_day_skips_weekend():
    assert cs.next_working_day(cal(), SAT) == MON
    assert cs.next_working_day(cal(), MON) == MON


def test_working_days_between_inclusive():
    assert cs.working_days_between(cal(), MON, FRI) == 5


def test_working_days_between_reversed_is_zero():
    assert cs.working_days_between(cal(), FRI, MON) == 0


def test_working_days_between_with_holiday():
    c = Calendar(holidays=[WED])
    assert cs.working_days_between(c, MON, FRI) == 4


def test_add_working_days_zero_is_same():
    assert cs.add_working_days(cal(), MON, 0) == MON


def test_add_working_days_within_week():
    assert cs.add_working_days(cal(), MON, 4) == FRI


def test_add_working_days_crosses_weekend():
    assert cs.add_working_days(cal(), FRI, 1) == date(2026, 8, 10)


def test_add_working_days_skips_holiday():
    c = Calendar(holidays=[WED])
    # working days from Mon: 8/3,8/4,8/6,8/7,8/10 -> +4 = 8/10
    assert cs.add_working_days(c, MON, 4) == date(2026, 8, 10)


def test_end_after_duration_one_day():
    assert cs.end_after_duration(cal(), MON, 1) == MON


def test_end_after_duration_five_days():
    assert cs.end_after_duration(cal(), MON, 5) == FRI


def test_end_after_duration_fractional_rounds_up():
    # 4.5 -> 5 working days -> Fri
    assert cs.end_after_duration(cal(), MON, 4.5) == FRI


def test_working_days_list():
    days = cs.working_days_list(cal(), MON, FRI)
    assert days == [MON, TUE, WED, THU, FRI]


def test_week_key_same_week_equal():
    assert cs.week_key(MON) == cs.week_key(FRI)
    assert cs.week_key(MON).startswith("2026-W")


def test_week_key_next_week_differs():
    assert cs.week_key(MON) != cs.week_key(date(2026, 8, 10))


def test_no_working_days_raises():
    broken = Calendar(working_weekdays=[])
    try:
        cs.next_working_day(broken, MON)
        assert False, "expected ValueError"
    except ValueError:
        assert True


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
