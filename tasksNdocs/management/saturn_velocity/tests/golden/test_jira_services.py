"""Golden tests for Jira normalization, workload, capacity, and velocity rules."""
import sys
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from src.application.jira_services import (
    average_completed_velocity,
    build_saturn_capacity_hours,
    calculate_velocity,
    calculate_workload,
    capture_sprint_snapshot,
    parse_board_configuration,
    parse_issue,
    parse_sprint,
)
from src.application.jira_snapshot_io import export_snapshot_bundle, import_snapshot_bundle
from src.domain.jira_models import (
    JiraBoardConfig,
    JiraIssue,
    JiraResourceMapping,
    JiraSprint,
    JiraSprintSnapshot,
)
from src.domain.models import ResourceType, RuleSet, Scenario, ScenarioResource, Sprint


UTC = timezone.utc


def _board():
    return JiraBoardConfig(
        board_id=42,
        name="Delivery",
        estimation_type="field",
        estimation_field_id="customfield_10016",
        estimation_display_name="Story points",
        done_status_ids=["3"],
        done_status_names=["Done"],
    )


def _issue(key, *, estimate=None, done=False, assignee="a1", remaining=None,
           original=None, subtask=False):
    return JiraIssue(
        issue_id=key,
        key=key,
        summary=key,
        estimate=estimate,
        done=done,
        assignee_account_id=assignee,
        assignee_display_name="Alice" if assignee else "Unassigned",
        remaining_estimate_seconds=remaining,
        original_estimate_seconds=original,
        is_subtask=subtask,
    )


def _snapshot(kind, estimates, done=None, subtasks=None):
    return JiraSprintSnapshot(
        sprint_id=101,
        sprint_name="S1",
        snapshot_kind=kind,
        captured_at=datetime(2026, 8, 1 if kind == "start" else 14, tzinfo=UTC),
        estimation_field_id="customfield_10016",
        estimation_display_name="Story points",
        issue_estimates=estimates,
        issue_done=done or {},
        issue_is_subtask=subtasks or {},
    )


def test_board_configuration_uses_last_column_as_done_contract():
    raw = {
        "id": 42,
        "name": "Delivery",
        "type": "scrum",
        "filter": {"id": "9001"},
        "estimation": {
            "type": "field",
            "field": {"fieldId": "customfield_10016", "displayName": "Story points"},
        },
        "columnConfig": {"columns": [
            {"name": "To Do", "statuses": [{"id": "1", "name": "Open"}]},
            {"name": "Done", "statuses": [{"id": "3", "name": "Released"}]},
        ]},
    }
    board = parse_board_configuration(raw, 42)
    assert board.estimation_field_id == "customfield_10016"
    assert board.done_status_ids == ["3"]
    assert board.done_status_names == ["Released"]


def test_issue_parsing_uses_board_field_done_mapping_and_time_tracking():
    raw = {
        "id": "1",
        "key": "DEMO-1",
        "fields": {
            "summary": "Example",
            "issuetype": {"name": "Story", "subtask": False},
            "status": {"id": "3", "name": "Released", "statusCategory": {"key": "done"}},
            "assignee": {"accountId": "a1", "displayName": "Alice"},
            "timetracking": {
                "originalEstimateSeconds": 14400,
                "remainingEstimateSeconds": 7200,
                "timeSpentSeconds": 3600,
            },
            "created": "2026-08-01T09:00:00",
            "customfield_10016": 5,
        },
    }
    issue = parse_issue(raw, _board(), 101)
    assert issue.estimate == 5.0
    assert issue.done is True
    assert issue.remaining_estimate_seconds == 7200
    assert issue.created.tzinfo == UTC


def test_sprint_parsing_normalizes_timezone():
    sprint = parse_sprint({
        "id": 101,
        "name": "S1",
        "state": "active",
        "startDate": "2026-08-01T09:00:00",
    })
    assert sprint.start_date.tzinfo == UTC


def test_velocity_separates_commitment_completion_scope_and_subtasks():
    start = _snapshot(
        "start",
        {"A": 5.0, "B": 3.0, "SUB": 2.0},
        subtasks={"SUB": True},
    )
    close = _snapshot(
        "close",
        {"A": 5.0, "B": 3.0, "SUB": 2.0, "C": 2.0},
        done={"A": True, "B": False, "SUB": True, "C": True},
        subtasks={"SUB": True},
    )
    result = calculate_velocity(start, close)
    assert result.commitment == 8.0
    assert result.completed == 7.0
    assert result.scope_added == 1
    assert result.scope_removed == 0


def test_velocity_average_requires_an_explicit_positive_window():
    result = calculate_velocity(
        _snapshot("start", {"A": 5.0}),
        _snapshot("close", {"A": 5.0}, done={"A": True}),
    )
    assert average_completed_velocity([result], None) is None
    assert average_completed_velocity([result], 0) is None
    assert average_completed_velocity([result], 1) == 5.0


def test_live_snapshot_is_labeled_reconstructed_but_exact_boundary_is_not():
    sprint = JiraSprint(
        sprint_id=101,
        name="S1",
        state="active",
        start_date=datetime(2026, 8, 1, tzinfo=UTC),
    )
    exact = capture_sprint_snapshot(
        sprint, _board(), [_issue("A", estimate=5.0)], "start",
        captured_at=sprint.start_date,
    )
    live = capture_sprint_snapshot(sprint, _board(), [_issue("A", estimate=5.0)], "start")
    assert exact.source == "boundary_snapshot"
    assert exact.warnings == []
    assert live.source == "reconstructed_live_sync"
    assert live.warnings


def test_workload_uses_time_seconds_and_preserves_unknowns():
    issues = [
        _issue("A", estimate=13.0, remaining=7200, original=14400),
        _issue("B", estimate=8.0, remaining=None, original=3600),
        _issue("C", estimate=5.0, assignee="", remaining=None),
        _issue("D", estimate=3.0, done=True, remaining=36000),
    ]
    mappings = [JiraResourceMapping("a1", "Alice", "r1", "Alice Saturn")]
    result = calculate_workload(
        issues,
        mappings,
        demand_mode="remaining",
        capacity_hours_by_resource={"r1": 4.0},
    )
    assert len(result.rows) == 1
    row = result.rows[0]
    assert row.demand_hours == 2.0
    assert row.issue_count == 2
    assert row.unestimated_issue_count == 1
    assert row.utilization == 0.5
    assert result.unknown_estimate_issue_keys == ["B", "C"]
    assert result.unassigned_issue_keys == ["C"]


def test_saturn_capacity_requires_approved_rules_and_uses_hours_per_day():
    sprint = Sprint(
        sprint_id="saturn-1",
        name="S1",
        start_date=date(2026, 8, 3),
        end_date=date(2026, 8, 14),
        development_end_date=date(2026, 8, 14),
        buffer=0.1,
        backup=1.0,
        fixed_day_deduction=3.0,
    )
    scenario = Scenario(
        scenario_id="scenario-1",
        sprint_id="saturn-1",
        name="Baseline",
        resources=[ScenarioResource(
            scenario_id="scenario-1",
            resource_id="r1",
            display_name="Alice",
            leave_days=1.0,
            v_percent=1.0,
            type=ResourceType.DEV,
        )],
    )
    draft_capacity, draft_warnings = build_saturn_capacity_hours(
        sprint, scenario, RuleSet(effective_status="draft"),
        as_of_date=date(2026, 8, 3), remaining=False,
    )
    approved_capacity, approved_warnings = build_saturn_capacity_hours(
        sprint, scenario, RuleSet(hours_per_day=8.0, effective_status="approved"),
        as_of_date=date(2026, 8, 3), remaining=False,
    )
    assert draft_capacity == {}
    assert draft_warnings
    assert approved_capacity == {"r1": 36.0}
    assert approved_warnings == []


def test_velocity_snapshot_bundle_round_trip_is_board_scoped():
    source = _snapshot("start", {"A": 5.0, "B": None}, done={"A": False})
    raw = export_snapshot_bundle(_board(), [source])
    board_id, restored = import_snapshot_bundle(raw)
    assert board_id == 42
    assert len(restored) == 1
    assert restored[0].captured_at == source.captured_at
    assert restored[0].issue_estimates == {"A": 5.0, "B": None}


def test_velocity_snapshot_bundle_rejects_unknown_schema():
    try:
        import_snapshot_bundle(b'{"schema_version":"unknown","board_id":42,"snapshots":[]}')
    except ValueError as exc:
        assert "schema" in str(exc)
    else:
        raise AssertionError("Expected an unknown snapshot schema to be rejected")
