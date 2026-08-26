"""
Session store — typed wrappers around st.session_state.

All Streamlit pages import from here so key names are centralised.
"""
import uuid
from typing import Dict, List, Optional

import streamlit as st

from ..domain.models import (
    CalculationSnapshot, RuleSet, Resource, Scenario,
    ScenarioResource, Sprint,
)

# ── Key constants ────────────────────────────────────────────────────────────
_SPRINT = "sv_sprint"
_SCENARIOS = "sv_scenarios"
_ACTIVE_SID = "sv_active_scenario_id"
_RULESET = "sv_ruleset"
_SNAPSHOTS = "sv_snapshots"


# ── Sprint ────────────────────────────────────────────────────────────────────
def get_sprint() -> Optional[Sprint]:
    return st.session_state.get(_SPRINT)


def set_sprint(sprint: Sprint) -> None:
    st.session_state[_SPRINT] = sprint


# ── Scenarios ─────────────────────────────────────────────────────────────────
def get_scenarios() -> Dict[str, Scenario]:
    return st.session_state.get(_SCENARIOS, {})


def set_scenarios(scenarios: Dict[str, Scenario]) -> None:
    st.session_state[_SCENARIOS] = scenarios


def upsert_scenario(scenario: Scenario) -> None:
    scenarios = get_scenarios()
    scenarios[scenario.scenario_id] = scenario
    set_scenarios(scenarios)
    if get_active_scenario_id() is None:
        set_active_scenario_id(scenario.scenario_id)


def delete_scenario(scenario_id: str) -> None:
    scenarios = get_scenarios()
    scenarios.pop(scenario_id, None)
    set_scenarios(scenarios)
    if get_active_scenario_id() == scenario_id:
        remaining = list(scenarios.keys())
        st.session_state[_ACTIVE_SID] = remaining[0] if remaining else None


# ── Active scenario ───────────────────────────────────────────────────────────
def get_active_scenario_id() -> Optional[str]:
    return st.session_state.get(_ACTIVE_SID)


def set_active_scenario_id(sid: str) -> None:
    st.session_state[_ACTIVE_SID] = sid


def get_active_scenario() -> Optional[Scenario]:
    sid = get_active_scenario_id()
    return get_scenarios().get(sid) if sid else None


# ── RuleSet ───────────────────────────────────────────────────────────────────
def get_ruleset() -> RuleSet:
    return st.session_state.get(_RULESET, RuleSet())


def set_ruleset(rs: RuleSet) -> None:
    st.session_state[_RULESET] = rs


# ── Snapshots ─────────────────────────────────────────────────────────────────
def get_snapshots() -> List[CalculationSnapshot]:
    return st.session_state.get(_SNAPSHOTS, [])


def add_snapshot(snap: CalculationSnapshot) -> None:
    snaps = list(get_snapshots())
    snaps.append(snap)
    st.session_state[_SNAPSHOTS] = snaps


# ── Helpers ───────────────────────────────────────────────────────────────────
def has_sprint() -> bool:
    return get_sprint() is not None


def has_scenario() -> bool:
    return bool(get_scenarios())


def has_data() -> bool:
    return has_sprint() and has_scenario()


def new_scenario_id() -> str:
    return str(uuid.uuid4())[:8]


def create_blank_scenario(sprint_id: str, name: str = "Baseline") -> Scenario:
    return Scenario(
        scenario_id=new_scenario_id(),
        sprint_id=sprint_id,
        name=name,
    )
