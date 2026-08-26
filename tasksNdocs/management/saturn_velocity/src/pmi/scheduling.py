"""
Deterministic schedule forecast — Critical Path Method (FC-01, FC-02, PM-04).

The engine works in integer *working-day* space (1 unit = 1 working day on the
project calendar) so forward/backward passes are exact, then maps indices back to
calendar dates. It refuses to run — returning ``status="insufficient_data"`` with an
explicit missing list — when durations, dependencies, calendar or a status date are
not all present. It never invents a completion date (business rule §8.4).
"""
from dataclasses import dataclass, field
from datetime import date, timedelta
from math import ceil
from typing import Dict, List, Optional

from ..domain.pmi_models import Calendar, Dependency, DependencyType, Task, TaskPlan
from . import calendar_service as cal_svc


@dataclass
class TaskSchedule:
    task_id: str
    summary: str
    duration_days: int
    early_start: date
    early_finish: date
    late_start: date
    late_finish: date
    total_slack_days: int
    critical: bool


@dataclass
class ScheduleForecast:
    status: str                                   # "ok" | "insufficient_data"
    as_of_date: Optional[date] = None
    rule_version: str = "cpm-v1"
    project_finish: Optional[date] = None
    task_schedules: List[TaskSchedule] = field(default_factory=list)
    critical_path: List[str] = field(default_factory=list)
    missing: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


class _WorkingDayAxis:
    """Maps between calendar dates and 0-based working-day indices from an anchor."""

    def __init__(self, cal: Calendar, anchor: date):
        self._cal = cal
        self._anchor = cal_svc.next_working_day(cal, anchor)
        self._days: List[date] = [self._anchor]

    def _extend_to(self, index: int) -> None:
        while len(self._days) <= index:
            nxt = self._days[-1] + timedelta(days=1)
            self._days.append(cal_svc.next_working_day(self._cal, nxt))

    def date_at(self, index: int) -> date:
        if index < 0:
            index = 0
        self._extend_to(index)
        return self._days[index]

    def index_at(self, d: date) -> int:
        """Index of ``d`` if a working day on/after anchor, else index of next one."""
        target = cal_svc.next_working_day(self._cal, d) if not cal_svc.is_working_day(self._cal, d) else d
        if target <= self._anchor:
            return 0
        return cal_svc.working_days_between(self._cal, self._anchor, target) - 1


def _topological_order(task_ids: List[str], deps: List[Dependency]) -> Optional[List[str]]:
    """Kahn's algorithm. Returns None when a dependency cycle exists."""
    indeg: Dict[str, int] = {tid: 0 for tid in task_ids}
    adj: Dict[str, List[str]] = {tid: [] for tid in task_ids}
    for d in deps:
        if d.predecessor_task_id in indeg and d.successor_task_id in indeg:
            adj[d.predecessor_task_id].append(d.successor_task_id)
            indeg[d.successor_task_id] += 1
    queue = [tid for tid in task_ids if indeg[tid] == 0]
    order: List[str] = []
    while queue:
        n = queue.pop(0)
        order.append(n)
        for m in adj[n]:
            indeg[m] -= 1
            if indeg[m] == 0:
                queue.append(m)
    return order if len(order) == len(task_ids) else None


def _duration_units(plan: Optional[TaskPlan]) -> Optional[int]:
    """Whole working-day duration from a plan, or None when not derivable."""
    if plan is None:
        return None
    if plan.duration_days is not None and plan.duration_days > 0:
        return max(1, ceil(plan.duration_days))
    return None


def forecast_schedule(
    tasks: List[Task],
    plans: Dict[str, TaskPlan],
    dependencies: List[Dependency],
    calendar: Calendar,
    as_of_date: Optional[date],
    project_start: Optional[date] = None,
    *,
    exclude_done: bool = False,
    done_states: Optional[List[str]] = None,
) -> ScheduleForecast:
    """Compute early/late dates, slack and the critical path.

    Parameters
    ----------
    project_start : anchor for tasks with no predecessor; defaults to ``as_of_date``.
    exclude_done  : when True, tasks already in a finished state are dropped from the
                    network (their remaining duration is 0).
    """
    fc = ScheduleForecast(status="ok", as_of_date=as_of_date)

    # ── Prerequisite gate (FC-01) ────────────────────────────────────────────
    if as_of_date is None:
        fc.missing.append("Status/as-of date is required.")
    if not tasks:
        fc.missing.append("No tasks to schedule.")
    if not calendar.working_weekdays:
        fc.missing.append("Calendar has no working weekdays.")

    finished = set(done_states or [])
    active_tasks = [t for t in tasks if not (exclude_done and t.status in finished)]
    task_ids = [t.task_id for t in active_tasks]
    id_set = set(task_ids)
    by_id = {t.task_id: t for t in active_tasks}

    durations: Dict[str, int] = {}
    for t in active_tasks:
        d = _duration_units(plans.get(t.task_id))
        if d is None:
            fc.missing.append(f"Task '{t.summary or t.task_id}' has no positive duration.")
        else:
            durations[t.task_id] = d

    active_deps = [d for d in dependencies
                   if d.predecessor_task_id in id_set and d.successor_task_id in id_set]
    for d in dependencies:
        if d.predecessor_task_id in id_set and d.successor_task_id not in id_set:
            fc.warnings.append(
                f"Dependency ignored: successor '{d.successor_task_id}' not in active set.")
        if d.successor_task_id in id_set and d.predecessor_task_id not in id_set:
            fc.warnings.append(
                f"Dependency ignored: predecessor '{d.predecessor_task_id}' not in active set.")

    order = _topological_order(task_ids, active_deps) if task_ids else []
    if task_ids and order is None:
        fc.missing.append("Dependency cycle detected; schedule cannot be computed.")

    if fc.missing:
        fc.status = "insufficient_data"
        return fc

    anchor = project_start or as_of_date
    axis = _WorkingDayAxis(calendar, anchor)

    succ_by_pred: Dict[str, List[Dependency]] = {tid: [] for tid in task_ids}
    pred_by_succ: Dict[str, List[Dependency]] = {tid: [] for tid in task_ids}
    for d in active_deps:
        succ_by_pred[d.predecessor_task_id].append(d)
        pred_by_succ[d.successor_task_id].append(d)

    # ── Forward pass: earliest start / finish (index space) ───────────────────
    es: Dict[str, int] = {}
    ef: Dict[str, int] = {}
    for tid in order:                       # topological order guarantees preds ready
        dur = durations[tid]
        plan = plans.get(tid)
        floor_idx = 0
        if plan and plan.planned_start:
            floor_idx = max(floor_idx, axis.index_at(plan.planned_start))
        start_idx = floor_idx
        for dep in pred_by_succ[tid]:
            p = dep.predecessor_task_id
            lag = int(round(dep.lag_days))
            if dep.type == DependencyType.FS:
                start_idx = max(start_idx, ef[p] + 1 + lag)
            elif dep.type == DependencyType.SS:
                start_idx = max(start_idx, es[p] + lag)
            elif dep.type == DependencyType.FF:
                start_idx = max(start_idx, ef[p] + lag - (dur - 1))
            elif dep.type == DependencyType.SF:
                start_idx = max(start_idx, es[p] + lag - (dur - 1))
        start_idx = max(0, start_idx)
        es[tid] = start_idx
        ef[tid] = start_idx + dur - 1

    project_finish_idx = max(ef.values()) if ef else 0

    # ── Backward pass: latest start / finish (index space) ────────────────────
    lf: Dict[str, int] = {}
    ls: Dict[str, int] = {}
    for tid in reversed(order):
        dur = durations[tid]
        if not succ_by_pred[tid]:
            latest_finish = project_finish_idx
        else:
            latest_finish = project_finish_idx
            for dep in succ_by_pred[tid]:
                s = dep.successor_task_id
                lag = int(round(dep.lag_days))
                ls_s = ls[s]
                lf_s = lf[s]
                if dep.type == DependencyType.FS:
                    bound = ls_s - 1 - lag
                elif dep.type == DependencyType.SS:
                    bound = (ls_s - lag) + dur - 1
                elif dep.type == DependencyType.FF:
                    bound = lf_s - lag
                elif dep.type == DependencyType.SF:
                    bound = (lf_s - lag) + dur - 1
                else:
                    bound = project_finish_idx
                latest_finish = min(latest_finish, bound)
        lf[tid] = latest_finish
        ls[tid] = latest_finish - dur + 1

    # ── Assemble result ──────────────────────────────────────────────────────
    schedules: List[TaskSchedule] = []
    critical_ids: List[str] = []
    for tid in order:
        slack = ls[tid] - es[tid]
        is_crit = slack <= 0
        if is_crit:
            critical_ids.append(tid)
        schedules.append(TaskSchedule(
            task_id=tid,
            summary=by_id[tid].summary,
            duration_days=durations[tid],
            early_start=axis.date_at(es[tid]),
            early_finish=axis.date_at(ef[tid]),
            late_start=axis.date_at(max(0, ls[tid])),
            late_finish=axis.date_at(max(0, lf[tid])),
            total_slack_days=slack,
            critical=is_crit,
        ))

    fc.task_schedules = schedules
    fc.project_finish = axis.date_at(project_finish_idx)
    # Critical path ordered by early start.
    fc.critical_path = [s.task_id for s in sorted(
        (s for s in schedules if s.critical), key=lambda x: x.early_start)]
    return fc
