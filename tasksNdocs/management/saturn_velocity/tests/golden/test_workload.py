"""Golden tests — workload demand vs capacity (WL-01 … WL-04)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from datetime import date
from src.domain.pmi_models import Calendar, ResourceAssignment
from src.pmi.workload import compute_workload

MON = date(2026, 8, 3)


def _assignments():
    return [
        # Alice: 3 pd over 8/3-8/5 (3 wd) => 1.0/day
        ResourceAssignment(assignment_id="a1", task_id="A", resource_id="r-alice",
                           resource_name="Alice", effort=3, start=MON, finish=date(2026, 8, 5)),
        # Alice: 5 pd over 8/6-8/12 (5 wd) => 1.0/day
        ResourceAssignment(assignment_id="a2", task_id="B", resource_id="r-alice",
                           resource_name="Alice", effort=5, start=date(2026, 8, 6),
                           finish=date(2026, 8, 12)),
        # Bob: 4 pd over 8/3-8/6 (4 wd) => 1.0/day
        ResourceAssignment(assignment_id="a3", task_id="C", resource_id="r-bob",
                           resource_name="Bob", effort=4, start=MON, finish=date(2026, 8, 6)),
    ]


def _demand_total(res, resource_id):
    return round(sum(c.demand for c in res.cells if c.resource_id == resource_id), 3)


def test_demand_totals():
    res = compute_workload(_assignments(), Calendar(name="MF"))
    assert _demand_total(res, "r-alice") == 8.0
    assert _demand_total(res, "r-bob") == 4.0


def test_no_unknown_when_complete():
    res = compute_workload(_assignments(), Calendar(name="MF"))
    assert res.unknown_assignments == []


def test_unknown_assignment_not_counted():
    a = _assignments()
    a.append(ResourceAssignment(assignment_id="a4", task_id="D", resource_id="r-carol",
                                resource_name="Carol", effort=None, start=None, finish=None))
    res = compute_workload(a, Calendar(name="MF"))
    assert "a4" in res.unknown_assignments
    assert _demand_total(res, "r-carol") == 0.0


def test_capacity_equals_demand_not_over_allocated():
    res = compute_workload(_assignments(), Calendar(name="MF"))
    # Alice demand equals working-day capacity, so never over-allocated.
    assert res.over_allocations == []


def test_over_allocation_detected():
    a = [
        ResourceAssignment(assignment_id="x1", task_id="A", resource_id="r-alice",
                           resource_name="Alice", effort=15, start=MON, finish=date(2026, 8, 5)),
    ]
    res = compute_workload(a, Calendar(name="MF"))
    overs = res.over_allocations
    assert overs, "expected an over-allocation"
    assert all(c.demand > c.capacity for c in overs)


def test_reversed_window_is_unknown():
    a = [ResourceAssignment(assignment_id="r1", task_id="A", resource_id="r-x",
                            resource_name="X", effort=2, start=date(2026, 8, 6), finish=MON)]
    res = compute_workload(a, Calendar(name="MF"))
    assert "r1" in res.unknown_assignments


def test_leave_reduces_capacity():
    a = [ResourceAssignment(assignment_id="l1", task_id="A", resource_id="r-alice",
                            resource_name="Alice", effort=2, start=MON, finish=date(2026, 8, 4))]
    # Alice on leave for the two working days -> capacity 0, demand 2 -> over-allocated.
    res = compute_workload(a, Calendar(name="MF"),
                           resource_leave={"r-alice": [MON, date(2026, 8, 4)]})
    assert res.over_allocations


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
