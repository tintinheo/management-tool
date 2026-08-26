"""
Working-calendar service (PM-06).

Pure working-day arithmetic used by scheduling (FC-01) and workload (WL-01).
A day is a working day when its weekday is in ``working_weekdays`` and it is not a
holiday or an exception date. Nothing here invents holidays or leave — callers pass
an explicit :class:`Calendar`.
"""
from datetime import date, timedelta
from typing import Iterable, List, Optional

from ..domain.pmi_models import Calendar


def is_working_day(cal: Calendar, d: date) -> bool:
    if d.weekday() not in cal.working_weekdays:
        return False
    if d in cal.holidays:
        return False
    if d in cal.exceptions:
        return False
    return True


def next_working_day(cal: Calendar, d: date) -> date:
    """First working day on or after ``d``."""
    cur = d
    # Bound the loop so a mis-configured calendar (no working weekdays) cannot hang.
    for _ in range(3660):
        if is_working_day(cal, cur):
            return cur
        cur += timedelta(days=1)
    raise ValueError("Calendar has no working days within a 10-year window.")


def working_days_between(cal: Calendar, start: date, end: date) -> int:
    """Inclusive count of working days in ``[start, end]``. 0 when end < start."""
    if end < start:
        return 0
    count = 0
    cur = start
    while cur <= end:
        if is_working_day(cal, cur):
            count += 1
        cur += timedelta(days=1)
    return count


def add_working_days(cal: Calendar, start: date, n: int) -> date:
    """Return the working day ``n`` working days after ``start``.

    ``n == 0`` returns the next working day on/after ``start`` itself. Used to walk a
    finish date forward from a start date.
    """
    cur = next_working_day(cal, start)
    remaining = n
    while remaining > 0:
        cur += timedelta(days=1)
        cur = next_working_day(cal, cur)
        remaining -= 1
    return cur


def end_after_duration(cal: Calendar, start: date, duration_days: float) -> date:
    """Finish date for a task of ``duration_days`` working days starting at ``start``.

    Duration is inclusive: a 1-day task finishes on its start working day. Fractional
    durations are rounded up to whole working days (no partial-day scheduling invented).
    """
    dur = max(1, int(duration_days) if float(duration_days).is_integer() else int(duration_days) + 1)
    return add_working_days(cal, start, dur - 1)


def working_days_list(cal: Calendar, start: date, end: date) -> List[date]:
    """All working days in ``[start, end]`` inclusive."""
    if end < start:
        return []
    out: List[date] = []
    cur = start
    while cur <= end:
        if is_working_day(cal, cur):
            out.append(cur)
        cur += timedelta(days=1)
    return out


def week_key(d: date) -> str:
    """ISO year-week label (e.g. ``2026-W31``) used as a workload period bucket."""
    iso = d.isocalendar()
    return f"{iso[0]}-W{iso[1]:02d}"
