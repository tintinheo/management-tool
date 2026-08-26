"""Portable, credential-free Jira velocity snapshot bundles.

Streamlit Community Cloud local runtime state is not a durable history store. This module
therefore supports an explicit download/upload path for Sprint boundary evidence. The bundle
contains no Jira token, OAuth secret, site URL, issue summary, or person identifier.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import List, Sequence, Tuple

from ..domain.jira_models import JiraBoardConfig, JiraSprintSnapshot


SCHEMA_VERSION = "saturn-jira-snapshots-v1"


def export_snapshot_bundle(
    board: JiraBoardConfig,
    snapshots: Sequence[JiraSprintSnapshot],
) -> bytes:
    payload = {
        "schema_version": SCHEMA_VERSION,
        "board_id": board.board_id,
        "snapshots": [
            {
                "sprint_id": snapshot.sprint_id,
                "sprint_name": snapshot.sprint_name,
                "snapshot_kind": snapshot.snapshot_kind,
                "captured_at": snapshot.captured_at.isoformat(),
                "estimation_field_id": snapshot.estimation_field_id,
                "estimation_display_name": snapshot.estimation_display_name,
                "issue_estimates": snapshot.issue_estimates,
                "issue_done": snapshot.issue_done,
                "issue_is_subtask": snapshot.issue_is_subtask,
                "source": snapshot.source,
                "warnings": snapshot.warnings,
            }
            for snapshot in snapshots
        ],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8")


def import_snapshot_bundle(raw: bytes) -> Tuple[int, List[JiraSprintSnapshot]]:
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("Snapshot bundle must be valid UTF-8 JSON.") from exc
    if not isinstance(payload, dict) or payload.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"Snapshot bundle schema must be {SCHEMA_VERSION}.")
    try:
        board_id = int(payload["board_id"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("Snapshot bundle board_id is missing or invalid.") from exc
    rows = payload.get("snapshots")
    if not isinstance(rows, list):
        raise ValueError("Snapshot bundle snapshots must be a list.")

    snapshots: List[JiraSprintSnapshot] = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise ValueError(f"Snapshot row {index} must be an object.")
        kind = str(row.get("snapshot_kind") or "")
        if kind not in {"start", "close"}:
            raise ValueError(f"Snapshot row {index} has an invalid snapshot_kind.")
        captured_at = _parse_timestamp(row.get("captured_at"), index)
        estimates = row.get("issue_estimates")
        done = row.get("issue_done")
        subtasks = row.get("issue_is_subtask")
        if not isinstance(estimates, dict) or not isinstance(done, dict) or not isinstance(subtasks, dict):
            raise ValueError(f"Snapshot row {index} issue maps are invalid.")
        warnings = row.get("warnings") or []
        if not isinstance(warnings, list):
            raise ValueError(f"Snapshot row {index} warnings must be a list.")
        snapshots.append(JiraSprintSnapshot(
            sprint_id=_required_int(row.get("sprint_id"), index, "sprint_id"),
            sprint_name=str(row.get("sprint_name") or ""),
            snapshot_kind=kind,
            captured_at=captured_at,
            estimation_field_id=(
                None if row.get("estimation_field_id") is None
                else str(row.get("estimation_field_id"))
            ),
            estimation_display_name=str(row.get("estimation_display_name") or ""),
            issue_estimates={str(key): _optional_float(value, index) for key, value in estimates.items()},
            issue_done=_boolean_map(done, index, "issue_done"),
            issue_is_subtask=_boolean_map(subtasks, index, "issue_is_subtask"),
            source=str(row.get("source") or "imported_bundle"),
            warnings=[str(value) for value in warnings],
        ))
    return board_id, snapshots


def _parse_timestamp(value, index: int) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"Snapshot row {index} captured_at is invalid.") from exc
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _required_int(value, index: int, field: str) -> int:
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Snapshot row {index} {field} is invalid.") from exc


def _optional_float(value, index: int):
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError(f"Snapshot row {index} contains a non-numeric estimate.")
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Snapshot row {index} contains a non-numeric estimate.") from exc


def _boolean_map(values, index: int, field: str):
    if any(not isinstance(value, bool) for value in values.values()):
        raise ValueError(f"Snapshot row {index} {field} must contain Boolean values.")
    return {str(key): value for key, value in values.items()}
