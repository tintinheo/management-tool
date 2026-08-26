"""Shared Streamlit helpers for the PMI pages (kept under src/ so it is not a page)."""
import streamlit as st

from ..storage import pmi_store
from ..domain.pmi_models import Project


def require_project(page_title: str) -> Project:
    """Guard a PMI page: ensure a project exists, else offer to seed a demo and stop."""
    project = pmi_store.get_project()
    if project is None:
        st.warning(
            "No project yet. Create one on **Project & Outcomes**, or seed a synthetic "
            "demo project to explore the project-control features."
        )
        if st.button("🌱 Seed synthetic demo project", key=f"seed_{page_title}"):
            pmi_store.seed_sample_project()
            st.success("Seeded a synthetic demo project.")
            st.rerun()
        st.stop()
    return project


def status_badge(status: str) -> str:
    return {
        "ok": "✅ ok",
        "insufficient_data": "🚫 insufficient data",
    }.get(status, status)
