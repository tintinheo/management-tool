"""
Progress reconciliation (PR-01 … PR-04).

Progress is derived only from an approved baseline (planned dates/effort) and status
actuals observed at an explicit ``as_of_date``. Tasks with no actual evidence are
reported as *unknown* and are never counted as zero-percent or complete
(business rule §8.3). ``Status`` text and story points are not converted to progress.
"""
from dataclasses import dataclass, field
from datetime import date
from typing import Dict, List, Optional

from ..domain.pmi_models import (
    Calendar, Milestone, MilestoneStatus, StatusUpdate, Task, TaskPlan,
)
from . import calendar_service as cal_svc


@dataclass
class TaskProgress:
    task_id: str
    summary: str
    planned_start: Optional[date]
    planned_finish: Optional[date]
    actual_start: Optional[date]
    actual_finish: Optional[date]
    percent_complete: Optional[float]
    remaining_effort: Optional[float]
    schedule_variance_days: Optional[int]   # +ahead / -behind / None=unknown
    state: str                              # not_started | in_progress | done | unknown
    known: bool


@dataclass
class ProgressResult:
    as_of_date: Optional[date]
    task_progress: List[TaskProgress] = field(default_factory=list)
    completed_count: int = 0
    in_progress_count: int = 0
    not_started_count: int = 0
    unknown_count: int = 0
    total_remaining_effort: Optional[float] = None
    remaining_is_partial: bool = False       # True when some tasks lack remaining data
    warnings: List[str] = field(default_factory=list)


def _variance_days(
    planned_finish: Optional[date],
    actual_finish: Optional[date],
    as_of: Optional[date],
    actual_start: Optional[date],
    calendar: Optional[Calendar],
) -> Optional[int]:
    """Signed schedule variance in days: positive = ahead, negative = behind."""
    def diff(a: date, b: date) -> int:
        # working days from b→a, signed
        if calendar is not None:
            if a >= b:
                return cal_svc.working_days_between(calendar, b, a) - 1
            return -(cal_svc.working_days_between(calendar, a, b) - 1)
        return (a - b).days

    if planned_finish and actual_finish:
        return diff(planned_finish, actual_finish)      # finished early → positive
    if planned_finish and as_of and actual_start and as_of > planned_finish:
        return -diff(as_of, planned_finish)             # overdue and not finished
    return None


def compute_progress(
    tasks: List[Task],
    baseline_plans: Dict[str, TaskPlan],
    status_updates: List[StatusUpdate],
    as_of_date: Optional[date],
    calendar: Optional[Calendar] = None,
) -> ProgressResult:
    """Reconcile baseline plans against status actuals as of ``as_of_date``."""
    result = ProgressResult(as_of_date=as_of_date)
    status_by_task = {s.task_id: s for s in status_updates}

    remaining_sum = 0.0
    remaining_seen = False
    remaining_missing = False

    for t in tasks:
        plan = baseline_plans.get(t.task_id)
        upd = status_by_task.get(t.task_id)
        planned_start = plan.planned_start if plan else None
        planned_finish = plan.planned_finish if plan else None

        actual_start = upd.actual_start if upd else None
        actual_finish = upd.actual_finish if upd else None
        pct = upd.percent_complete if upd else None
        rem = upd.remaining_effort if upd else None

        # Determine state without inventing anything.
        if upd is None:
            state, known = "unknown", False
        elif actual_finish is not None or (pct is not None and pct >= 100):
            state, known = "done", True
        elif actual_start is not None or (pct is not None and pct > 0) or rem is not None:
            state, known = "in_progress", True
        elif pct == 0:
            state, known = "not_started", True
        else:
            state, known = "unknown", False

        if state == "done":
            result.completed_count += 1
        elif state == "in_progress":
            result.in_progress_count += 1
        elif state == "not_started":
            result.not_started_count += 1
        else:
            result.unknown_count += 1

        if rem is not None:
            remaining_sum += rem
            remaining_seen = True
        elif state != "done":
            remaining_missing = True

        variance = _variance_days(planned_finish, actual_finish, as_of_date, actual_start, calendar)

        result.task_progress.append(TaskProgress(
            task_id=t.task_id,
            summary=t.summary,
            planned_start=planned_start,
            planned_finish=planned_finish,
            actual_start=actual_start,
            actual_finish=actual_finish,
            percent_complete=pct,
            remaining_effort=rem,
            schedule_variance_days=variance,
            state=state,
            known=known,
        ))

    if result.unknown_count:
        result.warnings.append(
            f"{result.unknown_count} task(s) have no status evidence and are counted "
            "as unknown, not zero.")
    result.total_remaining_effort = round(remaining_sum, 3) if remaining_seen else None
    result.remaining_is_partial = remaining_missing
    if remaining_missing:
        result.warnings.append(
            "Remaining effort is partial: some unfinished tasks have no remaining value.")
    return result


def assess_milestones(
    milestones: List[Milestone],
    as_of_date: Optional[date],
) -> List[dict]:
    """Milestone health (PR-02). Health is withheld when a planned date or a
    forecast/actual date is missing — it is never inferred."""
    rows: List[dict] = []
    for m in milestones:
        health = "unknown"
        note = ""
        if m.planned_date is None:
            note = "No planned date — health withheld."
        elif m.actual_date is not None:
            health = "achieved" if m.actual_date <= m.planned_date else "missed"
        elif m.forecast_date is not None:
            if m.forecast_date <= m.planned_date:
                health = "on_track"
            else:
                health = "at_risk"
        elif as_of_date is not None and as_of_date > m.planned_date:
            health = "missed"
            note = "Past planned date with no actual/forecast."
        else:
            note = "No forecast/actual — health withheld."
        rows.append({
            "milestone_id": m.milestone_id,
            "name": m.name,
            "planned_date": m.planned_date,
            "forecast_date": m.forecast_date,
            "actual_date": m.actual_date,
            "health": health,
            "note": note,
        })
    return rows
