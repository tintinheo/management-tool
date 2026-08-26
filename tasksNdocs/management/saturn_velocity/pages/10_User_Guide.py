"""In-application user guide backed by the repository Markdown document."""
from pathlib import Path

import streamlit as st


st.set_page_config(page_title="User Guide · Saturn Velocity", page_icon="📘", layout="wide")

guide_path = Path(__file__).parent.parent / "docs" / "User_Guide.md"
if not guide_path.exists():
    st.error("User guide file was not found in docs/User_Guide.md.")
    st.stop()

st.markdown(guide_path.read_text(encoding="utf-8"))
