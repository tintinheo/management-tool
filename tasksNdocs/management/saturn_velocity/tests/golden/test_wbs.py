"""Golden tests — WBS / scope-hierarchy integrity (PM-02)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from src.domain.pmi_models import Deliverable, Task, WorkPackage
from src.pmi.wbs import validate_wbs


def _valid_set():
    d = Deliverable(deliverable_id="D1", name="Rel 1")
    wp = WorkPackage(work_package_id="WP1", deliverable_id="D1", name="Backend")
    t = Task(task_id="T1", work_package_id="WP1", summary="Do it")
    return [d], [wp], [t]


def test_valid_hierarchy():
    d, wp, t = _valid_set()
    res = validate_wbs(d, wp, t)
    assert res.is_valid
    assert res.errors == []


def test_task_without_parent_errors():
    d, wp, _ = _valid_set()
    orphan = Task(task_id="T9", summary="No parent")
    res = validate_wbs(d, wp, [orphan])
    assert not res.is_valid
    assert any(f.code == "TASK_NO_PARENT" for f in res.errors)


def test_task_without_parent_warns_when_not_required():
    d, wp, _ = _valid_set()
    orphan = Task(task_id="T9", summary="No parent")
    res = validate_wbs(d, wp, [orphan], require_task_parent=False)
    assert res.is_valid
    assert any(f.code == "TASK_NO_PARENT" for f in res.warnings)


def test_task_orphan_missing_wp():
    d, wp, _ = _valid_set()
    t = Task(task_id="T2", work_package_id="WP_MISSING", summary="x")
    res = validate_wbs(d, wp, [t])
    assert any(f.code == "TASK_ORPHAN" for f in res.errors)


def test_wp_orphan_missing_deliverable():
    wp = WorkPackage(work_package_id="WP1", deliverable_id="D_MISSING", name="x")
    res = validate_wbs([], [wp], [])
    assert any(f.code == "WP_ORPHAN_DELIVERABLE" for f in res.errors)


def test_wp_missing_parent():
    wp = WorkPackage(work_package_id="WP1", parent_id="WP_MISSING", name="x")
    res = validate_wbs([], [wp], [])
    assert any(f.code == "WP_ORPHAN_PARENT" for f in res.errors)


def test_wp_self_parent():
    wp = WorkPackage(work_package_id="WP1", parent_id="WP1", name="x")
    res = validate_wbs([], [wp], [])
    assert any(f.code in ("WP_SELF_PARENT", "WP_CYCLE") for f in res.errors)


def test_wp_cycle_detected():
    wp1 = WorkPackage(work_package_id="WP1", parent_id="WP2", name="a")
    wp2 = WorkPackage(work_package_id="WP2", parent_id="WP1", name="b")
    res = validate_wbs([], [wp1, wp2], [])
    assert any(f.code == "WP_CYCLE" for f in res.errors)


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    p = f = 0
    for t in TESTS:
        try:
            t(); print(f"  PASS {t.__name__}"); p += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {t.__name__}: {exc}"); f += 1
    print(f"\n{p} passed, {f} failed.")
