"""
Page 2 — Resources & Leave
Manage per-scenario resources (velocity, type, OT) and leave events.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import uuid
import streamlit as st
import pandas as pd
from src.storage.session_store import (
    get_sprint, get_active_scenario, get_active_scenario_id,
    get_scenarios, upsert_scenario, has_data,
)
from src.domain.models import ScenarioResource, LeaveEvent, LeaveStatus, ResourceType

st.set_page_config(page_title="Resources & Leave · Saturn Velocity", layout="wide")
st.title("👥 Resources & Leave")

if not has_data():
    st.warning("No sprint loaded. Go to **Home** to import or create a sprint.")
    st.stop()

sprint = get_sprint()
scenarios = get_scenarios()
sid_list = list(scenarios.keys())
sid_names = [scenarios[s].name for s in sid_list]

active_sid = get_active_scenario_id()
default_idx = sid_list.index(active_sid) if active_sid in sid_list else 0
chosen_idx = st.selectbox(
    "Active scenario", options=range(len(sid_list)),
    format_func=lambda i: sid_names[i], index=default_idx,
)
scenario = scenarios[sid_list[chosen_idx]]

st.subheader(f"Scenario: **{scenario.name}**")
tab_res, tab_leave = st.tabs(["Resources", "Leave Events"])

# ── Resources tab ─────────────────────────────────────────────────────────────
with tab_res:
    st.caption(
        "Edit velocity, OT and type per resource. "
        "V% defaults to 1.0 (100%). Values > 1 in the source workbook are normalised to fractions."
    )

    def resources_to_df(resources):
        if not resources:
            return pd.DataFrame(columns=[
                "display_name", "type", "velocity", "leave_days",
                "ot_hours", "ot_days", "v_percent", "others",
            ])
        return pd.DataFrame([
            {
                "display_name": r.display_name,
                "type": r.type.value,
                "velocity": r.velocity,
                "leave_days": r.leave_days,
                "ot_hours": r.ot_hours,
                "ot_days": r.ot_days,
                "v_percent": r.v_percent,
                "others": r.others,
            }
            for r in resources
        ])

    df = resources_to_df(scenario.resources)
    type_options = [t.value for t in ResourceType]

    edited = st.data_editor(
        df,
        num_rows="dynamic",
        use_container_width=True,
        column_config={
            "display_name": st.column_config.TextColumn("Name", required=True),
            "type": st.column_config.SelectboxColumn("Type", options=type_options),
            "velocity": st.column_config.NumberColumn("Velocity/day", min_value=0.0, format="%.2f"),
            "leave_days": st.column_config.NumberColumn("Leave days", min_value=0.0, format="%.1f"),
            "ot_hours": st.column_config.NumberColumn(
                "OT hours",
                help="CRITICAL DATA MISSING: hours→days rule (R-05). Informational only.",
                format="%.1f",
            ),
            "ot_days": st.column_config.NumberColumn("OT days", min_value=0.0, format="%.2f"),
            "v_percent": st.column_config.NumberColumn(
                "V %", min_value=0.0, max_value=1.0, format="%.2f",
                help="Fraction of velocity applied (0–1). 1.0 = 100%.",
            ),
            "others": st.column_config.NumberColumn("Others", format="%.2f"),
        },
        key=f"res_editor_{scenario.scenario_id}",
    )

    if st.button("💾 Save Resources", use_container_width=True, key="save_res"):
        new_resources = []
        for _, row in edited.iterrows():
            name = str(row.get("display_name", "")).strip()
            if not name:
                continue
            raw_type = str(row.get("type", "Dev"))
            try:
                rtype = ResourceType(raw_type)
            except ValueError:
                rtype = ResourceType.DEV
            new_resources.append(
                ScenarioResource(
                    scenario_id=scenario.scenario_id,
                    resource_id=str(uuid.uuid4())[:8],
                    display_name=name,
                    velocity=float(row.get("velocity", 1.0) or 1.0),
                    leave_days=float(row.get("leave_days", 0.0) or 0.0),
                    ot_hours=float(row.get("ot_hours", 0.0) or 0.0),
                    ot_days=float(row.get("ot_days", 0.0) or 0.0),
                    v_percent=float(row.get("v_percent", 1.0) or 1.0),
                    others=float(row.get("others", 0.0) or 0.0),
                    type=rtype,
                )
            )
        scenario.resources = new_resources
        upsert_scenario(scenario)
        st.success(f"Saved {len(new_resources)} resource(s).")

# ── Leave Events tab ──────────────────────────────────────────────────────────
with tab_leave:
    st.caption(
        "Track leave per resource. Use **TBD** status for unconfirmed dates "
        "(separates date semantics from TBD text per FR-03 / R-07)."
    )

    def leaves_to_df(events):
        if not events:
            return pd.DataFrame(columns=["resource_name", "date", "days", "status", "note"])
        return pd.DataFrame([
            {
                "resource_name": e.resource_name,
                "date": str(e.date) if e.date else "",
                "days": e.days,
                "status": e.status.value,
                "note": e.note,
            }
            for e in events
        ])

    ldf = leaves_to_df(scenario.leave_events)
    status_opts = [s.value for s in LeaveStatus]
    resource_names = [r.display_name for r in scenario.resources] or ["(add resources first)"]

    ledited = st.data_editor(
        ldf,
        num_rows="dynamic",
        use_container_width=True,
        column_config={
            "resource_name": st.column_config.SelectboxColumn(
                "Resource", options=resource_names,
            ),
            "date": st.column_config.TextColumn(
                "Date (YYYY-MM-DD)", help="Leave blank if TBD.",
            ),
            "days": st.column_config.NumberColumn("Leave days", min_value=0.0, format="%.1f"),
            "status": st.column_config.SelectboxColumn("Status", options=status_opts),
            "note": st.column_config.TextColumn("Note"),
        },
        key=f"leave_editor_{scenario.scenario_id}",
    )

    if st.button("💾 Save Leave Events", use_container_width=True, key="save_leave"):
        from datetime import date as dt_date
        new_events = []
        tbd_count = 0
        for _, row in ledited.iterrows():
            rname = str(row.get("resource_name", "")).strip()
            if not rname or rname == "(add resources first)":
                continue
            status_val = str(row.get("status", "Planned"))
            try:
                lstatus = LeaveStatus(status_val)
            except ValueError:
                lstatus = LeaveStatus.PLANNED

            date_str = str(row.get("date", "")).strip()
            parsed_date = None
            if date_str:
                try:
                    from datetime import datetime
                    parsed_date = datetime.strptime(date_str, "%Y-%m-%d").date()
                except ValueError:
                    lstatus = LeaveStatus.TBD
                    tbd_count += 1

            if lstatus == LeaveStatus.TBD:
                parsed_date = None

            # Match resource_id from resources list
            rid = next(
                (r.resource_id for r in scenario.resources if r.display_name == rname),
                str(uuid.uuid4())[:8],
            )
            new_events.append(
                LeaveEvent(
                    resource_id=rid,
                    resource_name=rname,
                    date=parsed_date,
                    days=float(row.get("days", 0.0) or 0.0),
                    status=lstatus,
                    note=str(row.get("note", "")),
                )
            )
        scenario.leave_events = new_events
        upsert_scenario(scenario)
        msg = f"Saved {len(new_events)} leave event(s)."
        if tbd_count:
            msg += f" {tbd_count} event(s) set to TBD (unparseable date)."
        st.success(msg)

    tbd_events = [e for e in scenario.leave_events if e.status == LeaveStatus.TBD]
    if tbd_events:
        st.warning(
            f"⚠ {len(tbd_events)} leave event(s) have TBD status — "
            "these are excluded from capacity calculation until a date is confirmed (FR-03)."
        )
