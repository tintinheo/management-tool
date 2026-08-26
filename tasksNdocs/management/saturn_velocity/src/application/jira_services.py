"""Jira synchronization, normalization, workload, and velocity services.

All functions are independent of Streamlit so they can be tested without credentials or a
network. Jira board estimates are used only for team velocity. Individual workload uses Jira
time-tracking seconds and is reconciled to Saturn hours only when the Saturn RuleSet is approved.
"""
from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from ..calculation.engine import calculate
from ..domain.jira_models import (
    JiraBoardConfig, JiraIssue, JiraResourceMapping, JiraSprint, JiraSprintSnapshot,
    JiraVelocityResult, JiraWorkloadResult, JiraWorkloadRow,
)
from ..domain.models import LeaveStatus, RuleSet, Scenario, Sprint
from ..integrations.jira_cloud import JiraCloudClient


ISSUE_FIELDS = [
    "summary", "issuetype", "parent", "priority", "status", "assignee",
    "timetracking", "created", "updated", "resolutiondate",
]


def _parse_datetime(value) -> Optional[datetime]:
    if not value:
        return None
    if isinstance(value, datetime):
        return _ensure_timezone(value)
    text = str(value).strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        return _ensure_timezone(datetime.fromisoformat(text))
    except ValueError:
        for fmt in ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z"):
            try:
                return _ensure_timezone(datetime.strptime(text, fmt))
            except ValueError:
                continue
    return None


def parse_board_configuration(raw: Dict, board_id: int) -> JiraBoardConfig:
    columns = list((raw.get("columnConfig") or {}).get("columns") or [])
    done_column = columns[-1] if columns else {}
    done_statuses = list(done_column.get("statuses") or [])
    estimation = raw.get("estimation") or {}
    estimation_field = estimation.get("field") or {}
    filter_obj = raw.get("filter") or {}
    return JiraBoardConfig(
        board_id=int(raw.get("id") or board_id),
        name=str(raw.get("name") or ""),
        board_type=str(raw.get("type") or ""),
        filter_id=str(filter_obj.get("id") or ""),
        estimation_type=str(estimation.get("type") or "none"),
        estimation_field_id=estimation_field.get("fieldId"),
        estimation_display_name=str(
            estimation_field.get("displayName")
            or ("Work item count" if estimation.get("type") == "issueCount" else "Not configured")
        ),
        done_status_ids=[str(s.get("id")) for s in done_statuses if s.get("id") is not None],
        done_status_names=[str(s.get("name") or s.get("id")) for s in done_statuses],
    )


def parse_sprint(raw: Dict, board_id: Optional[int] = None) -> JiraSprint:
    return JiraSprint(
        sprint_id=int(raw["id"]),
        name=str(raw.get("name") or raw["id"]),
        state=str(raw.get("state") or ""),
        board_id=int(raw.get("originBoardId") or board_id) if (raw.get("originBoardId") or board_id) else None,
        start_date=_parse_datetime(raw.get("startDate")),
        end_date=_parse_datetime(raw.get("endDate")),
        complete_date=_parse_datetime(raw.get("completeDate")),
        goal=str(raw.get("goal") or ""),
    )


def parse_issue(raw: Dict, board: JiraBoardConfig, sprint_id: int) -> JiraIssue:
    fields = raw.get("fields") or {}
    issue_type = fields.get("issuetype") or {}
    status = fields.get("status") or {}
    status_category = status.get("statusCategory") or {}
    assignee = fields.get("assignee") or {}
    priority = fields.get("priority") or {}
    parent = fields.get("parent") or {}
    tracking = fields.get("timetracking") or {}

    estimate: Optional[float] = None
    if board.estimation_field_id:
        value = fields.get(board.estimation_field_id)
        if value is not None:
            try:
                estimate = float(value)
            except (TypeError, ValueError):
                estimate = None
    elif board.estimation_type == "issueCount":
        estimate = 1.0

    status_id = str(status.get("id") or "")
    return JiraIssue(
        issue_id=str(raw.get("id") or ""),
        key=str(raw.get("key") or ""),
        summary=str(fields.get("summary") or ""),
        issue_type=str(issue_type.get("name") or ""),
        is_subtask=bool(issue_type.get("subtask", False)),
        parent_key=str(parent.get("key") or ""),
        priority=str(priority.get("name") or ""),
        status_id=status_id,
        status_name=str(status.get("name") or ""),
        status_category=str(status_category.get("key") or status_category.get("name") or ""),
        assignee_account_id=str(assignee.get("accountId") or ""),
        assignee_display_name=str(assignee.get("displayName") or "Unassigned"),
        estimate=estimate,
        original_estimate_seconds=_optional_int(tracking.get("originalEstimateSeconds")),
        remaining_estimate_seconds=_optional_int(tracking.get("remainingEstimateSeconds")),
        time_spent_seconds=_optional_int(tracking.get("timeSpentSeconds")),
        created=_parse_datetime(fields.get("created")),
        updated=_parse_datetime(fields.get("updated")),
        resolution_date=_parse_datetime(fields.get("resolutiondate")),
        sprint_id=sprint_id,
        done=status_id in set(board.done_status_ids),
    )


def _optional_int(value) -> Optional[int]:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def load_board(client: JiraCloudClient, board_id: int) -> Tuple[JiraBoardConfig, List[JiraSprint]]:
    config = parse_board_configuration(client.get_board_configuration(board_id), board_id)
    sprints = [parse_sprint(raw, board_id) for raw in client.get_board_sprints(board_id)]
    sprints.sort(key=lambda s: (s.start_date or datetime.min.replace(tzinfo=timezone.utc), s.sprint_id), reverse=True)
    return config, sprints


def sync_sprint_issues(
    client: JiraCloudClient,
    board: JiraBoardConfig,
    sprint: JiraSprint,
) -> List[JiraIssue]:
    fields = list(ISSUE_FIELDS)
    if board.estimation_field_id:
        fields.append(board.estimation_field_id)
    raw_issues = client.search_issues(
        f"sprint = {sprint.sprint_id} ORDER BY rank ASC",
        fields,
    )
    return [parse_issue(raw, board, sprint.sprint_id) for raw in raw_issues]


def capture_sprint_snapshot(
    sprint: JiraSprint,
    board: JiraBoardConfig,
    issues: Sequence[JiraIssue],
    snapshot_kind: str,
    *,
    captured_at: Optional[datetime] = None,
) -> JiraSprintSnapshot:
    if snapshot_kind not in {"start", "close"}:
        raise ValueError("snapshot_kind must be 'start' or 'close'.")
    warnings: List[str] = []
    boundary_verified = True
    is_live_capture = captured_at is None
    captured = _ensure_timezone(captured_at or datetime.now(timezone.utc))
    reference = sprint.start_date if snapshot_kind == "start" else (sprint.complete_date or sprint.end_date)
    if is_live_capture:
        boundary_verified = False
        warnings.append(
            f"{snapshot_kind.title()} snapshot is a live capture; the exact Jira Sprint "
            "boundary was not independently verified."
        )
    elif reference and captured != _ensure_timezone(reference):
        boundary_verified = False
        warnings.append(
            f"{snapshot_kind.title()} snapshot was not captured at the Jira Sprint boundary; "
            "velocity will be labeled reconstructed."
        )
    if not board.done_status_ids:
        warnings.append("Board Done-column mapping is empty; completed velocity may be understated.")
    missing_estimates = [issue.key for issue in issues if not issue.is_subtask and issue.estimate is None]
    if missing_estimates:
        warnings.append(
            f"{len(missing_estimates)} non-subtask issue(s) have no board estimate; "
            "velocity totals exclude their unknown estimate."
        )
    return JiraSprintSnapshot(
        sprint_id=sprint.sprint_id,
        sprint_name=sprint.name,
        snapshot_kind=snapshot_kind,
        captured_at=captured,
        estimation_field_id=board.estimation_field_id,
        estimation_display_name=board.estimation_display_name,
        issue_estimates={i.key: i.estimate for i in issues},
        issue_done={i.key: i.done for i in issues},
        issue_is_subtask={i.key: i.is_subtask for i in issues},
        source="boundary_snapshot" if boundary_verified else "reconstructed_live_sync",
        warnings=warnings,
    )


def _ensure_timezone(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def calculate_velocity(
    start: JiraSprintSnapshot,
    close: JiraSprintSnapshot,
) -> JiraVelocityResult:
    if start.sprint_id != close.sprint_id:
        raise ValueError("Velocity snapshots must belong to the same Sprint.")
    if start.snapshot_kind != "start" or close.snapshot_kind != "close":
        raise ValueError("Velocity requires a start snapshot and a close snapshot.")
    if start.estimation_field_id != close.estimation_field_id:
        raise ValueError("Board estimation field changed between Sprint snapshots.")

    def eligible(snapshot: JiraSprintSnapshot, key: str) -> bool:
        return not snapshot.issue_is_subtask.get(key, False)

    commitment = sum(
        float(value or 0.0)
        for key, value in start.issue_estimates.items()
        if eligible(start, key)
    )
    completed = sum(
        float(value or 0.0)
        for key, value in close.issue_estimates.items()
        if eligible(close, key) and close.issue_done.get(key, False)
    )
    start_keys = {k for k in start.issue_estimates if eligible(start, k)}
    close_keys = {k for k in close.issue_estimates if eligible(close, k)}
    return JiraVelocityResult(
        sprint_id=start.sprint_id,
        sprint_name=start.sprint_name,
        estimation_display_name=start.estimation_display_name,
        commitment=round(commitment, 3),
        completed=round(completed, 3),
        scope_added=len(close_keys - start_keys),
        scope_removed=len(start_keys - close_keys),
        start_captured_at=start.captured_at,
        close_captured_at=close.captured_at,
        start_source=start.source,
        close_source=close.source,
        warnings=list(start.warnings) + list(close.warnings),
    )


def average_completed_velocity(
    results: Sequence[JiraVelocityResult],
    window: Optional[int],
) -> Optional[float]:
    if not window or window <= 0 or not results:
        return None
    ordered = sorted(results, key=lambda result: _ensure_timezone(result.close_captured_at))
    selected = ordered[-window:]
    if not selected:
        return None
    return round(sum(r.completed for r in selected) / len(selected), 3)


def build_saturn_capacity_hours(
    sprint: Sprint,
    scenario: Scenario,
    ruleset: RuleSet,
    *,
    as_of_date: date,
    remaining: bool,
    include_ot: bool = False,
) -> Tuple[Dict[str, float], List[str]]:
    """Return resource capacity hours only when time-conversion rules are approved."""
    warnings: List[str] = []
    if ruleset.effective_status != "approved":
        return {}, ["Saturn RuleSet is not approved; capacity hours are unavailable."]
    if ruleset.hours_per_day <= 0:
        return {}, ["Approved hours_per_day must be greater than zero."]

    result = calculate(sprint, scenario, ruleset, as_of_date=as_of_date)
    if result.has_errors:
        return {}, ["Saturn capacity calculation has errors; capacity hours are unavailable."]

    dated_leave: Dict[str, float] = {}
    undated_leave_resources = set()
    if remaining:
        for event in scenario.leave_events:
            if event.status == LeaveStatus.TBD or event.date is None:
                undated_leave_resources.add(event.resource_id)
                continue
            if as_of_date <= event.date <= sprint.development_end_date:
                dated_leave[event.resource_id] = dated_leave.get(event.resource_id, 0.0) + event.days

    capacity: Dict[str, float] = {}
    base_days = result.remaining_dev_days if remaining else result.dev_days
    for resource in scenario.resources:
        leave_days = dated_leave.get(resource.resource_id, 0.0) if remaining else resource.leave_days
        ot_days = resource.ot_days if include_ot and not remaining else 0.0
        available_days = max(0.0, base_days - leave_days + ot_days)
        available_days *= max(0.0, resource.v_percent)
        available_days *= max(0.0, 1.0 - sprint.buffer)
        capacity[resource.resource_id] = round(available_days * ruleset.hours_per_day, 3)

    if remaining and undated_leave_resources:
        warnings.append(
            "Undated/TBD leave was not deducted from remaining capacity; review resource mappings."
        )
    if include_ot and remaining:
        warnings.append("OT is not included in remaining capacity because OT dates are unavailable.")
    return capacity, warnings


def calculate_workload(
    issues: Sequence[JiraIssue],
    mappings: Sequence[JiraResourceMapping],
    *,
    demand_mode: str = "remaining",
    capacity_hours_by_resource: Optional[Dict[str, float]] = None,
) -> JiraWorkloadResult:
    if demand_mode not in {"remaining", "original"}:
        raise ValueError("demand_mode must be 'remaining' or 'original'.")
    capacity = capacity_hours_by_resource or {}
    mapping_by_account = {m.jira_account_id: m for m in mappings if m.jira_account_id}
    grouped: Dict[str, JiraWorkloadRow] = {}
    result = JiraWorkloadResult()

    for issue in issues:
        if issue.done:
            continue
        seconds = (
            issue.remaining_estimate_seconds
            if demand_mode == "remaining"
            else issue.original_estimate_seconds
        )
        if seconds is None:
            result.unknown_estimate_issue_keys.append(issue.key)
        if not issue.assignee_account_id:
            result.unassigned_issue_keys.append(issue.key)
            continue
        account_id = issue.assignee_account_id
        row = grouped.setdefault(
            account_id,
            JiraWorkloadRow(
                jira_account_id=account_id,
                jira_display_name=issue.assignee_display_name,
            ),
        )
        row.issue_count += 1
        if seconds is None:
            row.unestimated_issue_count += 1
        else:
            row.demand_hours += seconds / 3600.0

    for account_id, row in grouped.items():
        mapping = mapping_by_account.get(account_id)
        if mapping:
            row.resource_id = mapping.resource_id
            row.resource_name = mapping.resource_name
            if mapping.resource_id in capacity:
                row.capacity_hours = capacity[mapping.resource_id]
        else:
            result.unmapped_account_ids.append(account_id)
        row.demand_hours = round(row.demand_hours, 3)
        result.rows.append(row)

    result.rows.sort(key=lambda r: r.jira_display_name.lower())
    result.unknown_estimate_issue_keys.sort()
    result.unassigned_issue_keys.sort()
    result.unmapped_account_ids.sort()
    if result.unknown_estimate_issue_keys:
        result.warnings.append("Unestimated Jira issues are unknown demand, not zero demand.")
    if result.unmapped_account_ids:
        result.warnings.append("Some Jira assignees are not mapped to Saturn resources.")
    if not capacity:
        result.warnings.append("Capacity hours are unavailable; utilization is not calculated.")
    return result
