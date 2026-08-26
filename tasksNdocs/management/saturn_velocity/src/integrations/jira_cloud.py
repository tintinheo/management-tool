"""Small Jira Cloud REST client with injectable transport for deterministic tests.

Supported authentication modes:

* OAuth 2.0 (3LO) bearer access tokens through ``api.atlassian.com``.
* Email + API token basic authentication for a controlled internal proof of concept.

The client is read-only. It uses Jira's enhanced JQL search endpoint and discovers
the board's estimation field and Done-column mapping instead of hard-coding custom
field IDs or workflow statuses.
"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from typing import Any, Callable, Dict, Iterable, List, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen


class JiraApiError(RuntimeError):
    """A sanitized Jira API failure. Credentials and response headers are never included."""

    def __init__(self, message: str, *, status_code: Optional[int] = None):
        super().__init__(message)
        self.status_code = status_code


Transport = Callable[[str, str, Dict[str, str], Optional[Dict[str, Any]]], Any]


def _default_transport(
    method: str,
    url: str,
    headers: Dict[str, str],
    payload: Optional[Dict[str, Any]],
) -> Any:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:  # noqa: S310 - URL is user-configured Jira
            raw = response.read()
    except HTTPError as exc:
        try:
            body = exc.read().decode("utf-8", errors="replace")
            detail = json.loads(body)
            messages = detail.get("errorMessages") or [detail.get("message")]
            safe_detail = "; ".join(str(m) for m in messages if m)
        except Exception:  # noqa: BLE001 - preserve only a sanitized status
            safe_detail = ""
        suffix = f": {safe_detail}" if safe_detail else ""
        raise JiraApiError(
            f"Jira API request failed with HTTP {exc.code}{suffix}",
            status_code=exc.code,
        ) from exc
    except URLError as exc:
        raise JiraApiError("Jira API could not be reached. Check the site URL and network.") from exc

    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise JiraApiError("Jira API returned a non-JSON response.") from exc


def normalize_site_url(site_url: str) -> str:
    value = site_url.strip().rstrip("/")
    if not value:
        raise ValueError("Jira site URL is required.")
    parsed = urlparse(value)
    if parsed.scheme.lower() != "https":
        raise ValueError("Jira Cloud site URL must use HTTPS.")
    hostname = (parsed.hostname or "").lower()
    if not hostname.endswith(".atlassian.net"):
        raise ValueError("API-token mode only accepts Jira Cloud *.atlassian.net sites.")
    if parsed.username or parsed.password or parsed.port or parsed.path not in {"", "/"}:
        raise ValueError("Jira Cloud site URL must be the site root without credentials, port, or path.")
    if parsed.query or parsed.fragment:
        raise ValueError("Jira Cloud site URL must not contain a query or fragment.")
    return f"https://{hostname}"


@dataclass
class JiraCloudClient:
    api_root: str
    authorization: str
    transport: Transport = _default_transport

    @classmethod
    def with_api_token(
        cls,
        site_url: str,
        email: str,
        api_token: str,
        *,
        transport: Transport = _default_transport,
    ) -> "JiraCloudClient":
        if not email.strip() or not api_token:
            raise ValueError("Jira email and API token are required.")
        raw = f"{email.strip()}:{api_token}".encode("utf-8")
        auth = "Basic " + base64.b64encode(raw).decode("ascii")
        return cls(normalize_site_url(site_url), auth, transport)

    @classmethod
    def with_oauth(
        cls,
        cloud_id: str,
        access_token: str,
        *,
        transport: Transport = _default_transport,
    ) -> "JiraCloudClient":
        if not cloud_id.strip() or not access_token:
            raise ValueError("Jira cloud ID and OAuth access token are required.")
        root = f"https://api.atlassian.com/ex/jira/{cloud_id.strip()}"
        return cls(root, f"Bearer {access_token}", transport)

    def _request(
        self,
        method: str,
        path: str,
        payload: Optional[Dict[str, Any]] = None,
        query: Optional[Dict[str, Any]] = None,
    ) -> Any:
        url = self.api_root.rstrip("/") + "/" + path.lstrip("/")
        if query:
            encoded = urlencode({k: v for k, v in query.items() if v is not None})
            if encoded:
                url += "?" + encoded
        headers = {
            "Accept": "application/json",
            "Authorization": self.authorization,
        }
        if payload is not None:
            headers["Content-Type"] = "application/json"
        return self.transport(method, url, headers, payload)

    def get_board_configuration(self, board_id: int) -> Dict[str, Any]:
        return self._request("GET", f"/rest/agile/1.0/board/{board_id}/configuration")

    def get_board_sprints(self, board_id: int, state: Optional[str] = None) -> List[Dict[str, Any]]:
        values: List[Dict[str, Any]] = []
        start_at = 0
        while True:
            page = self._request(
                "GET",
                f"/rest/agile/1.0/board/{board_id}/sprint",
                query={"startAt": start_at, "maxResults": 50, "state": state},
            )
            batch = list(page.get("values") or [])
            values.extend(batch)
            if page.get("isLast", not batch):
                break
            next_start = int(page.get("startAt", start_at)) + int(page.get("maxResults", len(batch)))
            if next_start <= start_at or not batch:
                break
            start_at = next_start
        return values

    def search_issues(self, jql: str, fields: Iterable[str]) -> List[Dict[str, Any]]:
        issues: List[Dict[str, Any]] = []
        next_token: Optional[str] = None
        while True:
            body: Dict[str, Any] = {
                "jql": jql,
                "fields": list(dict.fromkeys(fields)),
                "maxResults": 100,
            }
            if next_token:
                body["nextPageToken"] = next_token
            page = self._request("POST", "/rest/api/3/search/jql", body)
            issues.extend(list(page.get("issues") or []))
            next_token = page.get("nextPageToken")
            if not next_token:
                break
        return issues

    def bulk_fetch_changelogs(
        self,
        issue_ids_or_keys: Iterable[str],
        field_ids: Optional[Iterable[str]] = None,
    ) -> List[Dict[str, Any]]:
        ids = [str(i) for i in issue_ids_or_keys if str(i)]
        if not ids:
            return []
        changelogs: List[Dict[str, Any]] = []
        next_token: Optional[str] = None
        while True:
            body: Dict[str, Any] = {"issueIdsOrKeys": ids, "maxResults": 100}
            if field_ids:
                body["fieldIds"] = list(dict.fromkeys(field_ids))
            if next_token:
                body["nextPageToken"] = next_token
            page = self._request("POST", "/rest/api/3/changelog/bulkfetch", body)
            changelogs.extend(list(page.get("issueChangeLogs") or page.get("values") or []))
            next_token = page.get("nextPageToken")
            if not next_token:
                break
        return changelogs


def build_oauth_authorization_url(
    client_id: str,
    redirect_uri: str,
    scopes: Iterable[str],
    state: str,
) -> str:
    if not client_id or not redirect_uri or not state:
        raise ValueError("OAuth client ID, redirect URI and state are required.")
    query = urlencode({
        "audience": "api.atlassian.com",
        "client_id": client_id,
        "scope": " ".join(dict.fromkeys(scopes)),
        "redirect_uri": redirect_uri,
        "state": state,
        "response_type": "code",
        "prompt": "consent",
    })
    return "https://auth.atlassian.com/authorize?" + query


def exchange_oauth_code(
    client_id: str,
    client_secret: str,
    code: str,
    redirect_uri: str,
    *,
    transport: Transport = _default_transport,
) -> Dict[str, Any]:
    return transport(
        "POST",
        "https://auth.atlassian.com/oauth/token",
        {"Accept": "application/json", "Content-Type": "application/json"},
        {
            "grant_type": "authorization_code",
            "client_id": client_id,
            "client_secret": client_secret,
            "code": code,
            "redirect_uri": redirect_uri,
        },
    )


def get_accessible_resources(
    access_token: str,
    *,
    transport: Transport = _default_transport,
) -> List[Dict[str, Any]]:
    response = transport(
        "GET",
        "https://api.atlassian.com/oauth/token/accessible-resources",
        {"Accept": "application/json", "Authorization": f"Bearer {access_token}"},
        None,
    )
    # The endpoint returns a top-level JSON array. Test transports may wrap it so the
    # typed client can keep a dictionary-only transport contract.
    if isinstance(response, list):
        return response
    return list(response.get("values") or response.get("resources") or [])
