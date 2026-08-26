"""Golden tests — PMI application services (snapshots, forecast orchestration)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

import json
from datetime import date
from src.domain.pmi_models import (
    Calendar, Deliverable, Dependency, DependencyType, Project, StatusUpdate,
    Task, TaskPlan, WorkPackage,
)
from src.application import pmi_services as svc

MON = date(2026, 8, 3)


def _fixture():
    project = Project(name="P", owner="o")
    d = Deliverable(deliverable_id="D1", name="Rel")
    wp = WorkPackage(work_package_id="WP1", deliverable_id="D1", name="Backend")
    a = Task(task_id="A", work_package_id="WP1", summary="A")
    b = Task(task_id="B", work_package_id="WP1", summary="B")
    tasks = [a, b]
    plans = {
        "A": TaskPlan(task_id="A", duration_days=3, planned_start=MON,
                      planned_finish=date(2026, 8, 5), planned_effort=3),
        "B": TaskPlan(task_id="B", duration_days=5, planned_finish=date(2026, 8, 12),
                      planned_effort=5),
    }
    deps = [Dependency(predecessor_task_id="A", successor_task_id="B", type=DependencyType.FS)]
    return project, [d], [wp], tasks, plans, deps


def test_baseline_roundtrip():
    project, dels, wps, tasks, plans, deps = _fixture()
    base = svc.create_baseline_snapshot(project, dels, wps, tasks, plans,
                                        label="B1", as_of_date=MON, approved_by="pm")
    restored = svc.baseline_plans(base)
    assert restored["A"].planned_start == MON
    assert restored["A"].planned_finish == date(2026, 8, 5)
    assert restored["B"].duration_days == 5


def test_run_forecasts_schedule_ok():
    project, dels, wps, tasks, plans, deps = _fixture()
    status = [
        StatusUpdate(task_id="A", actual_start=MON, actual_finish=date(2026, 8, 5)),
    ]
    out = svc.run_forecasts(project, tasks, plans, deps, Calendar(name="MF"),
                            status, as_of_date=MON, project_start=MON)
    assert out["schedule"].status == "ok"
    assert out["schedule"].project_finish is not None
    # Flow has only one completed item -> low confidence but should still produce a result object.
    assert out["flow"].status in ("ok", "insufficient_data")


def test_forecast_snapshot_schedule():
    project, dels, wps, tasks, plans, deps = _fixture()
    out = svc.run_forecasts(project, tasks, plans, deps, Calendar(name="MF"),
                            [], as_of_date=MON, project_start=MON)
    snap = svc.create_forecast_snapshot(project, "schedule", out["schedule"], as_of_date=MON)
    assert snap.method == "schedule"
    assert snap.status == "ok"
    assert snap.outputs["project_finish"] is not None
    assert snap.rule_version == "cpm-v1"


def test_forecast_snapshot_flow_insufficient():
    project, dels, wps, tasks, plans, deps = _fixture()
    out = svc.run_forecasts(project, tasks, plans, deps, Calendar(name="MF"),
                            [], as_of_date=MON, project_start=MON)
    snap = svc.create_forecast_snapshot(project, "flow", out["flow"], as_of_date=MON)
    assert snap.method == "flow"
    assert snap.status == "insufficient_data"


def test_to_json_serialises_dates():
    project, dels, wps, tasks, plans, deps = _fixture()
    base = svc.create_baseline_snapshot(project, dels, wps, tasks, plans,
                                        label="B1", as_of_date=MON)
    text = svc.to_json({"baseline_id": base.baseline_id, "as_of": base.as_of_date,
                        "schedule": base.schedule_payload})
    parsed = json.loads(text)
    assert parsed["as_of"] == "2026-08-03"
    assert "A" in parsed["schedule"]


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
