"""
PMI session store — typed wrappers around st.session_state for the project-control
entities. Centralises key names so every page shares one source of truth.
"""
from datetime import date, timedelta
from typing import Dict, List, Optional

import streamlit as st

from ..domain.pmi_models import (
    ActualCost, BaselineSnapshot, Calendar, ChangeRequest, CostBaseline, Deliverable,
    Dependency, DependencyType, ForecastSnapshot, Issue, Milestone, Project,
    ProjectOutcome, ResourceAssignment, Risk, StatusSnapshot, Task, TaskPlan, TaskType,
    WorkPackage, new_id,
)

_PROJECT = "sv_project"
_OUTCOMES = "sv_outcomes"
_DELIVERABLES = "sv_deliverables"
_WORKPACKAGES = "sv_workpackages"
_TASKS = "sv_tasks"
_PLANS = "sv_plans"
_DEPS = "sv_dependencies"
_MILESTONES = "sv_milestones"
_CALENDAR = "sv_calendar"
_ASSIGNMENTS = "sv_assignments"
_RISKS = "sv_risks"
_ISSUES = "sv_issues"
_CHANGES = "sv_changes"
_BASELINES = "sv_baselines"
_STATUS_SNAPS = "sv_status_snapshots"
_FORECAST_SNAPS = "sv_forecast_snapshots"
_COST_BASELINES = "sv_cost_baselines"
_ACTUAL_COSTS = "sv_actual_costs"


# ── Project ──────────────────────────────────────────────────────────────────
def get_project() -> Optional[Project]:
    return st.session_state.get(_PROJECT)


def set_project(p: Project) -> None:
    st.session_state[_PROJECT] = p


def has_project() -> bool:
    return get_project() is not None


# ── Generic list helpers ─────────────────────────────────────────────────────
def _get_list(key: str) -> list:
    return st.session_state.get(key, [])


def _set_list(key: str, value: list) -> None:
    st.session_state[key] = value


def get_outcomes() -> List[ProjectOutcome]:
    return _get_list(_OUTCOMES)


def set_outcomes(v: List[ProjectOutcome]) -> None:
    _set_list(_OUTCOMES, v)


def get_deliverables() -> List[Deliverable]:
    return _get_list(_DELIVERABLES)


def set_deliverables(v: List[Deliverable]) -> None:
    _set_list(_DELIVERABLES, v)


def get_work_packages() -> List[WorkPackage]:
    return _get_list(_WORKPACKAGES)


def set_work_packages(v: List[WorkPackage]) -> None:
    _set_list(_WORKPACKAGES, v)


def get_tasks() -> List[Task]:
    return _get_list(_TASKS)


def set_tasks(v: List[Task]) -> None:
    _set_list(_TASKS, v)


def get_plans() -> Dict[str, TaskPlan]:
    return st.session_state.get(_PLANS, {})


def set_plans(v: Dict[str, TaskPlan]) -> None:
    st.session_state[_PLANS] = v


def get_dependencies() -> List[Dependency]:
    return _get_list(_DEPS)


def set_dependencies(v: List[Dependency]) -> None:
    _set_list(_DEPS, v)


def get_milestones() -> List[Milestone]:
    return _get_list(_MILESTONES)


def set_milestones(v: List[Milestone]) -> None:
    _set_list(_MILESTONES, v)


def get_calendar() -> Calendar:
    cal = st.session_state.get(_CALENDAR)
    if cal is None:
        cal = Calendar(name="Project")
        st.session_state[_CALENDAR] = cal
    return cal


def set_calendar(c: Calendar) -> None:
    st.session_state[_CALENDAR] = c


def get_assignments() -> List[ResourceAssignment]:
    return _get_list(_ASSIGNMENTS)


def set_assignments(v: List[ResourceAssignment]) -> None:
    _set_list(_ASSIGNMENTS, v)


def get_risks() -> List[Risk]:
    return _get_list(_RISKS)


def set_risks(v: List[Risk]) -> None:
    _set_list(_RISKS, v)


def get_issues() -> List[Issue]:
    return _get_list(_ISSUES)


def set_issues(v: List[Issue]) -> None:
    _set_list(_ISSUES, v)


def get_changes() -> List[ChangeRequest]:
    return _get_list(_CHANGES)


def set_changes(v: List[ChangeRequest]) -> None:
    _set_list(_CHANGES, v)


def get_cost_baselines() -> List[CostBaseline]:
    return _get_list(_COST_BASELINES)


def set_cost_baselines(v: List[CostBaseline]) -> None:
    _set_list(_COST_BASELINES, v)


def get_actual_costs() -> List[ActualCost]:
    return _get_list(_ACTUAL_COSTS)


def set_actual_costs(v: List[ActualCost]) -> None:
    _set_list(_ACTUAL_COSTS, v)


# ── Snapshots (append-only) ──────────────────────────────────────────────────
def get_baselines() -> List[BaselineSnapshot]:
    return _get_list(_BASELINES)


def add_baseline(b: BaselineSnapshot) -> None:
    _set_list(_BASELINES, _get_list(_BASELINES) + [b])


def get_status_snapshots() -> List[StatusSnapshot]:
    return _get_list(_STATUS_SNAPS)


def add_status_snapshot(s: StatusSnapshot) -> None:
    _set_list(_STATUS_SNAPS, _get_list(_STATUS_SNAPS) + [s])


def get_forecast_snapshots() -> List[ForecastSnapshot]:
    return _get_list(_FORECAST_SNAPS)


def add_forecast_snapshot(f: ForecastSnapshot) -> None:
    _set_list(_FORECAST_SNAPS, _get_list(_FORECAST_SNAPS) + [f])


# ── Synthetic demo (proposal §13: demo uses synthetic data only) ─────────────
def seed_sample_project(base_start: Optional[date] = None) -> None:
    """Populate a small, entirely synthetic project so the app is explorable without
    uploading real workbook data. Overwrites existing PMI state."""
    start = base_start or date.today()

    project = Project(
        name="Demo Project", objective="Illustrate the project-control layer",
        owner="Sample Owner", approach="Hybrid",
        product_goal="A usable planning demo",
    )
    project.dod.criteria = ["Code reviewed", "Tests pass", "Docs updated"]

    d1 = Deliverable(project_id=project.project_id, name="Release 1",
                     acceptance_criteria="All R1 stories accepted")
    wp1 = WorkPackage(project_id=project.project_id, deliverable_id=d1.deliverable_id,
                      name="Backend", owner="Sample Owner")
    wp2 = WorkPackage(project_id=project.project_id, deliverable_id=d1.deliverable_id,
                      name="Frontend", owner="Sample Owner")

    t1 = Task(project_id=project.project_id, work_package_id=wp1.work_package_id,
              summary="Design API", type=TaskType.FEATURE, order=1, status="In Progress",
              ready=True, acceptance_criteria="API spec approved")
    t2 = Task(project_id=project.project_id, work_package_id=wp1.work_package_id,
              summary="Implement API", type=TaskType.FEATURE, order=2, status="Backlog",
              ready=True)
    t3 = Task(project_id=project.project_id, work_package_id=wp2.work_package_id,
              summary="Build UI", type=TaskType.FEATURE, order=3, status="Backlog", ready=True)
    t4 = Task(project_id=project.project_id, work_package_id=wp2.work_package_id,
              summary="Integrate & test", type=TaskType.TASK, order=4, status="Backlog")

    plans = {
        t1.task_id: TaskPlan(task_id=t1.task_id, planned_effort=3, duration_days=3,
                             planned_start=start),
        t2.task_id: TaskPlan(task_id=t2.task_id, planned_effort=5, duration_days=5),
        t3.task_id: TaskPlan(task_id=t3.task_id, planned_effort=4, duration_days=4),
        t4.task_id: TaskPlan(task_id=t4.task_id, planned_effort=2, duration_days=2),
    }
    deps = [
        Dependency(predecessor_task_id=t1.task_id, successor_task_id=t2.task_id, type=DependencyType.FS),
        Dependency(predecessor_task_id=t2.task_id, successor_task_id=t4.task_id, type=DependencyType.FS),
        Dependency(predecessor_task_id=t3.task_id, successor_task_id=t4.task_id, type=DependencyType.FS),
    ]
    ms = [Milestone(project_id=project.project_id, work_package_id=wp1.work_package_id,
                    name="R1 feature complete",
                    planned_date=start + timedelta(days=21))]

    assignments = [
        ResourceAssignment(task_id=t1.task_id, resource_id="r-alice", resource_name="Alice",
                           effort=3, start=start, finish=start + timedelta(days=2), role="Dev"),
        ResourceAssignment(task_id=t2.task_id, resource_id="r-alice", resource_name="Alice",
                           effort=5, start=start + timedelta(days=3),
                           finish=start + timedelta(days=9), role="Dev"),
        ResourceAssignment(task_id=t3.task_id, resource_id="r-bob", resource_name="Bob",
                           effort=4, start=start, finish=start + timedelta(days=5), role="Dev"),
    ]

    set_project(project)
    set_deliverables([d1])
    set_work_packages([wp1, wp2])
    set_tasks([t1, t2, t3, t4])
    set_plans(plans)
    set_dependencies(deps)
    set_milestones(ms)
    set_assignments(assignments)
    set_calendar(Calendar(name="Project"))
    set_outcomes([ProjectOutcome(project_id=project.project_id,
                                 definition="Faster planning", measure="Cycle time",
                                 owner="Sample Owner")])
    set_risks([]); set_issues([]); set_changes([])
