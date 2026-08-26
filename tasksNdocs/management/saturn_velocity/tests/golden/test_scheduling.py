"""Golden tests — Critical Path scheduling (FC-01, FC-02).

Network (calendar Mon-Fri, no holidays, anchor Mon 2026-08-03):
  A dur3 (planned_start Mon 8/3)        ES 8/3  EF 8/5   slack 0  critical
  C dur4 (no preds)                     ES 8/3  EF 8/6   slack 4
  B dur5  FS after A                    ES 8/6  EF 8/12  slack 0  critical
  D dur2  FS after B and C              ES 8/13 EF 8/14  slack 0  critical
Project finish = Fri 2026-08-14. Critical path = A, B, D.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from datetime import date
from src.domain.pmi_models import Calendar, Dependency, DependencyType, Task, TaskPlan
from src.pmi.scheduling import forecast_schedule

MON = date(2026, 8, 3)


def _network():
    cal = Calendar(name="MF")
    a = Task(task_id="A", summary="A")
    b = Task(task_id="B", summary="B")
    c = Task(task_id="C", summary="C")
    d = Task(task_id="D", summary="D")
    tasks = [a, b, c, d]
    plans = {
        "A": TaskPlan(task_id="A", duration_days=3, planned_start=MON),
        "B": TaskPlan(task_id="B", duration_days=5),
        "C": TaskPlan(task_id="C", duration_days=4),
        "D": TaskPlan(task_id="D", duration_days=2),
    }
    deps = [
        Dependency(predecessor_task_id="A", successor_task_id="B", type=DependencyType.FS),
        Dependency(predecessor_task_id="B", successor_task_id="D", type=DependencyType.FS),
        Dependency(predecessor_task_id="C", successor_task_id="D", type=DependencyType.FS),
    ]
    return tasks, plans, deps, cal


def test_schedule_status_ok():
    tasks, plans, deps, cal = _network()
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=MON, project_start=MON)
    assert fc.status == "ok"
    assert fc.missing == []


def test_project_finish_date():
    tasks, plans, deps, cal = _network()
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=MON, project_start=MON)
    assert fc.project_finish == date(2026, 8, 14)


def test_task_early_dates():
    tasks, plans, deps, cal = _network()
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=MON, project_start=MON)
    by = {s.task_id: s for s in fc.task_schedules}
    assert by["A"].early_start == date(2026, 8, 3)
    assert by["A"].early_finish == date(2026, 8, 5)
    assert by["B"].early_start == date(2026, 8, 6)
    assert by["B"].early_finish == date(2026, 8, 12)
    assert by["D"].early_start == date(2026, 8, 13)
    assert by["D"].early_finish == date(2026, 8, 14)


def test_slack_and_criticality():
    tasks, plans, deps, cal = _network()
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=MON, project_start=MON)
    by = {s.task_id: s for s in fc.task_schedules}
    assert by["A"].total_slack_days == 0 and by["A"].critical
    assert by["B"].total_slack_days == 0 and by["B"].critical
    assert by["D"].total_slack_days == 0 and by["D"].critical
    assert by["C"].total_slack_days == 4 and not by["C"].critical


def test_critical_path_order():
    tasks, plans, deps, cal = _network()
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=MON, project_start=MON)
    assert fc.critical_path == ["A", "B", "D"]


def test_missing_duration_gates():
    tasks, plans, deps, cal = _network()
    plans["B"].duration_days = None       # remove a duration
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=MON, project_start=MON)
    assert fc.status == "insufficient_data"
    assert any("duration" in m.lower() for m in fc.missing)


def test_cycle_gates():
    cal = Calendar(name="MF")
    a = Task(task_id="A", summary="A")
    b = Task(task_id="B", summary="B")
    plans = {"A": TaskPlan(task_id="A", duration_days=2),
             "B": TaskPlan(task_id="B", duration_days=2)}
    deps = [
        Dependency(predecessor_task_id="A", successor_task_id="B", type=DependencyType.FS),
        Dependency(predecessor_task_id="B", successor_task_id="A", type=DependencyType.FS),
    ]
    fc = forecast_schedule([a, b], plans, deps, cal, as_of_date=MON, project_start=MON)
    assert fc.status == "insufficient_data"
    assert any("cycle" in m.lower() for m in fc.missing)


def test_missing_as_of_gates():
    tasks, plans, deps, cal = _network()
    fc = forecast_schedule(tasks, plans, deps, cal, as_of_date=None, project_start=MON)
    assert fc.status == "insufficient_data"


def test_no_dependencies_parallel():
    cal = Calendar(name="MF")
    a = Task(task_id="A", summary="A")
    b = Task(task_id="B", summary="B")
    plans = {"A": TaskPlan(task_id="A", duration_days=3),
             "B": TaskPlan(task_id="B", duration_days=5)}
    fc = forecast_schedule([a, b], plans, [], cal, as_of_date=MON, project_start=MON)
    # Both start at anchor; project finish driven by the longer task B (5 days -> Fri).
    assert fc.status == "ok"
    assert fc.project_finish == date(2026, 8, 7)


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
