"""
Application services — orchestrate use-cases without touching Streamlit state or UI.
"""
import copy
import json
import uuid
from datetime import date
from typing import Dict, List, Optional, Tuple

from ..domain.models import (
    Sprint, Scenario, RuleSet,
    CalculationResult, CalculationSnapshot,
)
from ..calculation.engine import calculate


def calculate_scenario(
    sprint: Sprint,
    scenario: Scenario,
    ruleset: RuleSet,
    as_of_date: Optional[date] = None,
) -> CalculationResult:
    return calculate(sprint, scenario, ruleset, as_of_date)


def clone_scenario(source: Scenario, new_name: str) -> Scenario:
    """Deep-copy a scenario and assign a fresh ID, keeping base_scenario_id for lineage."""
    clone = copy.deepcopy(source)
    clone.scenario_id = str(uuid.uuid4())[:8]
    clone.name = new_name
    clone.base_scenario_id = source.scenario_id
    return clone


def compare_scenarios(
    sprint: Sprint,
    scenarios: List[Scenario],
    ruleset: RuleSet,
    as_of_date: Optional[date] = None,
) -> List[Tuple[Scenario, CalculationResult]]:
    """Calculate all supplied scenarios and return paired results."""
    return [(s, calculate_scenario(sprint, s, ruleset, as_of_date)) for s in scenarios]


def create_snapshot(
    sprint: Sprint,
    scenario: Scenario,
    result: CalculationResult,
) -> CalculationSnapshot:
    """
    Freeze the current inputs and outputs into an immutable snapshot.

    The snapshot records rule_version and as_of_date so it can be replayed
    even after formula constants change (NFR-A / TR-03).
    """
    input_payload: Dict = {
        "sprint": {
            "sprint_id": sprint.sprint_id,
            "name": sprint.name,
            "start_date": str(sprint.start_date),
            "end_date": str(sprint.end_date),
            "development_end_date": str(sprint.development_end_date),
            "public_holidays": sprint.public_holidays,
            "buffer": sprint.buffer,
            "backup": sprint.backup,
            "fixed_day_deduction": sprint.fixed_day_deduction,
        },
        "resources": [
            {
                "resource_id": r.resource_id,
                "display_name": r.display_name,
                "velocity": r.velocity,
                "leave_days": r.leave_days,
                "ot_hours": r.ot_hours,
                "ot_days": r.ot_days,
                "v_percent": r.v_percent,
                "others": r.others,
                "type": r.type.value,
            }
            for r in scenario.resources
        ],
    }
    output_payload: Dict = {
        "dev_days": result.dev_days,
        "remaining_dev_days": result.remaining_dev_days,
        "buffer_days": result.buffer_days,
        "full_dev_v": result.full_dev_v,
        "dev_v": result.dev_v,
        "full_qc_v": result.full_qc_v,
        "qc_v": result.qc_v,
        "team_velocity_biz": result.team_velocity_biz,
        "qc_minus_dev": result.qc_minus_dev,
        "as_of_date": str(result.as_of_date),
    }

    has_errors = any(w.startswith("ERROR") for w in result.warnings)
    return CalculationSnapshot(
        snapshot_id=str(uuid.uuid4())[:8],
        scenario_id=scenario.scenario_id,
        scenario_name=scenario.name,
        sprint_id=sprint.sprint_id,
        input_payload=input_payload,
        output_payload=output_payload,
        warnings=result.warnings,
        rule_version=result.rule_version,
        as_of_date=result.as_of_date,
        approved=not has_errors,
    )


def snapshot_to_json(snapshot: CalculationSnapshot) -> str:
    data = {
        "snapshot_id": snapshot.snapshot_id,
        "scenario_id": snapshot.scenario_id,
        "scenario_name": snapshot.scenario_name,
        "sprint_id": snapshot.sprint_id,
        "rule_version": snapshot.rule_version,
        "as_of_date": str(snapshot.as_of_date),
        "approved": snapshot.approved,
        "warnings": snapshot.warnings,
        "inputs": snapshot.input_payload,
        "outputs": snapshot.output_payload,
    }
    return json.dumps(data, indent=2, ensure_ascii=False)
