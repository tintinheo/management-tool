"""
Saturn Velocity — Home / Import page
Entrypoint for Streamlit Community Cloud (streamlit_app.py at repository root).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import uuid
import streamlit as st

from src.storage.session_store import (
    get_sprint, get_scenarios, get_active_scenario_id,
    set_sprint, upsert_scenario, set_active_scenario_id,
    has_data, create_blank_scenario, get_ruleset,
)
from src.domain.models import Sprint, ResourceType
from src.importers.excel_importer import import_workbook
from datetime import date

st.set_page_config(
    page_title="Saturn Velocity",
    page_icon="🚀",
    layout="wide",
    initial_sidebar_state="expanded",
)

# ── Sidebar navigation helper ────────────────────────────────────────────────
st.sidebar.title("🚀 Saturn Velocity")
st.sidebar.caption("Sprint Capacity Planning")
if has_data():
    sprint = get_sprint()
    st.sidebar.success(f"✅ Sprint: **{sprint.name}**")
    scenarios = get_scenarios()
    active_sid = get_active_scenario_id()
    if scenarios:
        active_name = scenarios.get(active_sid, next(iter(scenarios.values()))).name
        st.sidebar.info(f"📋 Scenario: **{active_name}**")
else:
    st.sidebar.warning("No data loaded. Create a sprint or import an XLSX file.")

st.title("🚀 Saturn Velocity")
st.subheader("Sprint Capacity Planning Tool")

st.markdown(
    """
    **Saturn Velocity** calculates sprint capacity from team resource and leave data,
    implements BR-01 – BR-10 from the WB-s192 baseline, supports multiple scenarios,
    exports audit-ready snapshots, and can synchronize Jira Cloud for workload and
    team-velocity analysis.

    Use **Jira Integration** to connect a board and sync a Sprint. Use
    **Jira Workload & Velocity** for time-based personal demand and board-based team
    velocity. Story points are not converted into personal hours.
    """
)

st.divider()

col_import, col_new = st.columns(2, gap="large")

# ── Import XLSX ───────────────────────────────────────────────────────────────
with col_import:
    st.header("📂 Import from XLSX")
    st.caption("Upload a Saturn Velocity workbook. Each eligible sheet becomes a scenario.")

    uploaded = st.file_uploader(
        "Choose an XLSX file", type=["xlsx"], key="home_upload",
        help="Supports s192 schema (latest). s158 sheets import sprint metadata only."
    )
    if uploaded is not None:
        if st.button("▶ Import Workbook", use_container_width=True):
            with st.spinner("Importing…"):
                result = import_workbook(uploaded.read())

            sprints = result["sprints"]
            scenarios = result["scenarios"]
            report = result["import_report"]

            if not sprints:
                st.error("No sprint data could be extracted from the workbook.")
            else:
                # Use the last (most recent) sheet as the active sprint/scenario
                for sp, sc in zip(sprints, scenarios):
                    upsert_scenario(sc)
                # Set sprint from last sheet
                set_sprint(sprints[-1])
                # Set active to last scenario
                if scenarios:
                    set_active_scenario_id(scenarios[-1].scenario_id)

                st.success(f"Imported {len(sprints)} sheet(s) as scenarios.")

                with st.expander("Import report", expanded=True):
                    for se in report["sheets"]:
                        status_icon = "✅" if se["status"] == "ok" else "⚠️"
                        schema_label = se["schema"].upper() if se["schema"] != "unknown" else "Unknown schema"
                        st.markdown(
                            f"{status_icon} **{se['sheet']}** — {schema_label} — "
                            f"{se.get('resources_imported', 0)} resource(s)"
                        )
                        for w in se.get("warnings", []):
                            st.caption(f"  ⚠ {w}")

# ── Create new sprint ─────────────────────────────────────────────────────────
with col_new:
    st.header("✏️ Create New Sprint")
    st.caption("Start from scratch with a blank sprint.")

    with st.form("new_sprint_form"):
        sprint_name = st.text_input("Sprint name", value="s192", placeholder="e.g. s193")
        col_a, col_b = st.columns(2)
        with col_a:
            start_d = st.date_input("Start date", value=date.today())
            dev_end_d = st.date_input("Development end", value=date.today())
        with col_b:
            end_d = st.date_input("End date", value=date.today())
            public_h = st.number_input("Public holidays", min_value=0, max_value=30, value=0, step=1)
        col_c, col_d = st.columns(2)
        with col_c:
            buffer_v = st.number_input("Buffer (0–1)", min_value=0.0, max_value=1.0, value=0.1, step=0.01, format="%.2f")
        with col_d:
            backup_v = st.number_input("Backup days", min_value=0.0, max_value=10.0, value=1.0, step=0.5)

        fixed_dd = st.number_input(
            "Fixed day deduction *(undocumented constant from WB-s192-CALC)*",
            min_value=0.0, max_value=10.0, value=3.0, step=0.5,
            help="The literal '3' subtracted in BR-01. Meaning is CRITICAL DATA MISSING — expose as named setting per R-04.",
        )

        scenario_name = st.text_input("Baseline scenario name", value="Baseline")
        submitted = st.form_submit_button("Create Sprint", use_container_width=True)

    if submitted:
        if not sprint_name.strip():
            st.error("Sprint name is required.")
        elif end_d < start_d:
            st.error("End date must be on or after start date.")
        elif dev_end_d < start_d:
            st.error("Development end must be on or after start date.")
        else:
            new_sprint = Sprint(
                sprint_id=str(uuid.uuid4())[:8],
                name=sprint_name.strip(),
                start_date=start_d,
                end_date=end_d,
                development_end_date=dev_end_d,
                public_holidays=int(public_h),
                buffer=buffer_v,
                backup=backup_v,
                fixed_day_deduction=fixed_dd,
            )
            set_sprint(new_sprint)
            sc = create_blank_scenario(new_sprint.sprint_id, scenario_name.strip() or "Baseline")
            upsert_scenario(sc)
            st.success(f"Sprint **{sprint_name}** created with scenario **{sc.name}**.")
            st.info("👈 Go to **Sprint Setup** in the sidebar to review or adjust parameters.")

# ── Session status ─────────────────────────────────────────────────────────────
if has_data():
    st.divider()
    st.subheader("Current Session")
    sprint = get_sprint()
    scenarios = get_scenarios()
    rs = get_ruleset()
    scols = st.columns(4)
    scols[0].metric("Sprint", sprint.name)
    scols[1].metric("Scenarios loaded", len(scenarios))
    scols[2].metric("Buffer", f"{sprint.buffer:.0%}")
    scols[3].metric("Rule version", rs.rule_version)
