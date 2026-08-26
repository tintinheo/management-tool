from dataclasses import dataclass, field
from datetime import date
from typing import Optional, List, Dict, Any
from enum import Enum
import uuid


class ResourceType(str, Enum):
    DEV = "Dev"
    QC = "QC"
    BIZ = "Biz"
    OTHER = "Other"


class LeaveStatus(str, Enum):
    PLANNED = "Planned"
    TBD = "TBD"


@dataclass
class Sprint:
    sprint_id: str
    name: str
    start_date: date
    end_date: date
    development_end_date: date
    public_holidays: int = 0
    buffer: float = 0.1
    backup: float = 1.0
    # fixed_day_deduction is the unnamed constant "3" from WB-s192-CALC.
    # Business meaning is undocumented; exposed as a named setting per R-04.
    fixed_day_deduction: float = 3.0


@dataclass
class Resource:
    resource_id: str
    display_name: str
    default_velocity: float = 1.0
    default_type: ResourceType = ResourceType.DEV


@dataclass
class ScenarioResource:
    scenario_id: str
    resource_id: str
    display_name: str
    velocity: float = 1.0
    leave_days: float = 0.0
    ot_hours: float = 0.0
    ot_days: float = 0.0
    v_percent: float = 1.0
    others: float = 0.0
    type: ResourceType = ResourceType.DEV


@dataclass
class LeaveEvent:
    resource_id: str
    resource_name: str
    date: Optional[date] = None
    days: float = 0.0
    status: LeaveStatus = LeaveStatus.PLANNED
    note: str = ""


@dataclass
class Ticket:
    ticket_id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    dev: str = ""
    qc: str = ""
    category: str = ""
    assignee: str = ""
    summary: str = ""
    priority: str = ""
    point: float = 0.0
    status: str = ""
    added_after_sprint_start: bool = False
    note: str = ""


@dataclass
class Scenario:
    scenario_id: str
    sprint_id: str
    name: str
    resources: List[ScenarioResource] = field(default_factory=list)
    leave_events: List[LeaveEvent] = field(default_factory=list)
    tickets: List[Ticket] = field(default_factory=list)
    base_scenario_id: Optional[str] = None
    rule_version: str = "s192-baseline"


@dataclass
class RuleSet:
    rule_version: str = "s192-baseline"
    # hours_per_day is CRITICAL DATA MISSING per BRD; exposed but not yet used in capacity formula.
    hours_per_day: float = 8.0
    # fixed_day_deduction mirrors Sprint.fixed_day_deduction; both must stay in sync.
    fixed_day_deduction: float = 3.0
    effective_status: str = "draft"


@dataclass
class ResourceResult:
    resource_id: str
    display_name: str
    type: ResourceType
    leave_days: float
    ot_days: float
    fte_no_ot: float   # BR-04
    full_v: float      # BR-05
    v: float           # BR-06 buffered without OT
    v_ot: float        # BR-06 buffered with OT


@dataclass
class CalculationResult:
    dev_days: float                        # BR-01
    remaining_dev_days: float              # BR-02
    buffer_days: float                     # BR-10
    resource_results: List[ResourceResult]
    full_dev_v: float
    dev_v: float
    full_qc_v: float
    qc_v: float
    team_velocity_biz: float               # BR-08
    qc_minus_dev: float                    # BR-09
    as_of_date: date
    rule_version: str
    warnings: List[str]

    @property
    def has_errors(self) -> bool:
        return any(w.startswith("ERROR") for w in self.warnings)


@dataclass
class CalculationSnapshot:
    snapshot_id: str
    scenario_id: str
    scenario_name: str
    sprint_id: str
    input_payload: Dict[str, Any]
    output_payload: Dict[str, Any]
    warnings: List[str]
    rule_version: str
    as_of_date: date
    approved: bool = False
