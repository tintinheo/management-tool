"""Golden tests — Scrumban flow metrics & throughput forecast (NF-01 … NF-08, FC-03)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from datetime import date
from src.domain.pmi_models import Calendar, DefinitionOfWorkflow, StatusUpdate, Task
from src.pmi.flow import build_flow_items, compute_flow_metrics, forecast_completion

AS_OF = date(2026, 8, 11)


def _items():
    tasks = [Task(task_id=f"T{i}", summary=f"T{i}") for i in range(1, 5)]
    status = [
        StatusUpdate(task_id="T1", actual_start=date(2026, 8, 3), actual_finish=date(2026, 8, 6)),
        StatusUpdate(task_id="T2", actual_start=date(2026, 8, 4), actual_finish=date(2026, 8, 7)),
        StatusUpdate(task_id="T3", actual_start=date(2026, 8, 5)),   # WIP
        # T4 has no status
    ]
    return build_flow_items(tasks, status)


def test_wip_count():
    m = compute_flow_metrics(_items(), AS_OF, calendar=Calendar(name="MF"))
    assert m.wip == 1
    assert m.aging[0].task_id == "T3"
    assert m.aging[0].age_days == 5


def test_throughput():
    m = compute_flow_metrics(_items(), AS_OF, calendar=Calendar(name="MF"))
    assert sum(m.throughput_by_period.values()) == 2
    assert len(m.throughput_by_period) == 1   # both finished in the same ISO week


def test_cycle_time():
    m = compute_flow_metrics(_items(), AS_OF, calendar=Calendar(name="MF"))
    assert m.avg_cycle_time_days == 4.0


def test_sle_breach_when_configured():
    wf = DefinitionOfWorkflow(sle_days=3, sle_probability=0.85)
    m = compute_flow_metrics(_items(), AS_OF, calendar=Calendar(name="MF"), workflow=wf)
    assert m.aging[0].sle_breach is True     # age 5 > sle 3


def test_no_sle_returns_none():
    m = compute_flow_metrics(_items(), AS_OF, calendar=Calendar(name="MF"))
    assert m.aging[0].sle_breach is None
    assert any("Service Level" in w for w in m.warnings)


def test_forecast_ok():
    fc = forecast_completion(_items(), remaining_items=2, as_of_date=AS_OF)
    assert fc.status == "ok"
    assert fc.avg_throughput == 2.0
    assert fc.forecast_date == date(2026, 8, 18)   # 1 week out
    assert any("low confidence" in w.lower() for w in fc.warnings)


def test_forecast_insufficient_without_history():
    tasks = [Task(task_id="A", summary="A")]
    items = build_flow_items(tasks, [StatusUpdate(task_id="A", actual_start=date(2026, 8, 3))])
    fc = forecast_completion(items, remaining_items=1, as_of_date=AS_OF)
    assert fc.status == "insufficient_data"
    assert any("history" in m.lower() for m in fc.missing)


def test_forecast_insufficient_when_nothing_remaining():
    fc = forecast_completion(_items(), remaining_items=0, as_of_date=AS_OF)
    assert fc.status == "insufficient_data"


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
