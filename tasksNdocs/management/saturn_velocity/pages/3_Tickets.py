"""
Page 3 — Tickets
Optional ticket grid. Not wired to capacity engine until approved binding rules exist (FR-09 / R-06).
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import uuid
import streamlit as st
import pandas as pd
from src.storage.session_store import (
    get_active_scenario, get_active_scenario_id,
    get_scenarios, upsert_scenario, has_data,
)
from src.domain.models import Ticket

st.set_page_config(page_title="Tickets · Saturn Velocity", layout="wide")
st.title("🎫 Tickets")

st.info(
    "**Out-of-capacity scope (FR-09 / R-06):** "
    "Tickets can be imported and exported here but do NOT affect capacity calculations "
    "until approved binding rules are established. "
    "This mirrors the workbook where A3:K35 contains headers only."
)

if not has_data():
    st.warning("No sprint loaded. Go to **Home** to import or create a sprint.")
    st.stop()

scenarios = get_scenarios()
sid_list = list(scenarios.keys())
active_sid = get_active_scenario_id()
default_idx = sid_list.index(active_sid) if active_sid in sid_list else 0
chosen_idx = st.selectbox(
    "Scenario", options=range(len(sid_list)),
    format_func=lambda i: scenarios[sid_list[i]].name,
    index=default_idx,
)
scenario = scenarios[sid_list[chosen_idx]]

# ── Grid ──────────────────────────────────────────────────────────────────────

def tickets_to_df(tickets):
    if not tickets:
        return pd.DataFrame(columns=[
            "ticket_id", "dev", "qc", "category", "assignee",
            "summary", "priority", "point", "status",
            "added_after_sprint_start", "note",
        ])
    return pd.DataFrame([
        {
            "ticket_id": t.ticket_id,
            "dev": t.dev,
            "qc": t.qc,
            "category": t.category,
            "assignee": t.assignee,
            "summary": t.summary,
            "priority": t.priority,
            "point": t.point,
            "status": t.status,
            "added_after_sprint_start": t.added_after_sprint_start,
            "note": t.note,
        }
        for t in tickets
    ])


df = tickets_to_df(scenario.tickets)

edited = st.data_editor(
    df,
    num_rows="dynamic",
    use_container_width=True,
    column_config={
        "ticket_id": st.column_config.TextColumn("ID"),
        "dev": st.column_config.TextColumn("Dev"),
        "qc": st.column_config.TextColumn("QC"),
        "category": st.column_config.TextColumn("Category"),
        "assignee": st.column_config.TextColumn("Assignee"),
        "summary": st.column_config.TextColumn("Summary"),
        "priority": st.column_config.SelectboxColumn(
            "Priority", options=["Critical", "High", "Medium", "Low", ""],
        ),
        "point": st.column_config.NumberColumn("Point", min_value=0.0, format="%.1f"),
        "status": st.column_config.TextColumn("Status"),
        "added_after_sprint_start": st.column_config.CheckboxColumn("Added after start?"),
        "note": st.column_config.TextColumn("Note"),
    },
    key=f"ticket_editor_{scenario.scenario_id}",
)

col_save, col_dl = st.columns([1, 1])
with col_save:
    if st.button("💾 Save Tickets", use_container_width=True):
        new_tickets = []
        for _, row in edited.iterrows():
            summary = str(row.get("summary", "")).strip()
            if not summary:
                continue
            new_tickets.append(
                Ticket(
                    ticket_id=str(row.get("ticket_id", "")).strip() or str(uuid.uuid4())[:8],
                    dev=str(row.get("dev", "")),
                    qc=str(row.get("qc", "")),
                    category=str(row.get("category", "")),
                    assignee=str(row.get("assignee", "")),
                    summary=summary,
                    priority=str(row.get("priority", "")),
                    point=float(row.get("point", 0.0) or 0.0),
                    status=str(row.get("status", "")),
                    added_after_sprint_start=bool(row.get("added_after_sprint_start", False)),
                    note=str(row.get("note", "")),
                )
            )
        scenario.tickets = new_tickets
        upsert_scenario(scenario)
        st.success(f"Saved {len(new_tickets)} ticket(s).")

with col_dl:
    if scenario.tickets:
        csv_data = tickets_to_df(scenario.tickets).to_csv(index=False).encode("utf-8")
        st.download_button(
            "⬇ Download CSV",
            data=csv_data,
            file_name=f"tickets_{scenario.name}.csv",
            mime="text/csv",
            use_container_width=True,
        )

if scenario.tickets:
    st.caption(f"{len(scenario.tickets)} ticket(s) stored. Not included in capacity calculation.")
