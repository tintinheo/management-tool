"""Golden tests — conditional EVM guardrail (FC-06, §8.5)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from src.domain.pmi_models import ActualCost, CostBaseline
from src.pmi.forecast_evm import compute_evm


def test_disabled_without_data():
    res = compute_evm([], [], None)
    assert res.enabled is False
    assert len(res.missing) == 3
    assert res.earned_value is None


def test_disabled_without_progress():
    res = compute_evm([CostBaseline(work_package_id="WP1", approved_budget=100)],
                      [ActualCost(work_package_id="WP1", actual_cost=50)], None)
    assert res.enabled is False
    assert any("percent complete" in m.lower() for m in res.missing)


def test_enabled_full_computation():
    cb = [CostBaseline(work_package_id="WP1", approved_budget=100),
          CostBaseline(work_package_id="WP2", approved_budget=100)]
    ac = [ActualCost(work_package_id="WP1", actual_cost=50),
          ActualCost(work_package_id="WP2", actual_cost=60)]
    pct = {"WP1": 50, "WP2": 25}
    planned = {"WP1": 60, "WP2": 40}
    res = compute_evm(cb, ac, pct, planned)
    assert res.enabled is True
    assert res.budget_at_completion == 200
    assert res.actual_cost == 110
    assert res.earned_value == 75          # 100*0.5 + 100*0.25
    assert res.cost_variance == -35        # 75 - 110
    assert res.schedule_variance == -25    # 75 - 100
    assert res.spi == 0.75                 # 75 / 100
    assert round(res.cpi, 4) == 0.6818     # 75 / 110


def test_pv_withheld_without_planned():
    cb = [CostBaseline(work_package_id="WP1", approved_budget=100)]
    ac = [ActualCost(work_package_id="WP1", actual_cost=50)]
    res = compute_evm(cb, ac, {"WP1": 50})
    assert res.enabled is True
    assert res.planned_value is None
    assert res.schedule_variance is None
    assert any("PV/SV/SPI withheld" in w for w in res.warnings)


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
