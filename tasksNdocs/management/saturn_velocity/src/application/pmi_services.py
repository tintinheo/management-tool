"""
PMI application services — orchestrate the project-control use-cases without touching
Streamlit state or UI. Builds immutable baseline/status/forecast snapshots and selects
forecast methods based on the data actually available (FR-UP-10).
"""
import json
from dataclasses import asdict, is_dataclass
from datetime import date
from typing import Dict, List, Optional

from ..domain.pmi_models import (
    ActualCost, BaselineSnapshot, Calendar, CostBaseline, Deliverable, Dependency,
    ForecastSnapshot, Milestone, Project, ResourceAssignment, StatusSnapshot,
    StatusUpdate, Task, TaskPlan, WorkPackage, new_id,
)
from ..pmi import flow as flow_engine
from ..pmi import forecast_evm as evm_engine
from ..pmi import progress as progress_engine
from ..pmi import scheduling as schedule_engine
from ..pmi import workload as workload_engine


# ── JSON helpers ─────────────────────────────────────────────────────────────

def _json_default(o):
    if isinstance(o, date):
        return o.isoformat()
    if is_dataclass(o):
        return asdict(o)
    if hasattr(o, "value"):        # Enum
        return o.value
    return str(o)


def to_json(payload) -> str:
    return json.dumps(payload, indent=2, ensure_ascii=False, default=_json_default)


# ── Baseline snapshot (PM-07) ────────────────────────────────────────────────

def create_baseline_snapshot(
    project: Project,
    deliverables: List[Deliverable],
    work_packages: List[WorkPackage],
    tasks: List[Task],
    plans: Dict[str, TaskPlan],
    *,
    label: str,
    as_of_date: date,
    approved_by: str = "",
    source_version: str = "",
) -> BaselineSnapshot:
    scope_payload = {
        "deliverables": [asdict(d) for d in deliverables],
        "work_packages": [asdict(w) for w in work_packages],
        "tasks": [{"task_id": t.task_id, "summary": t.summary,
                   "work_package_id": t.work_package_id, "order": t.order} for t in tasks],
    }
    schedule_payload = {
        tid: {
            "planned_start": plan.planned_start.isoformat() if plan.planned_start else None,
            "planned_finish": plan.planned_finish.isoformat() if plan.planned_finish else None,
            "planned_effort": plan.planned_effort,
            "duration_days": plan.duration_days,
        }
        for tid, plan in plans.items()
    }
    return BaselineSnapshot(
        baseline_id=new_id("BASE"),
        project_id=project.project_id,
        label=label,
        as_of_date=as_of_date,
        scope_payload=scope_payload,
        schedule_payload=schedule_payload,
        approved_by=approved_by,
        source_version=source_version,
    )


def baseline_plans(baseline: BaselineSnapshot) -> Dict[str, TaskPlan]:
    """Reconstruct planned TaskPlans from a baseline's schedule payload."""
    out: Dict[str, TaskPlan] = {}
    for tid, sp in baseline.schedule_payload.items():
        out[tid] = TaskPlan(
            task_id=tid,
            planned_start=date.fromisoformat(sp["planned_start"]) if sp.get("planned_start") else None,
            planned_finish=date.fromisoformat(sp["planned_finish"]) if sp.get("planned_finish") else None,
            planned_effort=sp.get("planned_effort"),
            duration_days=sp.get("duration_days"),
        )
    return out


# ── Status snapshot (PM-08) ──────────────────────────────────────────────────

def create_status_snapshot(
    project: Project,
    updates: List[StatusUpdate],
    *,
    as_of_date: date,
    baseline_id: Optional[str] = None,
    updater: str = "",
) -> StatusSnapshot:
    return StatusSnapshot(
        status_id=new_id("STAT"),
        project_id=project.project_id,
        as_of_date=as_of_date,
        baseline_id=baseline_id,
        updates=list(updates),
        updater=updater,
    )


# ── Forecast orchestration (FR-UP-10) ────────────────────────────────────────

def run_forecasts(
    project: Project,
    tasks: List[Task],
    plans: Dict[str, TaskPlan],
    dependencies: List[Dependency],
    calendar: Calendar,
    status_updates: List[StatusUpdate],
    as_of_date: date,
    *,
    project_start: Optional[date] = None,
) -> Dict[str, object]:
    """Run every forecast method whose prerequisites are satisfied.

    Returns a dict with keys ``schedule`` and ``flow``; each value is the method's own
    result object carrying its own ``status`` ("ok" / "insufficient_data"). Methods are
    not mixed — each declares its own missing data (business rule §8.4).
    """
    done_states = project.workflow.finished_states()
    schedule_fc = schedule_engine.forecast_schedule(
        tasks, plans, dependencies, calendar, as_of_date,
        project_start=project_start, done_states=done_states,
    )

    items = flow_engine.build_flow_items(tasks, status_updates)
    finished_ids = {u.task_id for u in status_updates if u.actual_finish is not None}
    remaining = len([t for t in tasks if t.task_id not in finished_ids])
    flow_fc = flow_engine.forecast_completion(items, remaining, as_of_date)

    return {"schedule": schedule_fc, "flow": flow_fc}


def create_forecast_snapshot(
    project: Project,
    method: str,
    result: object,
    *,
    as_of_date: date,
    rule_version: str = "",
) -> ForecastSnapshot:
    """Freeze a forecast result into an immutable, replayable snapshot (FC-07)."""
    status = getattr(result, "status", "ok")
    warnings = list(getattr(result, "warnings", []))
    missing = list(getattr(result, "missing", []))
    drivers: List[str] = []
    outputs: Dict = {}

    if method == "schedule":
        drivers = list(getattr(result, "critical_path", []))
        pf = getattr(result, "project_finish", None)
        outputs = {
            "project_finish": pf.isoformat() if pf else None,
            "critical_path": drivers,
            "tasks": [asdict(s) for s in getattr(result, "task_schedules", [])],
        }
        rule_version = rule_version or getattr(result, "rule_version", "cpm-v1")
    elif method == "flow":
        fdate = getattr(result, "forecast_date", None)
        outputs = {
            "forecast_date": fdate.isoformat() if fdate else None,
            "avg_throughput": getattr(result, "avg_throughput", None),
            "periods_to_complete": getattr(result, "periods_to_complete", None),
            "sampled_throughput": getattr(result, "sampled_throughput", []),
        }
        drivers = [f"avg throughput={getattr(result, 'avg_throughput', None)}"]
        rule_version = rule_version or getattr(result, "method", "throughput-v1")

    return ForecastSnapshot(
        forecast_id=new_id("FC"),
        project_id=project.project_id,
        method=method,
        as_of_date=as_of_date,
        inputs={"as_of_date": as_of_date.isoformat()},
        outputs=outputs,
        drivers=drivers,
        warnings=warnings + ([f"MISSING: {m}" for m in missing] if missing else []),
        rule_version=rule_version,
        status=status,
    )


# ── Convenience wrappers used by pages ───────────────────────────────────────

def compute_workload(assignments: List[ResourceAssignment], calendar: Calendar, **kw):
    return workload_engine.compute_workload(assignments, calendar, **kw)


def compute_progress(tasks, baseline_plans_map, status_updates, as_of_date, calendar=None):
    return progress_engine.compute_progress(
        tasks, baseline_plans_map, status_updates, as_of_date, calendar)


def assess_milestones(milestones: List[Milestone], as_of_date: date):
    return progress_engine.assess_milestones(milestones, as_of_date)


def compute_evm(cost_baselines: List[CostBaseline], actual_costs: List[ActualCost],
                percent_complete_by_wp=None, planned_percent_by_wp=None):
    return evm_engine.compute_evm(
        cost_baselines, actual_costs, percent_complete_by_wp, planned_percent_by_wp)
