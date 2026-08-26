"""
Page 1 — Sprint Setup
Edit sprint parameters and rule-set constants.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import streamlit as st
from src.storage.session_store import get_sprint, set_sprint, has_sprint, get_ruleset, set_ruleset
from src.domain.models import Sprint, RuleSet
from datetime import date

st.set_page_config(page_title="Sprint Setup · Saturn Velocity", layout="wide")
st.title("🗓️ Sprint Setup")

if not has_sprint():
    st.warning("No sprint loaded. Go to **Home** to import a workbook or create a sprint.")
    st.stop()

sprint = get_sprint()
rs = get_ruleset()

st.subheader(f"Editing: **{sprint.name}**")

with st.form("sprint_setup_form"):
    col1, col2 = st.columns(2)
    with col1:
        sprint_name = st.text_input("Sprint name", value=sprint.name)
        start_d = st.date_input("Start date", value=sprint.start_date)
        end_d = st.date_input("Sprint end date", value=sprint.end_date)
        dev_end_d = st.date_input("Development end date", value=sprint.development_end_date,
                                  help="The cutoff used for BR-01 dev_days (Development Days end).")
    with col2:
        public_h = st.number_input("Public holidays", min_value=0, max_value=30,
                                   value=sprint.public_holidays, step=1)
        buffer_v = st.number_input("Buffer (0–1)", min_value=0.0, max_value=1.0,
                                   value=sprint.buffer, step=0.01, format="%.2f",
                                   help="BR-06: V = FTE × (1 − buffer)")
        backup_v = st.number_input("Backup days", min_value=0.0, max_value=10.0,
                                   value=sprint.backup, step=0.5,
                                   help="Subtracted from dev_days in BR-01.")
        fixed_dd = st.number_input(
            "Fixed day deduction",
            min_value=0.0, max_value=20.0,
            value=sprint.fixed_day_deduction, step=0.5,
            help="The unexplained constant '3' from WB-s192-CALC (R-04). "
                 "Business meaning is CRITICAL DATA MISSING; expose as named setting.",
        )

    st.markdown("---")
    st.markdown("**Rule-set parameters**")
    col3, col4 = st.columns(2)
    with col3:
        rule_version = st.text_input("Rule version", value=rs.rule_version)
        hours_per_day = st.number_input(
            "Hours per day (OT conversion)",
            min_value=1.0, max_value=24.0, value=rs.hours_per_day, step=0.5,
            help="CRITICAL DATA MISSING: OT hours→days conversion rule (R-05). "
                 "Used for informational display; not yet wired to capacity formula.",
        )
    with col4:
        effective_status = st.selectbox(
            "Rule effective status",
            options=["draft", "approved", "deprecated"],
            index=["draft", "approved", "deprecated"].index(rs.effective_status),
        )

    saved = st.form_submit_button("💾 Save Sprint & Rule-set", use_container_width=True)

if saved:
    if end_d < start_d:
        st.error("End date must be on or after start date.")
    elif dev_end_d < start_d:
        st.error("Development end must be on or after start date.")
    elif not sprint_name.strip():
        st.error("Sprint name is required.")
    else:
        updated = Sprint(
            sprint_id=sprint.sprint_id,
            name=sprint_name.strip(),
            start_date=start_d,
            end_date=end_d,
            development_end_date=dev_end_d,
            public_holidays=int(public_h),
            buffer=buffer_v,
            backup=backup_v,
            fixed_day_deduction=fixed_dd,
        )
        set_sprint(updated)
        set_ruleset(RuleSet(
            rule_version=rule_version.strip() or "s192-baseline",
            hours_per_day=hours_per_day,
            fixed_day_deduction=fixed_dd,
            effective_status=effective_status,
        ))
        st.success("Sprint and rule-set saved.")

# ── Read-only summary ─────────────────────────────────────────────────────────
sprint = get_sprint()  # re-read after potential save
st.divider()
st.subheader("Current Sprint Parameters")
c1, c2, c3, c4 = st.columns(4)
c1.metric("Start", str(sprint.start_date))
c2.metric("Dev End", str(sprint.development_end_date))
c3.metric("Sprint End", str(sprint.end_date))
c4.metric("Public Holidays", sprint.public_holidays)
c5, c6, c7, c8 = st.columns(4)
c5.metric("Buffer", f"{sprint.buffer:.0%}")
c6.metric("Backup (days)", sprint.backup)
c7.metric("Fixed Day Deduction", sprint.fixed_day_deduction)
c8.metric("Rule Version", get_ruleset().rule_version)

st.caption(
    "⚠️ **fixed_day_deduction** = 3 is an undocumented constant from WB-s192-CALC (Risk R-04). "
    "Business sign-off required before locking this value."
)
