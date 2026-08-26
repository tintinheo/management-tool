"""
Page 7 — Project & Outcomes (PM-01, IMP-01, IMP-02, NF-01).
Charter, Product Goal, Definition of Done, Definition of Workflow and outcomes.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import streamlit as st
import pandas as pd

from src.storage import pmi_store
from src.domain.pmi_models import (
    Project, ProjectOutcome, GovernanceStatus, DefinitionOfDone,
    DefinitionOfWorkflow, WorkflowState, default_workflow_states,
)

st.set_page_config(page_title="Project & Outcomes · Saturn Velocity", layout="wide")
st.title("�ln Project & Outcomes".replace("ln", "🧭"))

st.caption(
    "Project governance layer (PMBOK/PMP). Labels shown here still require business "
    "sign-off before production use (proposal §4)."
)

project = pmi_store.get_project()
gov_opts = [g.value for g in GovernanceStatus]

with st.form("project_form"):
    st.subheader("Charter")
    c1, c2 = st.columns(2)
    with c1:
        name = st.text_input("Project name", value=project.name if project else "")
        owner = st.text_input("Owner", value=project.owner if project else "")
        approach = st.selectbox(
            "Delivery approach", ["Predictive", "Adaptive", "Hybrid"],
            index=["Predictive", "Adaptive", "Hybrid"].index(project.approach) if project else 2,
            help="PMP ECO covers predictive, agile/adaptive and hybrid approaches.",
        )
    with c2:
        gov = st.selectbox(
            "Governance status", gov_opts,
            index=gov_opts.index(project.governance_status.value) if project else 0,
        )
        product_goal = st.text_input(
            "Product Goal (Scrum, IMP-01)", value=project.product_goal if project else "")
    objective = st.text_area("Objective", value=project.objective if project else "")

    st.subheader("Definition of Done (IMP-02)")
    dod_text = st.text_area(
        "One criterion per line",
        value="\n".join(project.dod.criteria) if project else "",
        help="'Done' is only valid when DoD evidence exists.",
    )

    saved = st.form_submit_button("💾 Save project", use_container_width=True)

if saved:
    if not name.strip():
        st.error("Project name is required.")
    else:
        p = project or Project()
        p.name = name.strip()
        p.owner = owner.strip()
        p.approach = approach
        p.governance_status = GovernanceStatus(gov)
        p.product_goal = product_goal.strip()
        p.objective = objective.strip()
        p.dod = DefinitionOfDone(
            version=p.dod.version if project else "dod-v1",
            criteria=[ln.strip() for ln in dod_text.splitlines() if ln.strip()],
        )
        pmi_store.set_project(p)
        st.success("Project saved.")
        st.rerun()

if project is None:
    st.info("Create the project above, or seed a synthetic demo from any project page.")
    if st.button("🌱 Seed synthetic demo project"):
        pmi_store.seed_sample_project()
        st.rerun()
    st.stop()

# ── Definition of Workflow (NF-01) ───────────────────────────────────────────
st.divider()
st.subheader("Definition of Workflow (NF-01)")
st.caption("Started/finished points drive flow metrics. WIP limits and SLE are optional.")

wf = project.workflow
wf_df = pd.DataFrame([
    {"order": s.order, "name": s.name, "is_started": s.is_started,
     "is_finished": s.is_finished, "wip_limit": s.wip_limit}
    for s in sorted(wf.states, key=lambda x: x.order)
])
edited_wf = st.data_editor(
    wf_df, num_rows="dynamic", use_container_width=True, key="wf_editor",
    column_config={
        "order": st.column_config.NumberColumn("Order", min_value=0, step=1),
        "name": st.column_config.TextColumn("State", required=True),
        "is_started": st.column_config.CheckboxColumn("Started point"),
        "is_finished": st.column_config.CheckboxColumn("Finished point"),
        "wip_limit": st.column_config.NumberColumn("WIP limit", min_value=0, step=1),
    },
)
cwf1, cwf2 = st.columns(2)
with cwf1:
    sle_days = st.number_input("SLE elapsed days (NF-06)", min_value=0,
                               value=wf.sle_days or 0, step=1,
                               help="Leave 0 to keep SLE unset — it will not be published.")
with cwf2:
    sle_prob = st.number_input("SLE probability", min_value=0.0, max_value=1.0,
                               value=wf.sle_probability or 0.0, step=0.05, format="%.2f")

if st.button("💾 Save workflow"):
    new_states = []
    for _, row in edited_wf.iterrows():
        nm = str(row.get("name", "")).strip()
        if not nm:
            continue
        wl = row.get("wip_limit")
        new_states.append(WorkflowState(
            name=nm, order=int(row.get("order", 0) or 0),
            is_started=bool(row.get("is_started", False)),
            is_finished=bool(row.get("is_finished", False)),
            wip_limit=int(wl) if wl is not None and not pd.isna(wl) else None,
        ))
    if not new_states:
        new_states = default_workflow_states()
    project.workflow = DefinitionOfWorkflow(
        version=wf.version, states=new_states, policies=wf.policies,
        sle_days=int(sle_days) or None,
        sle_probability=float(sle_prob) or None,
    )
    pmi_store.set_project(project)
    st.success("Workflow saved.")
    st.rerun()

# ── Outcomes register ────────────────────────────────────────────────────────
st.divider()
st.subheader("Outcomes / value register (PM-01)")
outcomes = pmi_store.get_outcomes()
out_df = pd.DataFrame([
    {"definition": o.definition, "measure": o.measure, "owner": o.owner,
     "review_status": o.review_status}
    for o in outcomes
]) if outcomes else pd.DataFrame(columns=["definition", "measure", "owner", "review_status"])

edited_out = st.data_editor(
    out_df, num_rows="dynamic", use_container_width=True, key="out_editor",
    column_config={
        "definition": st.column_config.TextColumn("Outcome", required=True),
        "measure": st.column_config.TextColumn("Measure"),
        "owner": st.column_config.TextColumn("Owner"),
        "review_status": st.column_config.SelectboxColumn(
            "Review status", options=["Proposed", "Approved", "Rejected"]),
    },
)
if st.button("💾 Save outcomes"):
    new_out = []
    for _, row in edited_out.iterrows():
        d = str(row.get("definition", "")).strip()
        if not d:
            continue
        new_out.append(ProjectOutcome(
            project_id=project.project_id, definition=d,
            measure=str(row.get("measure", "")), owner=str(row.get("owner", "")),
            review_status=str(row.get("review_status", "Proposed")),
        ))
    pmi_store.set_outcomes(new_out)
    st.success(f"Saved {len(new_out)} outcome(s).")
