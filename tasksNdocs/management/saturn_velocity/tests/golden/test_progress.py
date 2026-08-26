"""Golden tests — progress reconciliation & milestone health (PR-01 … PR-04)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from datetime import date
from src.domain.pmi_models import Calendar, Milestone, StatusUpdate, Task, TaskPlan
from src.pmi.progress import compute_progress, assess_milestones

AS_OF = date(2026, 8, 11)


def _tasks_plans_status():
    tasks = [Task(task_id="A", summary="A"), Task(task_id="B", summary="B"),
             Task(task_id="C", summary="C")]
    plans = {
        "A": TaskPlan(task_id="A", planned_start=date(2026, 8, 3), planned_finish=date(2026, 8, 7)),
        "B": TaskPlan(task_id="B", planned_start=date(2026, 8, 10), planned_finish=date(2026, 8, 12)),
        "C": TaskPlan(task_id="C", planned_start=date(2026, 8, 10), planned_finish=date(2026, 8, 13)),
    }
    status = [
        StatusUpdate(task_id="A", actual_start=date(2026, 8, 3), actual_finish=date(2026, 8, 6)),
        StatusUpdate(task_id="B", actual_start=date(2026, 8, 10), remaining_effort=2.0),
        # C has no status -> unknown
    ]
    return tasks, plans, status


def test_state_counts():
    tasks, plans, status = _tasks_plans_status()
    res = compute_progress(tasks, plans, status, AS_OF, calendar=Calendar(name="MF"))
    assert res.completed_count == 1
    assert res.in_progress_count == 1
    assert res.unknown_count == 1
    assert res.not_started_count == 0


def test_remaining_partial():
    tasks, plans, status = _tasks_plans_status()
    res = compute_progress(tasks, plans, status, AS_OF, calendar=Calendar(name="MF"))
    assert res.total_remaining_effort == 2.0
    assert res.remaining_is_partial is True


def test_unknown_not_zero_warning():
    tasks, plans, status = _tasks_plans_status()
    res = compute_progress(tasks, plans, status, AS_OF, calendar=Calendar(name="MF"))
    assert any("unknown" in w.lower() for w in res.warnings)


def test_variance_ahead():
    tasks, plans, status = _tasks_plans_status()
    res = compute_progress(tasks, plans, status, AS_OF, calendar=Calendar(name="MF"))
    by = {tp.task_id: tp for tp in res.task_progress}
    assert by["A"].schedule_variance_days == 1     # finished 1 working day early
    assert by["A"].state == "done"


def test_variance_unknown_when_in_progress_before_due():
    tasks, plans, status = _tasks_plans_status()
    res = compute_progress(tasks, plans, status, AS_OF, calendar=Calendar(name="MF"))
    by = {tp.task_id: tp for tp in res.task_progress}
    assert by["B"].schedule_variance_days is None
    assert by["B"].state == "in_progress"


def test_overdue_behind_variance():
    tasks = [Task(task_id="X", summary="X")]
    plans = {"X": TaskPlan(task_id="X", planned_finish=date(2026, 8, 6))}
    status = [StatusUpdate(task_id="X", actual_start=date(2026, 8, 3), remaining_effort=1.0)]
    res = compute_progress(tasks, plans, status, date(2026, 8, 11), calendar=Calendar(name="MF"))
    by = {tp.task_id: tp for tp in res.task_progress}
    # Past planned finish (8/6) and not done -> negative (behind).
    assert by["X"].schedule_variance_days is not None
    assert by["X"].schedule_variance_days < 0


def test_milestone_health():
    ms = [
        Milestone(milestone_id="m1", name="achieved", planned_date=date(2026, 8, 10),
                  actual_date=date(2026, 8, 10)),
        Milestone(milestone_id="m2", name="missed", planned_date=date(2026, 8, 10),
                  actual_date=date(2026, 8, 12)),
        Milestone(milestone_id="m3", name="on_track", planned_date=date(2026, 8, 20),
                  forecast_date=date(2026, 8, 18)),
        Milestone(milestone_id="m4", name="at_risk", planned_date=date(2026, 8, 20),
                  forecast_date=date(2026, 8, 25)),
        Milestone(milestone_id="m5", name="withheld"),  # no planned date
        Milestone(milestone_id="m6", name="overdue", planned_date=date(2026, 8, 5)),
    ]
    rows = {r["milestone_id"]: r for r in assess_milestones(ms, AS_OF)}
    assert rows["m1"]["health"] == "achieved"
    assert rows["m2"]["health"] == "missed"
    assert rows["m3"]["health"] == "on_track"
    assert rows["m4"]["health"] == "at_risk"
    assert rows["m5"]["health"] == "unknown"
    assert rows["m6"]["health"] == "missed"


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
