"""
Page 6 — Validation & Export
Warning acknowledgement, snapshot management and file downloads (FR-10, FR-11, FR-12).
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import json
import streamlit as st
import pandas as pd
from datetime import date
from src.storage.session_store import (
    get_sprint, get_scenarios, get_active_scenario_id,
    has_data, get_ruleset, get_snapshots, add_snapshot,
)
from src.application.services import calculate_scenario, create_snapshot, snapshot_to_json
from src.exporters.excel_exporter import result_to_xlsx, result_to_csv

st.set_page_config(page_title="Validation & Export · Saturn Velocity", layout="wide")
st.title("✅ Validation & Export")

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
    "Scenario to validate/export",
    options=range(len(sid_list)),
    format_func=lambda i: scenarios[sid_list[i]].name,
    index=default_idx,
)
scenario = scenarios[sid_list[chosen_idx]]

# ── Run validation ────────────────────────────────────────────────────────────
st.subheader("🔍 Validation Check")
as_of = st.date_input("As-of date", value=date.today())

if not scenario.resources:
    st.error("No resources configured. Go to Resources & Leave first.")
    st.stop()

result = calculate_scenario(sprint, scenario, ruleset, as_of_date=as_of)
errors = [w for w in result.warnings if w.startswith("ERROR")]
warnings_only = [w for w in result.warnings if not w.startswith("ERROR")]

if errors:
    st.error(f"**{len(errors)} ERROR(s)** — publish is BLOCKED until resolved:")
    for e in errors:
        st.error(f"• {e}")
elif warnings_only:
    st.warning(f"**{len(warnings_only)} WARNING(s)** — review before approving:")
    for w in warnings_only:
        st.warning(f"• {w}")
else:
    st.success("✅ No errors or warnings. Scenario is clean.")

# Reconciliation table
with st.expander("Reconciliation — calculated vs. expected"):
    st.caption(
        "Compare calculated results against reference values. "
        "CRITICAL DATA MISSING: numeric tolerance policy not defined (BRD §13)."
    )
    ref_dev_v = st.number_input("Reference Dev V (from workbook)", value=0.0, format="%.3f")
    ref_qc_v = st.number_input("Reference QC V (from workbook)", value=0.0, format="%.3f")
    if ref_dev_v or ref_qc_v:
        recon = [
            {"KPI": "Dev V", "Calculated": result.dev_v, "Reference": ref_dev_v, "Delta": round(result.dev_v - ref_dev_v, 3)},
            {"KPI": "QC V", "Calculated": result.qc_v, "Reference": ref_qc_v, "Delta": round(result.qc_v - ref_qc_v, 3)},
        ]
        st.dataframe(pd.DataFrame(recon), use_container_width=True, hide_index=True)

# ── Warning acknowledgement (NFR-B) ──────────────────────────────────────────
ack_key = f"ack_{scenario.scenario_id}"
if warnings_only:
    st.divider()
    st.subheader("Acknowledge Warnings")
    st.caption("You must acknowledge warnings before locking an approved snapshot.")
    acked = st.checkbox("I have reviewed all warnings and acknowledge they are acceptable.", key=ack_key)
else:
    acked = True

# ── Lock snapshot ─────────────────────────────────────────────────────────────
st.divider()
st.subheader("🔒 Lock Snapshot")
if errors:
    st.error("Cannot lock: ERRORs are blocking. Fix inputs first.")
elif warnings_only and not acked:
    st.warning("Acknowledge warnings above before locking.")
else:
    if st.button("Lock Snapshot", type="primary", use_container_width=False):
        snap = create_snapshot(sprint, scenario, result)
        add_snapshot(snap)
        st.success(f"Snapshot **{snap.snapshot_id}** locked — download below.")

# ── Download section ──────────────────────────────────────────────────────────
st.divider()
st.subheader("⬇ Download")

dl_col1, dl_col2, dl_col3 = st.columns(3)

with dl_col1:
    xlsx_bytes = result_to_xlsx(sprint, scenario, result)
    if xlsx_bytes:
        st.download_button(
            "📊 Download XLSX (current result)",
            data=xlsx_bytes,
            file_name=f"saturn_velocity_{sprint.name}_{scenario.name}.xlsx",
            mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            use_container_width=True,
        )
    else:
        st.info("openpyxl not available; XLSX export disabled.")

with dl_col2:
    csv_bytes = result_to_csv(result)
    st.download_button(
        "📄 Download CSV (resources)",
        data=csv_bytes,
        file_name=f"saturn_velocity_{sprint.name}_{scenario.name}_resources.csv",
        mime="text/csv",
        use_container_width=True,
    )

with dl_col3:
    # Download latest snapshot as JSON
    snapshots = get_snapshots()
    scenario_snaps = [s for s in snapshots if s.scenario_id == scenario.scenario_id]
    if scenario_snaps:
        latest_snap = scenario_snaps[-1]
        json_str = snapshot_to_json(latest_snap)
        st.download_button(
            "🔐 Download Snapshot JSON",
            data=json_str.encode("utf-8"),
            file_name=f"snapshot_{latest_snap.snapshot_id}.json",
            mime="application/json",
            use_container_width=True,
        )
    else:
        st.info("No snapshot locked yet for this scenario.")

# ── Snapshot history ──────────────────────────────────────────────────────────
st.divider()
st.subheader("📜 Snapshot History")
all_snaps = get_snapshots()
if all_snaps:
    snap_rows = []
    for s in reversed(all_snaps):
        snap_rows.append({
            "Snapshot ID": s.snapshot_id,
            "Scenario": s.scenario_name,
            "Sprint": s.sprint_id,
            "Rule Version": s.rule_version,
            "As-of Date": str(s.as_of_date),
            "Approved": "✅" if s.approved else "⚠️",
            "Warnings": len(s.warnings),
        })
    st.dataframe(pd.DataFrame(snap_rows), use_container_width=True, hide_index=True)

    with st.expander("Download any snapshot as JSON"):
        snap_names = [f"{s.snapshot_id} · {s.scenario_name} · {s.as_of_date}" for s in reversed(all_snaps)]
        sel_idx = st.selectbox("Select snapshot", range(len(snap_names)), format_func=lambda i: snap_names[i])
        sel_snap = list(reversed(all_snaps))[sel_idx]
        st.download_button(
            "⬇ Download JSON",
            data=snapshot_to_json(sel_snap).encode("utf-8"),
            file_name=f"snapshot_{sel_snap.snapshot_id}.json",
            mime="application/json",
        )
else:
    st.info("No snapshots in this session. Lock a snapshot from Results or here.")

# ── Privacy notice ────────────────────────────────────────────────────────────
st.divider()
st.caption(
    "🔒 **Privacy notice (NFR-C / R-08):** This session runs in your browser. "
    "Uploaded workbook data is not stored on any server. "
    "Before deploying to Streamlit Community Cloud, ensure the app is set to **private** "
    "if workbook data contains personal leave or resource information."
)
