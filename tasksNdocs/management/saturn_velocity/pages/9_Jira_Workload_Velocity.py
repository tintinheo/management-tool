"""Jira-backed workload and velocity dashboard."""
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd
import streamlit as st

from src.application.jira_services import (
    average_completed_velocity, build_saturn_capacity_hours, calculate_velocity,
    calculate_workload,
)
from src.storage import jira_store
from src.storage.session_store import (
    get_active_scenario, get_ruleset, get_sprint, has_data,
)


st.set_page_config(page_title="Jira Workload & Velocity · Saturn Velocity", layout="wide")
st.title("📈 Jira Workload & Velocity")
st.caption("Jira delivery demand reconciled with approved Saturn capacity.")

board = jira_store.get_board()
sprints = jira_store.get_sprints()
synced_sprints = [s for s in sprints if jira_store.get_issues(s.sprint_id)]
if board is None or not synced_sprints:
    st.warning("No synchronized Jira Sprint. Use **Jira Integration** first.")
    st.stop()

sprint_idx = st.selectbox(
    "Synchronized Sprint",
    options=range(len(synced_sprints)),
    format_func=lambda i: f"{synced_sprints[i].name} — {synced_sprints[i].state}",
)
jira_sprint = synced_sprints[sprint_idx]
issues = jira_store.get_issues(jira_sprint.sprint_id)
mappings = jira_store.get_mappings()

k1, k2, k3, k4 = st.columns(4)
k1.metric("Jira issues", len(issues))
k2.metric("Unassigned", len([i for i in issues if not i.assignee_account_id and not i.done]))
k3.metric("Without board estimate", len([i for i in issues if i.estimate is None and not i.is_subtask]))
k4.metric("Velocity unit", board.estimation_display_name)

st.divider()
st.subheader("Individual workload")
st.info(
    "Workload uses Jira **time tracking**, not story points. Missing remaining/original "
    "estimates are shown as unknown demand and are never treated as zero."
)

demand_label = st.radio(
    "Demand basis",
    ["Remaining estimate", "Original estimate"],
    horizontal=True,
)
demand_mode = "remaining" if demand_label == "Remaining estimate" else "original"

capacity_by_resource = {}
capacity_warnings = []
saturn_sprint = get_sprint() if has_data() else None
scenario = get_active_scenario() if has_data() else None
ruleset = get_ruleset()

use_capacity = False
if saturn_sprint and scenario:
    use_capacity = st.checkbox(
        f"Reconcile against active Saturn capacity: {saturn_sprint.name} / {scenario.name}",
        value=False,
        help="Enable only after confirming that this Saturn scenario represents the selected Jira Sprint.",
    )
else:
    st.warning("No active Saturn Sprint/scenario; workload demand will be shown without utilization.")

if use_capacity:
    as_of = st.date_input(
        "Capacity as-of date",
        value=date.today(),
        help="Explicit status date; no historical calculation uses the server clock implicitly.",
    )
    include_ot = st.checkbox(
        "Include approved OT in full-Sprint capacity",
        value=False,
        disabled=demand_mode == "remaining",
        help="Remaining-capacity mode excludes OT because the source has no OT dates.",
    )
    capacity_by_resource, capacity_warnings = build_saturn_capacity_hours(
        saturn_sprint, scenario, ruleset,
        as_of_date=as_of,
        remaining=demand_mode == "remaining",
        include_ot=include_ot,
    )

workload = calculate_workload(
    issues,
    mappings,
    demand_mode=demand_mode,
    capacity_hours_by_resource=capacity_by_resource,
)
for warning in capacity_warnings + workload.warnings:
    st.warning(warning)

if workload.rows:
    workload_df = pd.DataFrame([
        {
            "Jira assignee": row.jira_display_name,
            "Saturn resource": row.resource_name or "Not mapped",
            "Open issues": row.issue_count,
            "Unknown estimates": row.unestimated_issue_count,
            "Demand hours": row.demand_hours,
            "Capacity hours": row.capacity_hours,
            "Utilization %": None if row.utilization is None else row.utilization * 100.0,
            "Over allocated": row.over_allocated,
        }
        for row in workload.rows
    ])
    st.dataframe(
        workload_df,
        hide_index=True,
        use_container_width=True,
        column_config={
            "Demand hours": st.column_config.NumberColumn(format="%.1f"),
            "Capacity hours": st.column_config.NumberColumn(format="%.1f"),
            "Utilization %": st.column_config.NumberColumn(format="%.1f%%"),
            "Over allocated": st.column_config.CheckboxColumn(),
        },
    )
else:
    st.info("No open assigned issues were available for workload calculation.")

with st.expander("Workload data quality"):
    st.write("Unknown estimate issues:", workload.unknown_estimate_issue_keys or "None")
    st.write("Unassigned issues:", workload.unassigned_issue_keys or "None")
    st.write("Unmapped Jira accounts:", workload.unmapped_account_ids or "None")

st.divider()
st.subheader("Team velocity")
st.caption(
    "Commitment comes from the Sprint-start snapshot. Completed work comes from the "
    "Sprint-close snapshot and follows the board's estimation and Done-column configuration."
)

velocity_results = []
for sprint in sprints:
    start = jira_store.get_snapshot(sprint.sprint_id, "start")
    close = jira_store.get_snapshot(sprint.sprint_id, "close")
    if start and close:
        try:
            velocity_results.append(calculate_velocity(start, close))
        except ValueError as exc:
            st.error(f"{sprint.name}: {exc}")

if velocity_results:
    velocity_df = pd.DataFrame([
        {
            "Sprint": result.sprint_name,
            "Commitment": result.commitment,
            "Completed": result.completed,
            "Scope added": result.scope_added,
            "Scope removed": result.scope_removed,
            "Evidence": (
                "Boundary snapshots"
                if result.start_source == result.close_source == "boundary_snapshot"
                else "Reconstructed"
            ),
        }
        for result in velocity_results
    ])
    st.dataframe(velocity_df, hide_index=True, use_container_width=True)
    st.bar_chart(velocity_df.set_index("Sprint")[["Commitment", "Completed"]])

    window = int(st.number_input(
        "Historical Sprints in average",
        min_value=0,
        max_value=len(velocity_results),
        value=0,
        step=1,
        help="Leave zero until the team approves its velocity averaging window.",
    ))
    average = average_completed_velocity(velocity_results, window)
    if average is None:
        st.info("Average velocity is not published until a historical window is approved.")
    else:
        st.metric(f"Average completed velocity — {window} Sprint(s)", average)

    warnings = [w for result in velocity_results for w in result.warnings]
    for warning in dict.fromkeys(warnings):
        st.warning(warning)
else:
    st.info(
        "No Sprint has both start and close snapshots. Capture them from **Jira Integration**."
    )

st.divider()
st.subheader("Delivery data quality")
q1, q2, q3, q4 = st.columns(4)
q1.metric("Done", len([i for i in issues if i.done]))
q2.metric("Open", len([i for i in issues if not i.done]))
q3.metric("Missing time estimate", len(workload.unknown_estimate_issue_keys))
q4.metric("Missing resource mapping", len(workload.unmapped_account_ids))
