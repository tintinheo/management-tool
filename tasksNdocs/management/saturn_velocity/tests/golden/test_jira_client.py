"""Golden tests for the read-only Jira Cloud REST adapter."""
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from src.integrations.jira_cloud import (
    JiraCloudClient,
    build_oauth_authorization_url,
    get_accessible_resources,
    normalize_site_url,
)


def test_board_and_sprint_pagination_contract():
    calls = []

    def transport(method, url, headers, payload):
        calls.append((method, url, headers, payload))
        if url.endswith("/configuration"):
            return {"id": 42, "name": "Delivery"}
        start_at = int(parse_qs(urlparse(url).query).get("startAt", ["0"])[0])
        if start_at == 0:
            return {
                "startAt": 0, "maxResults": 1, "isLast": False,
                "values": [{"id": 101, "name": "S1"}],
            }
        return {
            "startAt": 1, "maxResults": 1, "isLast": True,
            "values": [{"id": 102, "name": "S2"}],
        }

    client = JiraCloudClient.with_api_token(
        "https://example.atlassian.net/", "person@example.com", "token",
        transport=transport,
    )
    board = client.get_board_configuration(42)
    sprints = client.get_board_sprints(42)

    assert board["name"] == "Delivery"
    assert [item["id"] for item in sprints] == [101, 102]
    assert calls[0][0] == "GET"
    assert calls[0][1] == "https://example.atlassian.net/rest/agile/1.0/board/42/configuration"
    assert all(call[2]["Authorization"].startswith("Basic ") for call in calls)


def test_enhanced_jql_token_pagination_and_field_deduplication():
    payloads = []

    def transport(method, url, headers, payload):
        payloads.append(payload)
        assert method == "POST"
        assert url.endswith("/rest/api/3/search/jql")
        if len(payloads) == 1:
            return {"issues": [{"key": "DEMO-1"}], "nextPageToken": "next-token"}
        return {"issues": [{"key": "DEMO-2"}]}

    client = JiraCloudClient.with_oauth("cloud-1", "access", transport=transport)
    issues = client.search_issues("sprint = 101", ["summary", "summary", "status"])

    assert [issue["key"] for issue in issues] == ["DEMO-1", "DEMO-2"]
    assert payloads[0]["fields"] == ["summary", "status"]
    assert "nextPageToken" not in payloads[0]
    assert payloads[1]["nextPageToken"] == "next-token"


def test_oauth_authorization_url_contains_state_and_scopes():
    url = build_oauth_authorization_url(
        "client-1", "https://app.example/callback",
        ["read:jira-work", "read:jira-work", "offline_access"], "state-1",
    )
    query = parse_qs(urlparse(url).query)
    assert url.startswith("https://auth.atlassian.com/authorize?")
    assert query["state"] == ["state-1"]
    assert query["redirect_uri"] == ["https://app.example/callback"]
    assert query["scope"] == ["read:jira-work offline_access"]


def test_accessible_resources_accepts_top_level_array():
    def transport(method, url, headers, payload):
        return [{"id": "cloud-1", "url": "https://example.atlassian.net"}]

    resources = get_accessible_resources("access", transport=transport)
    assert resources[0]["id"] == "cloud-1"


def test_site_url_requires_https():
    try:
        normalize_site_url("http://example.atlassian.net")
    except ValueError as exc:
        assert "HTTPS" in str(exc)
    else:
        raise AssertionError("Expected a ValueError for a non-HTTPS Jira URL")


def test_site_url_rejects_non_atlassian_host_and_non_root_path():
    for value in ("https://example.com", "https://example.atlassian.net/rest/api/3"):
        try:
            normalize_site_url(value)
        except ValueError:
            pass
        else:
            raise AssertionError(f"Expected Jira site URL to be rejected: {value}")
