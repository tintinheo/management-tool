"""
Calculation engine — pure functions implementing BR-01 through BR-10.

All formulas are derived from WB-s192-CALC (sheet s192, cells C44:R44 and C47:L52).
Each function is versioned via RuleSet.rule_version so snapshots remain deterministic.
"""
from datetime import date, timedelta
from typing import List, Optional

from ..domain.models import (
    Sprint, Scenario, RuleSet,
    CalculationResult, ResourceResult, ResourceType,
)


def networkdays(start: date, end: date, holiday_count: int = 0) -> float:
    """
    Count Monday–Friday days between start and end (inclusive),
    then subtract public_holiday_count.

    Mirrors Excel NETWORKDAYS(start, end, holidays) where holidays is a
    contiguous-count approximation (as used in WB-s192-META: Public Holiday = 0).
    """
    if end < start:
        return 0.0
    count = 0
    d = start
    while d <= end:
        if d.weekday() < 5:
            count += 1
        d += timedelta(days=1)
    return float(max(0, count - holiday_count))


def calculate(
    sprint: Sprint,
    scenario: Scenario,
    ruleset: RuleSet,
    as_of_date: Optional[date] = None,
) -> CalculationResult:
    """
    Compute all capacity metrics for one scenario.

    Parameters
    ----------
    sprint      : Sprint metadata (dates, buffer, backup, fixed_day_deduction).
    scenario    : Resources with per-resource leave/OT values.
    ruleset     : Versioned rule parameters (hours_per_day, fixed_day_deduction).
    as_of_date  : Explicit snapshot date for BR-02; defaults to date.today().
                  Using an explicit date makes snapshots deterministic (TR-03).
    """
    as_of = as_of_date or date.today()
    warnings: List[str] = []

    # ── BR-01: dev_days ──────────────────────────────────────────────────────
    # NETWORKDAYS(Start, Dev End, PublicHolidays) - fixed_day_deduction - backup
    # fixed_day_deduction=3 is an undocumented constant (R-04 / CRITICAL DATA MISSING).
    nd = networkdays(sprint.start_date, sprint.development_end_date, sprint.public_holidays)
    dev_days = nd - ruleset.fixed_day_deduction - sprint.backup
    if dev_days <= 0:
        warnings.append(
            f"ERROR: dev_days = {dev_days:.2f} ≤ 0. "
            "Check sprint dates, public_holidays, backup and fixed_day_deduction."
        )

    # ── BR-02: remaining_dev_days ────────────────────────────────────────────
    # NETWORKDAYS(as_of_date, Dev End)
    # Excel used TODAY(); replaced with explicit as_of_date for snapshot determinism.
    if as_of <= sprint.development_end_date:
        remaining_dev_days = networkdays(as_of, sprint.development_end_date)
    else:
        remaining_dev_days = 0.0
        warnings.append(
            "WARNING: as_of_date is past development_end_date; remaining_dev_days = 0."
        )

    # ── BR-10: buffer_days ───────────────────────────────────────────────────
    buffer_days = sprint.buffer * dev_days

    # ── Per-resource capacity (BR-03 … BR-06) ────────────────────────────────
    resource_results: List[ResourceResult] = []
    for sr in scenario.resources:
        leave = sr.leave_days  # BR-03: already summed at scenario level
        if leave > max(dev_days, 0.0):
            warnings.append(
                f"WARNING: {sr.display_name} — leave_days ({leave}) > dev_days ({dev_days:.2f})."
            )

        ot_d = sr.ot_days
        vp = sr.v_percent
        vel = sr.velocity
        effective = max(0.0, dev_days - leave)

        # BR-04: FTE without OT
        fte_no_ot = vel * effective * vp
        # BR-05: Full velocity (with OT)
        full_v = vel * max(0.0, effective + ot_d) * vp
        # BR-06: Buffered V and V_OT
        v = fte_no_ot * (1.0 - sprint.buffer)
        v_ot = full_v * (1.0 - sprint.buffer)

        resource_results.append(
            ResourceResult(
                resource_id=sr.resource_id,
                display_name=sr.display_name,
                type=sr.type,
                leave_days=leave,
                ot_days=ot_d,
                fte_no_ot=round(fte_no_ot, 3),
                full_v=round(full_v, 3),
                v=round(v, 3),
                v_ot=round(v_ot, 3),
            )
        )

    # ── BR-07: Team totals ───────────────────────────────────────────────────
    dev_res = [r for r in resource_results if r.type == ResourceType.DEV]
    qc_res = [r for r in resource_results if r.type == ResourceType.QC]

    full_dev_v = round(sum(r.full_v for r in dev_res), 3)
    dev_v = round(sum(r.v for r in dev_res), 3)
    full_qc_v = round(sum(r.full_v for r in qc_res), 3)
    qc_v = round(sum(r.v for r in qc_res), 3)

    # ── BR-08: Team velocity (Biz) = MIN(Dev V, QC V) ───────────────────────
    if dev_res and qc_res:
        team_velocity_biz = round(min(dev_v, qc_v), 3)
    elif dev_res:
        team_velocity_biz = dev_v
        warnings.append("WARNING: No QC resources — Team Velocity (Biz) = Dev V only.")
    elif qc_res:
        team_velocity_biz = qc_v
        warnings.append("WARNING: No Dev resources — Team Velocity (Biz) = QC V only.")
    else:
        team_velocity_biz = 0.0
        warnings.append("WARNING: No Dev or QC resources found in scenario.")

    # ── BR-09: QC - Dev ──────────────────────────────────────────────────────
    qc_minus_dev = round(qc_v - dev_v, 3)

    return CalculationResult(
        dev_days=round(dev_days, 3),
        remaining_dev_days=round(remaining_dev_days, 3),
        buffer_days=round(buffer_days, 3),
        resource_results=resource_results,
        full_dev_v=full_dev_v,
        dev_v=dev_v,
        full_qc_v=full_qc_v,
        qc_v=qc_v,
        team_velocity_biz=team_velocity_biz,
        qc_minus_dev=qc_minus_dev,
        as_of_date=as_of,
        rule_version=ruleset.rule_version,
        warnings=warnings,
    )
