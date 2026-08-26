"""
Scrumban flow metrics and historical forecast (NF-01 … NF-08, FC-03).

Flow is measured from the Kanban started/finished points, mapped here to each task's
actual start and actual finish. Metrics reconcile to those timestamps only. A Service
Level Expectation is reported solely when it has been configured (NF-06), and the
throughput forecast refuses to run without completed history (business rule §8.4).
"""
from dataclasses import dataclass, field
from datetime import date, timedelta
from math import ceil
from typing import Dict, List, Optional

from ..domain.pmi_models import (
    Calendar, DefinitionOfWorkflow, StatusUpdate, Task,
)
from . import calendar_service as cal_svc


@dataclass
class FlowItem:
    task_id: str
    summary: str
    started: Optional[date]
    finished: Optional[date]


@dataclass
class AgingItem:
    task_id: str
    summary: str
    started: date
    age_days: int
    sle_breach: Optional[bool]   # None when no SLE configured


@dataclass
class FlowMetrics:
    as_of_date: Optional[date]
    wip: int = 0
    throughput_by_period: Dict[str, int] = field(default_factory=dict)
    avg_cycle_time_days: Optional[float] = None
    cycle_times: List[tuple] = field(default_factory=list)   # (task_id, days)
    aging: List[AgingItem] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


@dataclass
class FlowForecast:
    status: str                       # "ok" | "insufficient_data"
    as_of_date: Optional[date] = None
    method: str = "throughput-v1"
    remaining_items: int = 0
    window_periods: int = 0
    sampled_throughput: List[int] = field(default_factory=list)
    avg_throughput: Optional[float] = None
    periods_to_complete: Optional[float] = None
    forecast_date: Optional[date] = None
    missing: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


def build_flow_items(tasks: List[Task], status_updates: List[StatusUpdate]) -> List[FlowItem]:
    by_task = {s.task_id: s for s in status_updates}
    items: List[FlowItem] = []
    for t in tasks:
        upd = by_task.get(t.task_id)
        items.append(FlowItem(
            task_id=t.task_id,
            summary=t.summary,
            started=upd.actual_start if upd else None,
            finished=upd.actual_finish if upd else None,
        ))
    return items


def _elapsed_days(calendar: Optional[Calendar], start: date, end: date) -> int:
    if calendar is not None:
        return cal_svc.working_days_between(calendar, start, end)
    return (end - start).days + 1


def compute_flow_metrics(
    items: List[FlowItem],
    as_of_date: date,
    *,
    calendar: Optional[Calendar] = None,
    workflow: Optional[DefinitionOfWorkflow] = None,
    granularity: str = "week",
) -> FlowMetrics:
    m = FlowMetrics(as_of_date=as_of_date)
    sle_days = workflow.sle_days if workflow else None

    cycle_total = 0.0
    cycle_n = 0
    for it in items:
        started = it.started
        finished = it.finished
        # WIP: started on/before as-of and not yet finished by as-of.
        if started and started <= as_of_date and (finished is None or finished > as_of_date):
            age = _elapsed_days(calendar, started, as_of_date)
            m.wip += 1
            m.aging.append(AgingItem(
                task_id=it.task_id, summary=it.summary, started=started, age_days=age,
                sle_breach=(age > sle_days) if sle_days is not None else None,
            ))
        # Throughput + cycle time for items finished on/before as-of.
        if finished and finished <= as_of_date and started and started <= finished:
            period = finished.isoformat() if granularity == "day" else cal_svc.week_key(finished)
            m.throughput_by_period[period] = m.throughput_by_period.get(period, 0) + 1
            ctime = _elapsed_days(calendar, started, finished)
            m.cycle_times.append((it.task_id, ctime))
            cycle_total += ctime
            cycle_n += 1

    m.aging.sort(key=lambda a: a.age_days, reverse=True)
    m.avg_cycle_time_days = round(cycle_total / cycle_n, 2) if cycle_n else None
    if sle_days is None and m.wip:
        m.warnings.append("No Service Level Expectation configured; SLE risk not evaluated (NF-06).")
    return m


def _period_series(as_of: date, window_periods: int, granularity: str) -> List[str]:
    keys: List[str] = []
    step = 1 if granularity == "day" else 7
    for i in range(window_periods):
        d = as_of - timedelta(days=step * i)
        keys.append(d.isoformat() if granularity == "day" else cal_svc.week_key(d))
    return list(dict.fromkeys(keys))   # preserve order, de-dup


def forecast_completion(
    items: List[FlowItem],
    remaining_items: int,
    as_of_date: date,
    *,
    window_periods: int = 6,
    granularity: str = "week",
) -> FlowForecast:
    """Throughput-based completion forecast (FC-03).

    Uses average throughput over the trailing ``window_periods``. Returns
    ``insufficient_data`` when there is no completed history or throughput is zero.
    """
    fc = FlowForecast(status="ok", as_of_date=as_of_date,
                      remaining_items=remaining_items, window_periods=window_periods)

    if remaining_items <= 0:
        fc.missing.append("No remaining items to forecast.")
    metrics = compute_flow_metrics(items, as_of_date, granularity=granularity)
    if not metrics.throughput_by_period:
        fc.missing.append("No completed work-item history in the window.")

    if fc.missing:
        fc.status = "insufficient_data"
        return fc

    window_keys = set(_period_series(as_of_date, window_periods, granularity))
    sample = [cnt for per, cnt in metrics.throughput_by_period.items() if per in window_keys]
    if not sample:
        # Fall back to the whole history if none of it lands in the nominal window.
        sample = list(metrics.throughput_by_period.values())
        fc.warnings.append("No completions inside the nominal window; used full history.")

    fc.sampled_throughput = sorted(sample, reverse=True)
    effective_periods = max(len(sample), 1)
    avg = sum(sample) / effective_periods
    fc.avg_throughput = round(avg, 3)

    if avg <= 0:
        fc.status = "insufficient_data"
        fc.missing.append("Average throughput is zero; cannot forecast.")
        return fc

    periods = ceil(remaining_items / avg)
    fc.periods_to_complete = round(remaining_items / avg, 2)
    step = 1 if granularity == "day" else 7
    fc.forecast_date = as_of_date + timedelta(days=step * periods)
    if sum(sample) < 3:
        fc.warnings.append("Small sample (<3 completed items); treat forecast as low confidence.")
    return fc
