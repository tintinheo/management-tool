"""
PMI / Scrum / Scrumban domain models (Upgrade Proposal §9).

These entities extend the capacity models in ``models.py`` with the project-control
layer required by the proposal: project → deliverable → work package → task →
plan/assignment/status/forecast, plus risk/issue/change and Scrumban workflow.

Design guardrails carried from the proposal:
  * No invented business values — optional fields stay ``None`` until supplied.
  * Progress/forecast carry an explicit ``as_of_date`` (never ``TODAY()``).
  * Snapshots are immutable payloads that can be replayed.
"""
from dataclasses import dataclass, field
from datetime import date
from enum import Enum
from typing import Optional, List, Dict, Any
import uuid


def new_id(prefix: str) -> str:
    """Short, human-readable, collision-resistant id such as ``TASK-1a2b3c4d``."""
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


# ── Enumerations ─────────────────────────────────────────────────────────────

class GovernanceStatus(str, Enum):
    DRAFT = "Draft"
    PROPOSED = "Proposed"
    APPROVED = "Approved"
    ON_HOLD = "On Hold"
    CLOSED = "Closed"


class TaskType(str, Enum):
    FEATURE = "Feature"
    BUG = "Bug"
    TASK = "Task"
    SPIKE = "Spike"
    CHORE = "Chore"


class DependencyType(str, Enum):
    FS = "FS"  # finish-to-start
    SS = "SS"  # start-to-start
    FF = "FF"  # finish-to-finish
    SF = "SF"  # start-to-finish


class MilestoneStatus(str, Enum):
    PLANNED = "Planned"
    AT_RISK = "At Risk"
    ACHIEVED = "Achieved"
    MISSED = "Missed"


class RiskStatus(str, Enum):
    OPEN = "Open"
    MITIGATING = "Mitigating"
    CLOSED = "Closed"


class IssueStatus(str, Enum):
    OPEN = "Open"
    IN_PROGRESS = "In Progress"
    RESOLVED = "Resolved"
    CLOSED = "Closed"


class ChangeStatus(str, Enum):
    PROPOSED = "Proposed"
    APPROVED = "Approved"
    REJECTED = "Rejected"
    DEFERRED = "Deferred"


# ── Scrumban workflow (NF-01 Definition of Workflow) ─────────────────────────

@dataclass
class WorkflowState:
    """One column of the Kanban/Scrumban board.

    ``is_started`` / ``is_finished`` are the Kanban started/finished points used by
    flow metrics (NF-03). ``wip_limit`` is an optional explicit WIP control (NF-02).
    """
    name: str
    order: int
    is_started: bool = False
    is_finished: bool = False
    wip_limit: Optional[int] = None


def default_workflow_states() -> List[WorkflowState]:
    """A sensible default DoW. Real states/WIP/SLE require sign-off (NF-01)."""
    return [
        WorkflowState("Backlog", 0, is_started=False, is_finished=False),
        WorkflowState("Ready", 1, is_started=False, is_finished=False),
        WorkflowState("In Progress", 2, is_started=True, is_finished=False, wip_limit=None),
        WorkflowState("In Review", 3, is_started=True, is_finished=False, wip_limit=None),
        WorkflowState("Done", 4, is_started=True, is_finished=True),
    ]


@dataclass
class DefinitionOfWorkflow:
    version: str = "wf-v1"
    states: List[WorkflowState] = field(default_factory=default_workflow_states)
    policies: List[str] = field(default_factory=list)
    # Service Level Expectation — only published when both are supplied (NF-06).
    sle_days: Optional[int] = None
    sle_probability: Optional[float] = None

    def state_names(self) -> List[str]:
        return [s.name for s in sorted(self.states, key=lambda x: x.order)]

    def started_states(self) -> List[str]:
        return [s.name for s in self.states if s.is_started and not s.is_finished]

    def finished_states(self) -> List[str]:
        return [s.name for s in self.states if s.is_finished]

    def get_state(self, name: str) -> Optional[WorkflowState]:
        for s in self.states:
            if s.name == name:
                return s
        return None


# ── Scrum artifacts (IMP-01, IMP-02) ─────────────────────────────────────────

@dataclass
class DefinitionOfDone:
    version: str = "dod-v1"
    criteria: List[str] = field(default_factory=list)


# ── Project / scope hierarchy (PM-01, PM-02) ─────────────────────────────────

@dataclass
class Project:
    project_id: str = field(default_factory=lambda: new_id("PROJ"))
    name: str = ""
    objective: str = ""
    approach: str = "Hybrid"          # Predictive / Adaptive / Hybrid (PMP ECO)
    owner: str = ""
    governance_status: GovernanceStatus = GovernanceStatus.DRAFT
    product_goal: str = ""            # Scrum Product Goal (IMP-01)
    dod: DefinitionOfDone = field(default_factory=DefinitionOfDone)
    workflow: DefinitionOfWorkflow = field(default_factory=DefinitionOfWorkflow)


@dataclass
class ProjectOutcome:
    outcome_id: str = field(default_factory=lambda: new_id("OUT"))
    project_id: str = ""
    definition: str = ""
    measure: str = ""
    owner: str = ""
    review_status: str = "Proposed"


@dataclass
class Deliverable:
    deliverable_id: str = field(default_factory=lambda: new_id("DEL"))
    project_id: str = ""
    name: str = ""
    acceptance_criteria: str = ""
    status: str = "Planned"


@dataclass
class WorkPackage:
    work_package_id: str = field(default_factory=lambda: new_id("WP"))
    project_id: str = ""
    deliverable_id: Optional[str] = None
    parent_id: Optional[str] = None   # WBS parent work-package (tree)
    name: str = ""
    owner: str = ""
    status: str = "Planned"


# ── Task & schedule (PM-03, PM-04) ───────────────────────────────────────────

@dataclass
class Task:
    task_id: str = field(default_factory=lambda: new_id("TASK"))
    project_id: str = ""
    work_package_id: Optional[str] = None
    sprint_id: Optional[str] = None
    summary: str = ""
    type: TaskType = TaskType.TASK
    priority: str = ""
    order: int = 0
    status: str = "Backlog"           # current workflow-state name
    acceptance_criteria: str = ""
    ready: bool = False               # readiness policy (IMP-03)
    assignee_resource_id: Optional[str] = None
    story_points: Optional[float] = None
    added_after_sprint_start: bool = False
    change_reason: str = ""           # required if added_after_sprint_start (IMP-06)
    source_id: str = ""               # external ticket id (NF-10 idempotent import)


@dataclass
class TaskPlan:
    """Planned schedule/effort for a task. Actuals live in StatusUpdate (PM-08)."""
    task_id: str = ""
    planned_effort: Optional[float] = None
    effort_unit: str = "person-days"
    planned_start: Optional[date] = None
    planned_finish: Optional[date] = None
    duration_days: Optional[float] = None
    calendar_id: Optional[str] = None


@dataclass
class Dependency:
    dependency_id: str = field(default_factory=lambda: new_id("DEP"))
    predecessor_task_id: str = ""
    successor_task_id: str = ""
    type: DependencyType = DependencyType.FS
    lag_days: float = 0.0


@dataclass
class Milestone:
    milestone_id: str = field(default_factory=lambda: new_id("MS"))
    project_id: str = ""
    work_package_id: Optional[str] = None
    name: str = ""
    planned_date: Optional[date] = None
    actual_date: Optional[date] = None
    forecast_date: Optional[date] = None
    status: MilestoneStatus = MilestoneStatus.PLANNED


# ── Resources, calendar, assignment (PM-05, PM-06) ───────────────────────────

@dataclass
class Calendar:
    calendar_id: str = field(default_factory=lambda: new_id("CAL"))
    name: str = "Default"
    # 0=Mon … 6=Sun. Default working week Mon–Fri.
    working_weekdays: List[int] = field(default_factory=lambda: [0, 1, 2, 3, 4])
    holidays: List[date] = field(default_factory=list)
    exceptions: List[date] = field(default_factory=list)  # extra non-working days


@dataclass
class ResourceAssignment:
    assignment_id: str = field(default_factory=lambda: new_id("ASG"))
    task_id: str = ""
    resource_id: str = ""
    resource_name: str = ""
    scenario_id: Optional[str] = None
    effort: Optional[float] = None
    effort_unit: str = "person-days"
    start: Optional[date] = None
    finish: Optional[date] = None
    role: str = ""


# ── Risk / Issue / Change (PM-09) ────────────────────────────────────────────

@dataclass
class Risk:
    risk_id: str = field(default_factory=lambda: new_id("RISK"))
    project_id: str = ""
    title: str = ""
    owner: str = ""
    probability: Optional[float] = None   # 0..1, only if supplied
    impact: Optional[float] = None        # 0..1, only if supplied
    response: str = ""
    status: RiskStatus = RiskStatus.OPEN

    @property
    def exposure(self) -> Optional[float]:
        if self.probability is None or self.impact is None:
            return None
        return round(self.probability * self.impact, 4)


@dataclass
class Issue:
    issue_id: str = field(default_factory=lambda: new_id("ISS"))
    project_id: str = ""
    task_id: Optional[str] = None
    title: str = ""
    owner: str = ""
    impact: str = ""
    action: str = ""
    status: IssueStatus = IssueStatus.OPEN


@dataclass
class ChangeRequest:
    change_id: str = field(default_factory=lambda: new_id("CHG"))
    project_id: str = ""
    title: str = ""
    requester: str = ""
    scope_impact: str = ""
    schedule_impact: str = ""
    cost_impact: str = ""
    decision: str = ""
    approver: str = ""
    baseline_reference: Optional[str] = None
    status: ChangeStatus = ChangeStatus.PROPOSED


# ── Conditional cost (deferred — FR-UP-11) ───────────────────────────────────

@dataclass
class CostBaseline:
    work_package_id: str = ""
    time_period: str = ""
    approved_budget: float = 0.0


@dataclass
class ActualCost:
    work_package_id: str = ""
    time_period: str = ""
    actual_cost: float = 0.0
    source_reference: str = ""


# ── Progress status (PM-08) ──────────────────────────────────────────────────

@dataclass
class StatusUpdate:
    """One task's actuals as observed at a status date."""
    task_id: str = ""
    actual_start: Optional[date] = None
    actual_finish: Optional[date] = None
    remaining_effort: Optional[float] = None
    percent_complete: Optional[float] = None   # 0..100, only if measured
    evidence: str = ""
    updater: str = ""


# ── Immutable snapshots (PM-07, PM-08, FC-07) ────────────────────────────────

@dataclass
class BaselineSnapshot:
    baseline_id: str = field(default_factory=lambda: new_id("BASE"))
    project_id: str = ""
    label: str = ""
    as_of_date: Optional[date] = None
    scope_payload: Dict[str, Any] = field(default_factory=dict)
    schedule_payload: Dict[str, Any] = field(default_factory=dict)
    approved_by: str = ""
    source_version: str = ""


@dataclass
class StatusSnapshot:
    status_id: str = field(default_factory=lambda: new_id("STAT"))
    project_id: str = ""
    as_of_date: Optional[date] = None
    baseline_id: Optional[str] = None
    updates: List[StatusUpdate] = field(default_factory=list)
    updater: str = ""


@dataclass
class ForecastSnapshot:
    forecast_id: str = field(default_factory=lambda: new_id("FC"))
    project_id: str = ""
    method: str = ""
    as_of_date: Optional[date] = None
    inputs: Dict[str, Any] = field(default_factory=dict)
    outputs: Dict[str, Any] = field(default_factory=dict)
    drivers: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    rule_version: str = ""
    status: str = "ok"   # "ok" | "insufficient_data"
