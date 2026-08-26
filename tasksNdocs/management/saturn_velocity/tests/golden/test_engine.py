"""
Golden tests for the calculation engine.

Baseline: WB-s192 sprint parameters
  Start:            30 July 2026
  Dev End:          14 August 2026
  Sprint End:       19 August 2026
  Public Holidays:  0
  Buffer:           0.1
  Backup:           1.0
  Fixed deduction:  3.0  (unnamed constant, R-04)

Expected dev_days = NETWORKDAYS(2026-07-30, 2026-08-14, 0) - 3 - 1
  Working days 30 Jul – 14 Aug: 30Jul(Thu), 31Jul(Fri), 3Aug(Mon)…14Aug(Fri) = 12 days
  dev_days = 12 - 3 - 1 = 8

Resource block (two members, one Dev, one QC):
  Alice: Dev, velocity=1.0, leave=0, ot_days=0, v_percent=1.0
  Bob:   QC,  velocity=1.0, leave=1, ot_days=0, v_percent=0.9

NOTE: Business has NOT sign-off on these exact expected values; these tests verify
      the engine's arithmetic against the documented formulas, not against approved golden output.
      Replace expected values with business-approved figures once sign-off is obtained.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from datetime import date
from src.domain.models import Sprint, Scenario, ScenarioResource, ResourceType, RuleSet
from src.calculation.engine import networkdays, calculate


# ── Fixtures ──────────────────────────────────────────────────────────────────

def make_sprint() -> Sprint:
    return Sprint(
        sprint_id="test-s192",
        name="s192",
        start_date=date(2026, 7, 30),
        end_date=date(2026, 8, 19),
        development_end_date=date(2026, 8, 14),
        public_holidays=0,
        buffer=0.1,
        backup=1.0,
        fixed_day_deduction=3.0,
    )


def make_ruleset() -> RuleSet:
    return RuleSet(rule_version="s192-baseline", hours_per_day=8.0, fixed_day_deduction=3.0)


def make_scenario(sprint_id: str) -> Scenario:
    resources = [
        ScenarioResource(
            scenario_id="sc-test", resource_id="r1", display_name="Alice",
            velocity=1.0, leave_days=0.0, ot_hours=0.0, ot_days=0.0,
            v_percent=1.0, others=0.0, type=ResourceType.DEV,
        ),
        ScenarioResource(
            scenario_id="sc-test", resource_id="r2", display_name="Bob",
            velocity=1.0, leave_days=1.0, ot_hours=0.0, ot_days=0.0,
            v_percent=0.9, others=0.0, type=ResourceType.QC,
        ),
    ]
    return Scenario(scenario_id="sc-test", sprint_id=sprint_id, name="Test", resources=resources)


# ── networkdays tests ─────────────────────────────────────────────────────────

def test_networkdays_basic():
    # Mon 2026-08-03 to Fri 2026-08-07 = 5 days
    assert networkdays(date(2026, 8, 3), date(2026, 8, 7)) == 5.0


def test_networkdays_with_weekend():
    # Thu 2026-07-30 to Mon 2026-08-03 = Thu, Fri, Mon = 3 days
    assert networkdays(date(2026, 7, 30), date(2026, 8, 3)) == 3.0


def test_networkdays_with_holidays():
    assert networkdays(date(2026, 8, 3), date(2026, 8, 7), holiday_count=1) == 4.0


def test_networkdays_empty_range():
    assert networkdays(date(2026, 8, 8), date(2026, 8, 7)) == 0.0


def test_networkdays_s192_window():
    """30 Jul – 14 Aug 2026 with 0 holidays."""
    # 30Jul(Thu) 31Jul(Fri) | 3-7Aug(5) | 10-14Aug(5) = 2+5+5 = 12
    assert networkdays(date(2026, 7, 30), date(2026, 8, 14), 0) == 12.0


# ── BR-01 dev_days ────────────────────────────────────────────────────────────

def test_br01_dev_days():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    # 12 - 3 - 1 = 8
    assert result.dev_days == 8.0


# ── BR-04 fte_no_ot ───────────────────────────────────────────────────────────

def test_br04_alice():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    alice = next(r for r in result.resource_results if r.display_name == "Alice")
    # velocity=1, dev_days=8, leave=0, v_pct=1.0 => 1 * (8-0) * 1 = 8
    assert alice.fte_no_ot == 8.0


def test_br04_bob():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    bob = next(r for r in result.resource_results if r.display_name == "Bob")
    # velocity=1, dev_days=8, leave=1, v_pct=0.9 => 1 * (8-1) * 0.9 = 6.3
    assert bob.fte_no_ot == 6.3


# ── BR-06 buffered V ─────────────────────────────────────────────────────────

def test_br06_alice_v():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    alice = next(r for r in result.resource_results if r.display_name == "Alice")
    # 8 * (1 - 0.1) = 7.2
    assert alice.v == 7.2


# ── BR-07 team totals ────────────────────────────────────────────────────────

def test_br07_dev_v():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    assert result.dev_v == 7.2   # Alice only


def test_br07_qc_v():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    # Bob: fte=6.3, v = 6.3 * 0.9 = 5.67
    assert result.qc_v == round(6.3 * 0.9, 3)


# ── BR-08 team_velocity_biz ──────────────────────────────────────────────────

def test_br08_biz():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    assert result.team_velocity_biz == min(result.dev_v, result.qc_v)


# ── BR-09 qc_minus_dev ───────────────────────────────────────────────────────

def test_br09_qc_minus_dev():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    assert result.qc_minus_dev == round(result.qc_v - result.dev_v, 3)


# ── BR-10 buffer_days ────────────────────────────────────────────────────────

def test_br10_buffer_days():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    # 0.1 * 8 = 0.8
    assert result.buffer_days == 0.8


# ── Error / Warning detection ─────────────────────────────────────────────────

def test_error_when_dev_days_nonpositive():
    sprint = Sprint(
        sprint_id="bad", name="bad", start_date=date(2026, 8, 14),
        end_date=date(2026, 8, 14), development_end_date=date(2026, 8, 14),
        public_holidays=0, buffer=0.1, backup=5.0, fixed_day_deduction=3.0,
    )
    sc = make_scenario("bad")
    rs = make_ruleset()
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 8, 14))
    # networkdays(14Aug,14Aug)=1, 1-3-5=-7 => ERROR
    assert result.has_errors
    assert any("ERROR" in w for w in result.warnings)


def test_warning_leave_exceeds_dev_days():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    sc.resources[0].leave_days = 20.0  # Alice leaves 20 days vs 8 dev_days
    result = calculate(sprint, sc, rs, as_of_date=date(2026, 7, 30))
    assert any("leave_days" in w for w in result.warnings)


# ── Snapshot determinism ─────────────────────────────────────────────────────

def test_same_as_of_date_deterministic():
    sprint = make_sprint()
    rs = make_ruleset()
    sc = make_scenario(sprint.sprint_id)
    fixed = date(2026, 7, 30)
    r1 = calculate(sprint, sc, rs, as_of_date=fixed)
    r2 = calculate(sprint, sc, rs, as_of_date=fixed)
    assert r1.dev_days == r2.dev_days
    assert r1.remaining_dev_days == r2.remaining_dev_days
    assert r1.team_velocity_biz == r2.team_velocity_biz


if __name__ == "__main__":
    tests = [
        test_networkdays_basic,
        test_networkdays_with_weekend,
        test_networkdays_with_holidays,
        test_networkdays_empty_range,
        test_networkdays_s192_window,
        test_br01_dev_days,
        test_br04_alice,
        test_br04_bob,
        test_br06_alice_v,
        test_br07_dev_v,
        test_br07_qc_v,
        test_br08_biz,
        test_br09_qc_minus_dev,
        test_br10_buffer_days,
        test_error_when_dev_days_nonpositive,
        test_warning_leave_exceeds_dev_days,
        test_same_as_of_date_deterministic,
    ]
    passed = failed = 0
    for t in tests:
        try:
            t()
            print(f"  PASS {t.__name__}")
            passed += 1
        except Exception as exc:
            print(f"  FAIL {t.__name__}: {exc}")
            failed += 1
    print(f"\n{passed} passed, {failed} failed.")
