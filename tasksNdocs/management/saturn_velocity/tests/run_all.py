"""Run all no-argument ``test_*`` functions without an external test framework."""
from __future__ import annotations

import importlib.util
import inspect
import sys
import traceback
from pathlib import Path


ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))


def main() -> int:
    passed = 0
    failed = 0
    files = sorted((ROOT / "tests" / "golden").glob("test_*.py"))
    for file_path in files:
        module_name = f"golden_{file_path.stem}"
        spec = importlib.util.spec_from_file_location(module_name, file_path)
        if spec is None or spec.loader is None:
            print(f"FAIL {file_path.name}: unable to load module")
            failed += 1
            continue
        module = importlib.util.module_from_spec(spec)
        try:
            spec.loader.exec_module(module)
        except Exception:  # noqa: BLE001 - runner must report import failures
            print(f"FAIL {file_path.name}: module import failed")
            traceback.print_exc()
            failed += 1
            continue
        tests = [
            (name, value)
            for name, value in inspect.getmembers(module, inspect.isfunction)
            if name.startswith("test_") and len(inspect.signature(value).parameters) == 0
        ]
        for name, test in tests:
            try:
                test()
                print(f"PASS {file_path.name}::{name}")
                passed += 1
            except Exception:  # noqa: BLE001 - runner must continue and report all failures
                print(f"FAIL {file_path.name}::{name}")
                traceback.print_exc()
                failed += 1
    print(f"RESULT {passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
