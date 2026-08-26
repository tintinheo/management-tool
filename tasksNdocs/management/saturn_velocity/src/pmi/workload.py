"""
Workload demand-vs-capacity (WL-01 … WL-04).

Demand comes only from approved :class:`ResourceAssignment` records spread across
their working days; capacity comes from each resource's working calendar. Assignments
that lack effort or a date window are reported as *unknown* — never treated as zero
(business rule §8.2). Nothing here invents utilization targets or warning bands.
"""
from dataclasses import dataclass, field
from datetime import date
from typing import Dict, List, Optional

from ..domain.pmi_models import Calendar, ResourceAssignment
from . import calendar_service as cal_svc

# One resource-day of availability per working day (person-day unit). The proposal
# lists effort_unit as sign-off data; this is the neutral default and is configurable.
_CAPACITY_PER_WORKING_DAY = 1.0


@dataclass
class WorkloadCell:
    resource_id: str
    resource_name: str
    period: str
    demand: float
    capacity: float

    @property
    def over_allocated(self) -> bool:
        return self.demand > self.capacity + 1e-9

    @property
    def utilization(self) -> Optional[float]:
        if self.capacity <= 0:
            return None
        return round(self.demand / self.capacity, 3)


@dataclass
class WorkloadResult:
    cells: List[WorkloadCell] = field(default_factory=list)
    periods: List[str] = field(default_factory=list)
    unknown_assignments: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    @property
    def over_allocations(self) -> List[WorkloadCell]:
        return [c for c in self.cells if c.over_allocated]

    def cell(self, resource_id: str, period: str) -> Optional[WorkloadCell]:
        for c in self.cells:
            if c.resource_id == resource_id and c.period == period:
                return c
        return None


def _period_of(d: date, granularity: str) -> str:
    if granularity == "day":
        return d.isoformat()
    return cal_svc.week_key(d)


def compute_workload(
    assignments: List[ResourceAssignment],
    calendar: Calendar,
    *,
    horizon_start: Optional[date] = None,
    horizon_end: Optional[date] = None,
    granularity: str = "week",
    capacity_per_day: float = _CAPACITY_PER_WORKING_DAY,
    resource_leave: Optional[Dict[str, List[date]]] = None,
) -> WorkloadResult:
    """Reconcile assignment demand with calendar capacity per resource per period.

    ``resource_leave`` maps resource_id → non-working leave dates that reduce capacity
    (PM-06). ``horizon_*`` optionally clamp the reporting window.
    """
    result = WorkloadResult()
    leave = resource_leave or {}

    # Collect the working days each assignment touches.
    demand_by_key: Dict[tuple, float] = {}
    resource_names: Dict[str, str] = {}
    periods: set = set()

    for a in assignments:
        resource_names.setdefault(a.resource_id, a.resource_name or a.resource_id)
        if a.effort is None or a.start is None or a.finish is None:
            result.unknown_assignments.append(a.assignment_id)
            continue
        if a.finish < a.start:
            result.warnings.append(
                f"Assignment {a.assignment_id}: finish before start; skipped.")
            result.unknown_assignments.append(a.assignment_id)
            continue

        wdays = cal_svc.working_days_list(calendar, a.start, a.finish)
        if not wdays:
            result.warnings.append(
                f"Assignment {a.assignment_id}: no working days in window; skipped.")
            result.unknown_assignments.append(a.assignment_id)
            continue

        per_day = a.effort / len(wdays)          # even spread across working days
        for wd in wdays:
            if horizon_start and wd < horizon_start:
                continue
            if horizon_end and wd > horizon_end:
                continue
            period = _period_of(wd, granularity)
            periods.add(period)
            key = (a.resource_id, period)
            demand_by_key[key] = demand_by_key.get(key, 0.0) + per_day

    # Capacity per resource per period from calendar working days minus leave.
    if not periods and not result.unknown_assignments:
        return result

    # Establish the date span to size capacity buckets.
    all_starts = [a.start for a in assignments if a.start]
    all_finishes = [a.finish for a in assignments if a.finish]
    span_start = horizon_start or (min(all_starts) if all_starts else None)
    span_end = horizon_end or (max(all_finishes) if all_finishes else None)

    capacity_by_key: Dict[tuple, float] = {}
    if span_start and span_end and resource_names:
        working_days = cal_svc.working_days_list(calendar, span_start, span_end)
        for rid in resource_names:
            rleave = set(leave.get(rid, []))
            for wd in working_days:
                if wd in rleave:
                    continue
                period = _period_of(wd, granularity)
                periods.add(period)
                key = (rid, period)
                capacity_by_key[key] = capacity_by_key.get(key, 0.0) + capacity_per_day

    ordered_periods = sorted(periods)
    result.periods = ordered_periods
    for rid, rname in sorted(resource_names.items(), key=lambda kv: kv[1]):
        for period in ordered_periods:
            demand = round(demand_by_key.get((rid, period), 0.0), 3)
            capacity = round(capacity_by_key.get((rid, period), 0.0), 3)
            if demand == 0.0 and capacity == 0.0:
                continue
            result.cells.append(WorkloadCell(rid, rname, period, demand, capacity))

    return result
