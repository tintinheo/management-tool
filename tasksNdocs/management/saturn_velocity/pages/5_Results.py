"""
Page 5 — Results
Dashboard: KPI cards, resource breakdown, formula trace (FR-05, FR-07).
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import streamlit as st
import pandas as pd
from datetime import date
from src.storage.session_store import (
    get_sprint, get_active_scenario, get_scenarios,
    get_active_scenario_id, set_active_scenario_id,
    has_data, get_ruleset, add_snapshot,
)
from src.application.services import calculate_scenario, create_snapshot

st.set_page_config(page_title="Results · Saturn Velocity", layout="wide")
st.title("📊 Results")

if not has_data():
    st.warning("No sprint loaded. Go to **Home** to import or create a sprint.")
    st.stop()

sprint = get_sprint()
ruleset = get_ruleset()
scenarios = get_scenarios()
sid_list = list(scenarios.keys())
active_sid = get_active_scenario_id()
default_idx = sid_list.index(active_sid) if active_sid in sid_list else 0

chosen_idx = st.selectbox(
    "Scenario to view",
    options=range(len(sid_list)),
    format_func=lambda i: scenarios[sid_list[i]].name,
    index=default_idx,
)
scenario = scenarios[sid_list[chosen_idx]]

col_date, col_calc = st.columns([2, 1])
with col_date:
    as_of = st.date_input(
        "As-of date (BR-02)",
        value=date.today(),
        help="Replaces Excel's TODAY() for deterministic snapshot replay (TR-03).",
    )
with col_calc:
    st.write("")
    st.write("")
    run = st.button("▶ Calculate", use_container_width=True, type="primary")

if "last_result" not in st.session_state or run:
    if not scenario.resources:
        st.warning("No resources configured. Go to **Resources & Leave** to add team members.")
        st.stop()
    result = calculate_scenario(sprint, scenario, ruleset, as_of_date=as_of)
    st.session_state["last_result"] = result
    st.session_state["last_scenario"] = scenario
else:
    result = st.session_state["last_result"]
    scenario = st.session_state.get("last_scenario", scenario)

# ── Errors / Warnings banner ──────────────────────────────────────────────────
errors = [w for w in result.warnings if w.startswith("ERROR")]
warnings = [w for w in result.warnings if not w.startswith("ERROR")]
if errors:
    for e in errors:
        st.error(e)
if warnings:
    for w in warnings:
        st.warning(w)

if not result.has_errors:
    # ── KPI cards ───────────────────────────────────────────────────────────────
    st.subheader("Sprint KPIs")
    k1, k2, k3, k4 = st.columns(4)
    k1.metric("Dev Days", result.dev_days, help="BR-01: NETWORKDAYS − fixed_deduction − backup")
    k2.metric("Remaining Dev Days", result.remaining_dev_days, help="BR-02: NETWORKDAYS(as_of, dev_end)")
    k3.metric("Buffer Days", result.buffer_days, help="BR-10: buffer × dev_days")
    k4.metric("Rule Version", result.rule_version)

    st.subheader("Team Capacity")
    t1, t2, t3, t4, t5, t6 = st.columns(6)
    t1.metric("Full Dev V", result.full_dev_v)
    t2.metric("Dev V ★", result.dev_v, help="Buffered Dev velocity without OT")
    t3.metric("Full QC V", result.full_qc_v)
    t4.metric("QC V ★", result.qc_v, help="Buffered QC velocity without OT")
    delta_color = "normal" if result.qc_minus_dev >= 0 else "inverse"
    t5.metric("Team Velocity (Biz)", result.team_velocity_biz, help="BR-08: MIN(Dev V, QC V)")
    t6.metric("QC − Dev", result.qc_minus_dev, delta=f"{result.qc_minus_dev:+.3f}",
              delta_color=delta_color, help="BR-09: QC V − Dev V")

    # ── Resource breakdown ───────────────────────────────────────────────────────
    st.subheader("Resource Breakdown")
    if result.resource_results:
        rdf = pd.DataFrame([
            {
                "Resource": rr.display_name,
                "Type": rr.type.value,
                "Leave Days": rr.leave_days,
                "OT Days": rr.ot_days,
                "FTE noOT [BR-04]": rr.fte_no_ot,
                "Full V [BR-05]": rr.full_v,
                "V [BR-06]": rr.v,
                "V OT [BR-06]": rr.v_ot,
            }
            for rr in result.resource_results
        ])
        st.dataframe(rdf, use_container_width=True, hide_index=True)
    else:
        st.info("No resource results.")

    # ── Formula trace ─────────────────────────────────────────────────────────────
    with st.expander("🔍 Formula Trace"):
        rs = get_ruleset()
        st.markdown(f"""
| Rule | Formula | Inputs | Result |
|------|---------|--------|--------|
| BR-01 | `NETWORKDAYS(start, dev_end, holidays) − fixed_deduction − backup` | start={sprint.start_date}, dev_end={sprint.development_end_date}, holidays={sprint.public_holidays}, deduction={sprint.fixed_day_deduction}, backup={sprint.backup} | **{result.dev_days}** |
| BR-02 | `NETWORKDAYS(as_of, dev_end)` | as_of={as_of}, dev_end={sprint.development_end_date} | **{result.remaining_dev_days}** |
| BR-03 | `sum(leave_days)` per resource | — | see table above |
| BR-04 | `velocity × (dev_days − leave) × v_pct` | — | see table above |
| BR-05 | `velocity × (dev_days − leave + ot_days) × v_pct` | — | see table above |
| BR-06 | `v = FTE × (1 − buffer)` | buffer={sprint.buffer} | see table above |
| BR-07 | `sum(v) per type` | — | Dev V={result.dev_v}, QC V={result.qc_v} |
| BR-08 | `min(Dev V, QC V)` | Dev V={result.dev_v}, QC V={result.qc_v} | **{result.team_velocity_biz}** |
| BR-09 | `QC V − Dev V` | — | **{result.qc_minus_dev}** |
| BR-10 | `buffer × dev_days` | — | **{result.buffer_days}** |

Rule version: `{result.rule_version}` · As-of: `{result.as_of_date}`
        """)

    # ── Lock snapshot ────────────────────────────────────────────────────────────
    st.divider()
    if errors:
        st.error("Cannot lock snapshot: ERRORs present. Fix inputs first.")
    else:
        if warnings:
            st.warning("Snapshot has warnings. Review before locking.")
        if st.button("🔒 Lock Snapshot", help="Freeze inputs + outputs for audit (FR-12)"):
            snap = create_snapshot(sprint, scenario, result)
            add_snapshot(snap)
            st.success(f"Snapshot **{snap.snapshot_id}** locked. Download it from **Validation & Export**.")
else:
    st.error("Calculation errors prevent displaying results. Fix the inputs on Sprint Setup or Resources & Leave.")
