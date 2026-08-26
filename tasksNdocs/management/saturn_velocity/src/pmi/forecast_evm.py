"""
Conditional Earned Value Management (FC-06, FR-UP-11, business rule §8.5).

EVM stays disabled until an approved cost baseline, actual cost and a progress
measure are all supplied. When disabled the engine returns an explicit missing-data
list and computes nothing — story points, velocity and capacity are never turned into
PV/EV/AC (the proposal forbids synthesizing cost).
"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..domain.pmi_models import ActualCost, CostBaseline


@dataclass
class EvmResult:
    enabled: bool
    missing: List[str] = field(default_factory=list)
    planned_value: Optional[float] = None       # PV / BCWS
    earned_value: Optional[float] = None         # EV / BCWP
    actual_cost: Optional[float] = None          # AC / ACWP
    budget_at_completion: Optional[float] = None  # BAC
    schedule_variance: Optional[float] = None    # SV = EV - PV
    cost_variance: Optional[float] = None        # CV = EV - AC
    spi: Optional[float] = None                  # SPI = EV / PV
    cpi: Optional[float] = None                  # CPI = EV / AC
    estimate_at_completion: Optional[float] = None  # EAC = BAC / CPI
    estimate_to_complete: Optional[float] = None    # ETC = EAC - AC
    variance_at_completion: Optional[float] = None  # VAC = BAC - EAC
    warnings: List[str] = field(default_factory=list)


def compute_evm(
    cost_baselines: List[CostBaseline],
    actual_costs: List[ActualCost],
    percent_complete_by_wp: Optional[Dict[str, float]],
    planned_percent_by_wp: Optional[Dict[str, float]] = None,
) -> EvmResult:
    """Compute EVM only when every prerequisite exists.

    Parameters
    ----------
    percent_complete_by_wp : measured physical percent complete (0..100) per work
        package — the approved progress-measurement input (§8.5).
    planned_percent_by_wp : planned percent complete per work package as of the status
        date; required for PV/SV/SPI. Without it, schedule-based metrics stay ``None``.
    """
    result = EvmResult(enabled=False)

    if not cost_baselines:
        result.missing.append("Approved cost baseline (work-package budgets).")
    if not actual_costs:
        result.missing.append("Actual cost records.")
    if not percent_complete_by_wp:
        result.missing.append("Approved progress-measurement (physical percent complete).")

    if result.missing:
        return result   # disabled — compute nothing

    result.enabled = True

    budget_by_wp: Dict[str, float] = {}
    for cb in cost_baselines:
        budget_by_wp[cb.work_package_id] = budget_by_wp.get(cb.work_package_id, 0.0) + cb.approved_budget
    ac_by_wp: Dict[str, float] = {}
    for ac in actual_costs:
        ac_by_wp[ac.work_package_id] = ac_by_wp.get(ac.work_package_id, 0.0) + ac.actual_cost

    bac = sum(budget_by_wp.values())
    ac_total = sum(ac_by_wp.values())
    ev_total = 0.0
    for wp, budget in budget_by_wp.items():
        pct = percent_complete_by_wp.get(wp)
        if pct is None:
            result.warnings.append(f"Work package '{wp}' has no percent-complete; EV excludes it.")
            continue
        ev_total += budget * (pct / 100.0)

    result.budget_at_completion = round(bac, 2)
    result.actual_cost = round(ac_total, 2)
    result.earned_value = round(ev_total, 2)
    result.cost_variance = round(ev_total - ac_total, 2)
    result.cpi = round(ev_total / ac_total, 4) if ac_total else None

    if planned_percent_by_wp:
        pv_total = 0.0
        for wp, budget in budget_by_wp.items():
            planned_pct = planned_percent_by_wp.get(wp)
            if planned_pct is not None:
                pv_total += budget * (planned_pct / 100.0)
        result.planned_value = round(pv_total, 2)
        result.schedule_variance = round(ev_total - pv_total, 2)
        result.spi = round(ev_total / pv_total, 4) if pv_total else None
    else:
        result.warnings.append("No planned percent-complete supplied; PV/SV/SPI withheld.")

    if result.cpi:
        eac = bac / result.cpi
        result.estimate_at_completion = round(eac, 2)
        result.estimate_to_complete = round(eac - ac_total, 2)
        result.variance_at_completion = round(bac - eac, 2)
    return result
