"""Session-only state for the read-only Jira Cloud integration.

No client secret, API token, access token, or refresh token is stored in the typed domain
objects below. OAuth tokens remain in a separate ephemeral Streamlit session key.
"""
from typing import Dict, List, Optional

import streamlit as st

from ..domain.jira_models import (
    JiraBoardConfig, JiraConnection, JiraIssue, JiraResourceMapping, JiraSprint,
    JiraSprintSnapshot, JiraSyncStatus,
)

_CONNECTION = "sv_jira_connection"
_BOARD = "sv_jira_board"
_SPRINTS = "sv_jira_sprints"
_ISSUES = "sv_jira_issues_by_sprint"
_MAPPINGS = "sv_jira_resource_mappings"
_SNAPSHOTS = "sv_jira_sprint_snapshots"
_SYNC_STATUS = "sv_jira_sync_status"
_OAUTH = "sv_jira_oauth_session"


def get_connection() -> Optional[JiraConnection]:
    return st.session_state.get(_CONNECTION)


def set_connection(value: JiraConnection) -> None:
    st.session_state[_CONNECTION] = value


def clear_connection_data() -> None:
    for key in (
        _CONNECTION, _BOARD, _SPRINTS, _ISSUES, _MAPPINGS,
        _SNAPSHOTS, _SYNC_STATUS, _OAUTH,
    ):
        st.session_state.pop(key, None)


def get_board() -> Optional[JiraBoardConfig]:
    return st.session_state.get(_BOARD)


def set_board(value: JiraBoardConfig) -> None:
    st.session_state[_BOARD] = value


def get_sprints() -> List[JiraSprint]:
    return st.session_state.get(_SPRINTS, [])


def set_sprints(value: List[JiraSprint]) -> None:
    st.session_state[_SPRINTS] = list(value)


def get_issues(sprint_id: Optional[int] = None) -> List[JiraIssue]:
    by_sprint: Dict[int, List[JiraIssue]] = st.session_state.get(_ISSUES, {})
    if sprint_id is None:
        return [issue for values in by_sprint.values() for issue in values]
    return list(by_sprint.get(int(sprint_id), []))


def set_issues(sprint_id: int, value: List[JiraIssue]) -> None:
    by_sprint: Dict[int, List[JiraIssue]] = dict(st.session_state.get(_ISSUES, {}))
    by_sprint[int(sprint_id)] = list(value)
    st.session_state[_ISSUES] = by_sprint


def get_mappings() -> List[JiraResourceMapping]:
    return st.session_state.get(_MAPPINGS, [])


def set_mappings(value: List[JiraResourceMapping]) -> None:
    st.session_state[_MAPPINGS] = list(value)


def get_snapshots() -> List[JiraSprintSnapshot]:
    return st.session_state.get(_SNAPSHOTS, [])


def add_snapshot(value: JiraSprintSnapshot) -> None:
    snapshots = [
        snap for snap in get_snapshots()
        if not (snap.sprint_id == value.sprint_id and snap.snapshot_kind == value.snapshot_kind)
    ]
    snapshots.append(value)
    snapshots.sort(key=lambda s: s.captured_at)
    st.session_state[_SNAPSHOTS] = snapshots


def get_snapshot(sprint_id: int, snapshot_kind: str) -> Optional[JiraSprintSnapshot]:
    matching = [
        snap for snap in get_snapshots()
        if snap.sprint_id == int(sprint_id) and snap.snapshot_kind == snapshot_kind
    ]
    return matching[-1] if matching else None


def get_sync_status() -> JiraSyncStatus:
    return st.session_state.get(_SYNC_STATUS, JiraSyncStatus())


def set_sync_status(value: JiraSyncStatus) -> None:
    st.session_state[_SYNC_STATUS] = value


def get_oauth_session() -> Dict[str, object]:
    return dict(st.session_state.get(_OAUTH, {}))


def set_oauth_session(value: Dict[str, object]) -> None:
    st.session_state[_OAUTH] = dict(value)
