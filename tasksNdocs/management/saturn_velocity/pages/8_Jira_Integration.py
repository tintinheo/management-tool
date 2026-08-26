"""Jira Cloud read-only connection, synchronization, mapping, and Sprint snapshots."""
import secrets
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd
import streamlit as st

from src.application.jira_services import (
    capture_sprint_snapshot, load_board, sync_sprint_issues,
)
from src.application.jira_snapshot_io import export_snapshot_bundle, import_snapshot_bundle
from src.domain.jira_models import (
    JiraAuthMode, JiraConnection, JiraResourceMapping, JiraSyncStatus,
)
from src.integrations.jira_cloud import (
    JiraApiError, JiraCloudClient, build_oauth_authorization_url,
    exchange_oauth_code, get_accessible_resources,
)
from src.storage import jira_store
from src.storage.session_store import get_active_scenario


st.set_page_config(page_title="Jira Integration · Saturn Velocity", layout="wide")
st.title("🔌 Jira Integration")
st.caption("Read-only Jira Cloud synchronization for workload and team velocity.")

st.info(
    "Jira board estimates are used for **team velocity**. Individual workload uses "
    "Jira time estimates. Story points are never converted into personal hours."
)


def _secret_section(name: str) -> dict:
    try:
        return dict(st.secrets[name])
    except Exception:  # noqa: BLE001 - missing secrets must be a normal UI state
        return {}


def _query_value(name: str) -> str:
    value = st.query_params.get(name, "")
    return value[0] if isinstance(value, list) and value else str(value or "")


DEFAULT_SCOPES = [
    "read:jira-work",
    "read:board-scope:jira-software",
    "read:sprint:jira-software",
]

connection = jira_store.get_connection() or JiraConnection()
auth_options = [mode.value for mode in JiraAuthMode]
auth_value = st.radio(
    "Authentication",
    auth_options,
    index=auth_options.index(connection.auth_mode.value),
    horizontal=True,
)
auth_mode = JiraAuthMode(auth_value)

client = None
board_id = 0

if auth_mode == JiraAuthMode.OAUTH_3LO:
    oauth_cfg = _secret_section("jira_oauth")
    client_id = str(oauth_cfg.get("client_id") or "")
    client_secret = str(oauth_cfg.get("client_secret") or "")
    redirect_uri = str(oauth_cfg.get("redirect_uri") or "")
    configured_scopes = str(oauth_cfg.get("scopes") or "").split()
    scopes = configured_scopes or DEFAULT_SCOPES

    if not (client_id and client_secret and redirect_uri):
        st.error("OAuth configuration is incomplete in Streamlit secrets.")
        st.code(
            '[jira_oauth]\nclient_id = "<atlassian-client-id>"\n'
            'client_secret = "<atlassian-client-secret>"\n'
            'redirect_uri = "<exact-jira-page-url>"\n'
            'scopes = "read:jira-work read:board-scope:jira-software '
            'read:sprint:jira-software"',
            language="toml",
        )
    else:
        oauth_session = jira_store.get_oauth_session()
        expected_state = str(oauth_session.get("state") or "")
        returned_code = _query_value("code")
        returned_state = _query_value("state")
        returned_error = _query_value("error")

        if returned_error:
            st.error("Atlassian authorization was not completed.")
        elif returned_code:
            if not expected_state or returned_state != expected_state:
                st.error("OAuth state validation failed. Start the connection again.")
            elif not oauth_session.get("access_token"):
                try:
                    token = exchange_oauth_code(
                        client_id, client_secret, returned_code, redirect_uri)
                    resources = get_accessible_resources(str(token.get("access_token") or ""))
                    oauth_session.update(token)
                    oauth_session["resources"] = resources
                    jira_store.set_oauth_session(oauth_session)
                    st.query_params.clear()
                    st.success("Atlassian authorization completed for this session.")
                    st.rerun()
                except (JiraApiError, ValueError) as exc:
                    st.error(str(exc))

        oauth_session = jira_store.get_oauth_session()
        access_token = str(oauth_session.get("access_token") or "")
        if not access_token:
            state = expected_state or secrets.token_urlsafe(24)
            if not expected_state:
                jira_store.set_oauth_session({"state": state})
            auth_url = build_oauth_authorization_url(
                client_id, redirect_uri, scopes, state)
            st.link_button("Connect to Atlassian", auth_url, use_container_width=True)
            st.caption("OAuth tokens remain in this Streamlit session and are not exported.")
        else:
            resources = list(oauth_session.get("resources") or [])
            if not resources:
                st.error("No accessible Jira Cloud sites were returned by Atlassian.")
            else:
                selected_idx = st.selectbox(
                    "Jira site",
                    options=range(len(resources)),
                    format_func=lambda i: f"{resources[i].get('name', '')} — {resources[i].get('url', '')}",
                )
                resource = resources[selected_idx]
                cloud_id = str(resource.get("id") or "")
                site_url = str(resource.get("url") or "")
                board_id = int(st.number_input(
                    "Board ID", min_value=0, step=1,
                    value=int(connection.board_id or 0),
                    help="Use the numeric ID from the Jira board URL.",
                ))
                client = JiraCloudClient.with_oauth(cloud_id, access_token)
                connection = JiraConnection(
                    auth_mode=auth_mode, site_url=site_url, cloud_id=cloud_id,
                    board_id=board_id or None,
                )
else:
    st.warning(
        "API-token mode is for a controlled internal proof of concept. "
        "The token is kept only in the password widget for this session."
    )
    c1, c2 = st.columns(2)
    with c1:
        site_url = st.text_input(
            "Jira Cloud site URL", value=connection.site_url,
            placeholder="https://<site>.atlassian.net",
        )
        account_email = st.text_input("Jira account email", value=connection.account_email)
    with c2:
        api_token = st.text_input("Jira API token", type="password")
        board_id = int(st.number_input(
            "Board ID", min_value=0, step=1, value=int(connection.board_id or 0),
            help="Use the numeric ID from the Jira board URL.",
        ))
    if site_url and account_email and api_token:
        try:
            client = JiraCloudClient.with_api_token(site_url, account_email, api_token)
            connection = JiraConnection(
                auth_mode=auth_mode, site_url=site_url, board_id=board_id or None,
                account_email=account_email,
            )
        except ValueError as exc:
            st.error(str(exc))

load_col, disconnect_col = st.columns(2)
with load_col:
    load_board_clicked = st.button(
        "Load board configuration", type="primary", use_container_width=True,
        disabled=client is None or board_id <= 0,
    )
with disconnect_col:
    if st.button("Disconnect and clear Jira session", use_container_width=True):
        jira_store.clear_connection_data()
        st.query_params.clear()
        st.rerun()

if load_board_clicked and client is not None:
    try:
        with st.spinner("Loading Jira board and Sprints…"):
            board, sprints = load_board(client, board_id)
        jira_store.set_connection(connection)
        jira_store.set_board(board)
        jira_store.set_sprints(sprints)
        st.success(f"Loaded board configuration and {len(sprints)} Sprint(s).")
    except (JiraApiError, ValueError) as exc:
        st.error(str(exc))

board = jira_store.get_board()
sprints = jira_store.get_sprints()
if board is None:
    st.stop()

st.divider()
st.subheader("Board contract")
b1, b2, b3 = st.columns(3)
b1.metric("Board", board.name or str(board.board_id))
b2.metric("Velocity unit", board.estimation_display_name)
b3.metric("Done statuses", len(board.done_status_ids))
if not board.done_status_ids:
    st.error("The board configuration did not expose a Done-column status mapping.")
if not board.estimation_field_id and board.estimation_type != "issueCount":
    st.warning("The board has no estimation field. Velocity estimates will be unavailable.")

if not sprints:
    st.warning("No Sprints were returned for this board.")
    st.stop()

sprint_idx = st.selectbox(
    "Sprint",
    options=range(len(sprints)),
    format_func=lambda i: f"{sprints[i].name} — {sprints[i].state}",
)
sprint = sprints[sprint_idx]

if st.button("Sync selected Sprint", type="primary", disabled=client is None):
    try:
        with st.spinner("Synchronizing Jira issues…"):
            issues = sync_sprint_issues(client, board, sprint)
        jira_store.set_issues(sprint.sprint_id, issues)
        jira_store.set_sync_status(JiraSyncStatus(
            synced_at=datetime.now(timezone.utc), board_id=board.board_id,
            sprint_id=sprint.sprint_id, issue_count=len(issues),
        ))
        st.success(f"Synchronized {len(issues)} issue(s) for {sprint.name}.")
    except (JiraApiError, ValueError) as exc:
        st.error(str(exc))

issues = jira_store.get_issues(sprint.sprint_id)
if not issues:
    st.info("Synchronize the Sprint to configure resource mappings and snapshots.")
    st.stop()

sync = jira_store.get_sync_status()
st.caption(
    f"Last sync: {sync.synced_at.isoformat() if sync.synced_at else 'Not found'} · "
    f"Issues: {len(issues)}"
)

st.divider()
st.subheader("Resource mapping")
st.caption("Map Jira account IDs to Saturn resources. Display names are not used as identifiers.")

existing = {m.jira_account_id: m for m in jira_store.get_mappings()}
assignees = {}
for issue in issues:
    if issue.assignee_account_id:
        assignees[issue.assignee_account_id] = issue.assignee_display_name

scenario = get_active_scenario()
resource_options = {"Not mapped": ("", "")}
if scenario:
    resource_options.update({
        f"{r.resource_id} — {r.display_name}": (r.resource_id, r.display_name)
        for r in scenario.resources
    })
resource_label_by_id = {
    resource_id: label
    for label, (resource_id, _resource_name) in resource_options.items()
}

map_df = pd.DataFrame([
    {
        "jira_account_id": account_id,
        "jira_display_name": display_name,
        "saturn_resource": resource_label_by_id.get(
            existing.get(account_id).resource_id if account_id in existing else "",
            "Not mapped",
        ),
    }
    for account_id, display_name in sorted(assignees.items(), key=lambda item: item[1].lower())
])
edited_map = st.data_editor(
    map_df, hide_index=True, use_container_width=True, disabled=["jira_account_id", "jira_display_name"],
    column_config={
        "jira_account_id": st.column_config.TextColumn("Jira account ID"),
        "jira_display_name": st.column_config.TextColumn("Jira assignee"),
        "saturn_resource": st.column_config.SelectboxColumn(
            "Saturn resource", options=list(resource_options.keys()),
        ),
    },
)
if st.button("Save resource mappings"):
    mappings = []
    for _, row in edited_map.iterrows():
        resource_label = str(row.get("saturn_resource") or "Not mapped")
        resource_id, resource_name = resource_options.get(resource_label, ("", ""))
        mappings.append(JiraResourceMapping(
            jira_account_id=str(row["jira_account_id"]),
            jira_display_name=str(row["jira_display_name"]),
            resource_id=resource_id,
            resource_name=resource_name,
        ))
    jira_store.set_mappings(mappings)
    st.success(f"Saved {len(mappings)} mapping record(s).")

st.divider()
st.subheader("Velocity boundary snapshots")
st.caption(
    "Capture at Sprint start and close to reproduce commitment/completed rules. "
    "A late capture is labeled reconstructed."
)
s1, s2 = st.columns(2)
with s1:
    if st.button("Capture Sprint-start snapshot", use_container_width=True):
        snap = capture_sprint_snapshot(sprint, board, issues, "start")
        jira_store.add_snapshot(snap)
        st.success("Sprint-start snapshot saved for this session.")
        for warning in snap.warnings:
            st.warning(warning)
with s2:
    if st.button("Capture Sprint-close snapshot", use_container_width=True):
        snap = capture_sprint_snapshot(sprint, board, issues, "close")
        jira_store.add_snapshot(snap)
        st.success("Sprint-close snapshot saved for this session.")
        for warning in snap.warnings:
            st.warning(warning)

snap_rows = [
    {
        "Sprint": snap.sprint_name,
        "Kind": snap.snapshot_kind,
        "Captured": snap.captured_at.isoformat(),
        "Source": snap.source,
        "Issues": len(snap.issue_estimates),
    }
    for snap in jira_store.get_snapshots()
]
if snap_rows:
    st.dataframe(pd.DataFrame(snap_rows), hide_index=True, use_container_width=True)

st.caption(
    "Community Cloud session state is not durable. Download this credential-free bundle "
    "after capturing snapshots, then upload it in a later session."
)
snapshot_bundle = export_snapshot_bundle(board, jira_store.get_snapshots())
backup_col, restore_col = st.columns(2)
with backup_col:
    st.download_button(
        "Download velocity snapshots",
        data=snapshot_bundle,
        file_name=f"jira-board-{board.board_id}-velocity-snapshots.json",
        mime="application/json",
        use_container_width=True,
        disabled=not jira_store.get_snapshots(),
    )
with restore_col:
    uploaded_bundle = st.file_uploader(
        "Restore velocity snapshots",
        type=["json"],
        label_visibility="collapsed",
    )
    if uploaded_bundle is not None and st.button(
        "Import uploaded snapshots", use_container_width=True,
    ):
        try:
            imported_board_id, imported_snapshots = import_snapshot_bundle(uploaded_bundle.read())
            if imported_board_id != board.board_id:
                st.error("Snapshot bundle board ID does not match the loaded Jira board.")
            else:
                for imported_snapshot in imported_snapshots:
                    jira_store.add_snapshot(imported_snapshot)
                st.success(f"Imported {len(imported_snapshots)} snapshot(s).")
                st.rerun()
        except ValueError as exc:
            st.error(str(exc))
