"""Jira Cloud integration domain models.

The integration deliberately separates three units that must not be mixed:

* Jira board estimates for team velocity (story points, time, count, or a numeric field),
* Jira remaining time for individual workload demand, and
* Saturn person-day/hour availability for resource capacity.

Credentials are intentionally absent from every model in this module. Connection secrets and
OAuth tokens are session-only concerns handled by the Jira integration page.
"""
from dataclasses import dataclass, field
from datetime import date, datetime
from enum import Enum
from typing import Dict, List, Optional


class JiraAuthMode(str, Enum):
    OAUTH_3LO = "OAuth 2.0 (3LO)"
    API_TOKEN = "API token (internal proof of concept)"


@dataclass
class JiraConnection:
    auth_mode: JiraAuthMode = JiraAuthMode.OAUTH_3LO
    site_url: str = ""
    cloud_id: str = ""
    board_id: Optional[int] = None
    account_email: str = ""


@dataclass
class JiraBoardConfig:
    board_id: int
    name: str = ""
    board_type: str = ""
    filter_id: str = ""
    estimation_type: str = "none"
    estimation_field_id: Optional[str] = None
    estimation_display_name: str = "Work item count"
    done_status_ids: List[str] = field(default_factory=list)
    done_status_names: List[str] = field(default_factory=list)


@dataclass
class JiraSprint:
    sprint_id: int
    name: str
    state: str
    board_id: Optional[int] = None
    start_date: Optional[datetime] = None
    end_date: Optional[datetime] = None
    complete_date: Optional[datetime] = None
    goal: str = ""


@dataclass
class JiraIssue:
    issue_id: str
    key: str
    summary: str
    issue_type: str = ""
    is_subtask: bool = False
    parent_key: str = ""
    priority: str = ""
    status_id: str = ""
    status_name: str = ""
    status_category: str = ""
    assignee_account_id: str = ""
    assignee_display_name: str = "Unassigned"
    estimate: Optional[float] = None
    original_estimate_seconds: Optional[int] = None
    remaining_estimate_seconds: Optional[int] = None
    time_spent_seconds: Optional[int] = None
    created: Optional[datetime] = None
    updated: Optional[datetime] = None
    resolution_date: Optional[datetime] = None
    sprint_id: Optional[int] = None
    done: bool = False


@dataclass
class JiraResourceMapping:
    jira_account_id: str
    jira_display_name: str
    resource_id: str = ""
    resource_name: str = ""


@dataclass
class JiraSprintSnapshot:
    sprint_id: int
    sprint_name: str
    snapshot_kind: str  # "start" or "close"
    captured_at: datetime
    estimation_field_id: Optional[str]
    estimation_display_name: str
    issue_estimates: Dict[str, Optional[float]] = field(default_factory=dict)
    issue_done: Dict[str, bool] = field(default_factory=dict)
    issue_is_subtask: Dict[str, bool] = field(default_factory=dict)
    source: str = "live_sync"
    warnings: List[str] = field(default_factory=list)


@dataclass
class JiraVelocityResult:
    sprint_id: int
    sprint_name: str
    estimation_display_name: str
    commitment: float
    completed: float
    scope_added: int
    scope_removed: int
    start_captured_at: datetime
    close_captured_at: datetime
    start_source: str = ""
    close_source: str = ""
    warnings: List[str] = field(default_factory=list)


@dataclass
class JiraWorkloadRow:
    jira_account_id: str
    jira_display_name: str
    resource_id: str = ""
    resource_name: str = ""
    demand_hours: float = 0.0
    capacity_hours: Optional[float] = None
    issue_count: int = 0
    unestimated_issue_count: int = 0

    @property
    def utilization(self) -> Optional[float]:
        if self.capacity_hours is None or self.capacity_hours <= 0:
            return None
        return round(self.demand_hours / self.capacity_hours, 4)

    @property
    def over_allocated(self) -> Optional[bool]:
        if self.capacity_hours is None:
            return None
        return self.demand_hours > self.capacity_hours


@dataclass
class JiraWorkloadResult:
    rows: List[JiraWorkloadRow] = field(default_factory=list)
    unknown_estimate_issue_keys: List[str] = field(default_factory=list)
    unassigned_issue_keys: List[str] = field(default_factory=list)
    unmapped_account_ids: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


@dataclass
class JiraSyncStatus:
    synced_at: Optional[datetime] = None
    board_id: Optional[int] = None
    sprint_id: Optional[int] = None
    issue_count: int = 0
    source_updated_after: Optional[datetime] = None
    warnings: List[str] = field(default_factory=list)
