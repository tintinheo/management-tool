"""
Page 4 — Scenarios
Clone, rename, delete scenarios and compare their results side-by-side (FR-06).
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import streamlit as st
import pandas as pd
from src.storage.session_store import (
    get_sprint, get_scenarios, get_active_scenario_id,
    set_active_scenario_id, upsert_scenario, delete_scenario,
    has_data, get_ruleset,
)
from src.application.services import clone_scenario, compare_scenarios
from datetime import date

st.set_page_config(page_title="Scenarios · Saturn Velocity", layout="wide")
st.title("🔀 Scenarios")

if not has_data():
    st.warning("No sprint loaded. Go to **Home** to import or create a sprint.")
    st.stop()

sprint = get_sprint()
scenarios = get_scenarios()
ruleset = get_ruleset()
sid_list = list(scenarios.keys())
active_sid = get_active_scenario_id()

# ── Scenario management ────────────────────────────────────────────────────────
st.subheader("Manage Scenarios")

sc_col1, sc_col2 = st.columns([2, 1])
with sc_col1:
    default_idx = sid_list.index(active_sid) if active_sid in sid_list else 0
    chosen_idx = st.selectbox(
        "Active scenario",
        options=range(len(sid_list)),
        format_func=lambda i: scenarios[sid_list[i]].name,
        index=default_idx,
        key="sc_active_select",
    )
    chosen_sid = sid_list[chosen_idx]
    if chosen_sid != active_sid:
        set_active_scenario_id(chosen_sid)

with sc_col2:
    with st.expander("➕ Clone scenario"):
        clone_from_idx = st.selectbox(
            "Clone from",
            options=range(len(sid_list)),
            format_func=lambda i: scenarios[sid_list[i]].name,
            key="clone_from",
        )
        new_name = st.text_input("New scenario name", value=f"{scenarios[sid_list[clone_from_idx]].name}_copy")
        if st.button("Clone", use_container_width=True):
            src = scenarios[sid_list[clone_from_idx]]
            cloned = clone_scenario(src, new_name.strip() or f"{src.name}_copy")
            upsert_scenario(cloned)
            set_active_scenario_id(cloned.scenario_id)
            st.success(f"Cloned as **{cloned.name}**.")
            st.rerun()

    with st.expander("✏️ Rename active scenario"):
        current_sc = scenarios.get(chosen_sid)
        rename_val = st.text_input("New name", value=current_sc.name if current_sc else "")
        if st.button("Rename", use_container_width=True):
            if current_sc and rename_val.strip():
                current_sc.name = rename_val.strip()
                upsert_scenario(current_sc)
                st.success("Renamed.")
                st.rerun()

    if len(sid_list) > 1:
        with st.expander("🗑️ Delete scenario"):
            del_idx = st.selectbox(
                "Delete which scenario",
                options=range(len(sid_list)),
                format_func=lambda i: scenarios[sid_list[i]].name,
                key="del_select",
            )
            del_sid = sid_list[del_idx]
            if st.button("Delete", use_container_width=True, type="primary"):
                delete_scenario(del_sid)
                st.warning(f"Scenario deleted.")
                st.rerun()

# ── Scenario overview table ────────────────────────────────────────────────────
st.divider()
st.subheader("All Scenarios")
overview_rows = []
for s in scenarios.values():
    overview_rows.append({
        "Name": s.name,
        "Resources": len(s.resources),
        "Leave events": len(s.leave_events),
        "Tickets": len(s.tickets),
        "Base scenario": s.base_scenario_id or "—",
        "Rule version": s.rule_version,
    })
st.dataframe(pd.DataFrame(overview_rows), use_container_width=True, hide_index=True)

# ── Side-by-side comparison ────────────────────────────────────────────────────
st.divider()
st.subheader("📊 Compare Scenarios")

if len(scenarios) < 2:
    st.info("Clone at least one scenario to compare.")
else:
    cmp_options = range(len(sid_list))
    cmp_selections = st.multiselect(
        "Select scenarios to compare",
        options=cmp_options,
        format_func=lambda i: scenarios[sid_list[i]].name,
        default=list(cmp_options)[:min(3, len(cmp_options))],
    )

    if len(cmp_selections) >= 2:
        as_of = st.date_input("As-of date for comparison", value=date.today())
        selected_scenarios = [scenarios[sid_list[i]] for i in cmp_selections]

        pairs = compare_scenarios(sprint, selected_scenarios, ruleset, as_of_date=as_of)

        # Summary comparison table
        rows = []
        for sc, res in pairs:
            row = {
                "Scenario": sc.name,
                "Dev Days": res.dev_days,
                "Remaining Dev Days": res.remaining_dev_days,
                "Buffer Days": res.buffer_days,
                "Full Dev V": res.full_dev_v,
                "Dev V": res.dev_v,
                "Full QC V": res.full_qc_v,
                "QC V": res.qc_v,
                "Team Velocity (Biz)": res.team_velocity_biz,
                "QC − Dev": res.qc_minus_dev,
                "Warnings": len(res.warnings),
                "Has Errors": "❌ Yes" if res.has_errors else "✅ No",
            }
            rows.append(row)

        cmp_df = pd.DataFrame(rows)
        st.dataframe(cmp_df, use_container_width=True, hide_index=True)

        # Delta highlighting
        numeric_kpis = ["Dev V", "QC V", "Team Velocity (Biz)", "Full Dev V", "Full QC V"]
        base_vals = {k: rows[0][k] for k in numeric_kpis}
        delta_rows = []
        for row in rows[1:]:
            delta = {f"Δ {k} vs {rows[0]['Scenario']}": round(row[k] - base_vals[k], 3) for k in numeric_kpis}
            delta["Scenario"] = row["Scenario"]
            delta_rows.append(delta)

        if delta_rows:
            st.subheader("Deltas vs. first selected scenario")
            st.dataframe(pd.DataFrame(delta_rows), use_container_width=True, hide_index=True)

        # Per-resource breakdown
        with st.expander("Resource breakdown per scenario"):
            for sc, res in pairs:
                st.markdown(f"**{sc.name}**")
                if res.resource_results:
                    rdf = pd.DataFrame([
                        {
                            "Resource": rr.display_name,
                            "Type": rr.type.value,
                            "Leave": rr.leave_days,
                            "OT days": rr.ot_days,
                            "FTE noOT": rr.fte_no_ot,
                            "Full V": rr.full_v,
                            "V": rr.v,
                            "V OT": rr.v_ot,
                        }
                        for rr in res.resource_results
                    ])
                    st.dataframe(rdf, use_container_width=True, hide_index=True)
                else:
                    st.caption("No resources.")
