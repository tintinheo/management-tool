"""
WBS / scope-hierarchy integrity (PM-02, FR-UP-02).

Validates that the project → deliverable → work-package → task tree has no orphans
and no cycles, and that every task traces to a scope parent. Returns structured
findings; it never mutates or "repairs" data.
"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..domain.pmi_models import Deliverable, Task, WorkPackage


@dataclass
class WbsFinding:
    severity: str      # "error" | "warning"
    code: str
    message: str
    entity_id: str = ""


@dataclass
class WbsValidationResult:
    findings: List[WbsFinding] = field(default_factory=list)

    @property
    def errors(self) -> List[WbsFinding]:
        return [f for f in self.findings if f.severity == "error"]

    @property
    def warnings(self) -> List[WbsFinding]:
        return [f for f in self.findings if f.severity == "warning"]

    @property
    def is_valid(self) -> bool:
        return not self.errors


def _detect_wp_cycles(work_packages: List[WorkPackage]) -> List[str]:
    """Return ids of work packages that participate in a parent-chain cycle."""
    by_id = {wp.work_package_id: wp for wp in work_packages}
    in_cycle: List[str] = []
    WHITE, GRAY, BLACK = 0, 1, 2
    color: Dict[str, int] = {wp.work_package_id: WHITE for wp in work_packages}

    def visit(node_id: str, stack: List[str]) -> None:
        color[node_id] = GRAY
        stack.append(node_id)
        parent = by_id[node_id].parent_id
        if parent and parent in by_id:
            if color.get(parent) == GRAY:
                # Everything from the parent onward in the stack forms the cycle.
                idx = stack.index(parent)
                for nid in stack[idx:]:
                    if nid not in in_cycle:
                        in_cycle.append(nid)
            elif color.get(parent) == WHITE:
                visit(parent, stack)
        color[node_id] = BLACK
        stack.pop()

    for wp in work_packages:
        if color[wp.work_package_id] == WHITE:
            visit(wp.work_package_id, [])
    return in_cycle


def validate_wbs(
    deliverables: List[Deliverable],
    work_packages: List[WorkPackage],
    tasks: List[Task],
    *,
    require_task_parent: bool = True,
) -> WbsValidationResult:
    """Validate scope-hierarchy integrity.

    ``require_task_parent`` maps to the FR-UP-02 policy that a task must trace to a
    scope parent; it is configurable because the policy still needs sign-off.
    """
    result = WbsValidationResult()
    del_ids = {d.deliverable_id for d in deliverables}
    wp_ids = {wp.work_package_id for wp in work_packages}

    # Work packages must point at an existing deliverable and parent.
    for wp in work_packages:
        if wp.deliverable_id and wp.deliverable_id not in del_ids:
            result.findings.append(WbsFinding(
                "error", "WP_ORPHAN_DELIVERABLE",
                f"Work package '{wp.name or wp.work_package_id}' references missing "
                f"deliverable '{wp.deliverable_id}'.", wp.work_package_id,
            ))
        if wp.parent_id and wp.parent_id not in wp_ids:
            result.findings.append(WbsFinding(
                "error", "WP_ORPHAN_PARENT",
                f"Work package '{wp.name or wp.work_package_id}' references missing "
                f"parent '{wp.parent_id}'.", wp.work_package_id,
            ))
        if wp.parent_id and wp.parent_id == wp.work_package_id:
            result.findings.append(WbsFinding(
                "error", "WP_SELF_PARENT",
                f"Work package '{wp.name or wp.work_package_id}' is its own parent.",
                wp.work_package_id,
            ))

    for wp_id in _detect_wp_cycles(work_packages):
        result.findings.append(WbsFinding(
            "error", "WP_CYCLE",
            f"Work package '{wp_id}' participates in a parent-chain cycle.", wp_id,
        ))

    # Tasks must trace to an existing work package.
    for t in tasks:
        if not t.work_package_id:
            if require_task_parent:
                result.findings.append(WbsFinding(
                    "error", "TASK_NO_PARENT",
                    f"Task '{t.summary or t.task_id}' has no work package.", t.task_id,
                ))
            else:
                result.findings.append(WbsFinding(
                    "warning", "TASK_NO_PARENT",
                    f"Task '{t.summary or t.task_id}' has no work package.", t.task_id,
                ))
        elif t.work_package_id not in wp_ids:
            result.findings.append(WbsFinding(
                "error", "TASK_ORPHAN",
                f"Task '{t.summary or t.task_id}' references missing work package "
                f"'{t.work_package_id}'.", t.task_id,
            ))

    return result
