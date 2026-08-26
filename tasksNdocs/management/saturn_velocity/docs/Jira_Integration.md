# Jira Cloud Integration — Implementation and Operations Proposal

## 1. Implemented outcome

Ứng dụng có hai trang mới:

- `pages/8_Jira_Integration.py`: xác thực Jira Cloud, đọc cấu hình board/Sprint, đồng bộ issue, mapping Jira assignee sang Saturn resource, capture và backup/restore velocity snapshot. [Source: `pages/8_Jira_Integration.py`]
- `pages/9_Jira_Workload_Velocity.py`: workload theo time tracking, đối chiếu capacity đã approve, team velocity, scope change và data-quality gates. [Source: `pages/9_Jira_Workload_Velocity.py`]

Adapter chỉ gọi API đọc dữ liệu; source không có lệnh create, update hoặc delete Jira issue. [Source: `src/integrations/jira_cloud.py`]

## 2. Authentication

### 2.1 OAuth 2.0 (3LO)

Tạo OAuth 2.0 integration trong Atlassian Developer Console, đăng ký callback URL chính xác tới trang Jira Integration của app, rồi cấu hình Streamlit secrets: [Source: `pages/8_Jira_Integration.py`; [Atlassian OAuth 2.0 (3LO)](https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/); [Streamlit secrets](https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/secrets-management)]

```toml
[jira_oauth]
client_id = "<atlassian-client-id>"
client_secret = "<atlassian-client-secret>"
redirect_uri = "<exact-jira-integration-page-url>"
scopes = "read:jira-work read:board-scope:jira-software read:sprint:jira-software"
```

OAuth `state` được tạo và kiểm tra trước khi đổi authorization code lấy token. OAuth token chỉ nằm trong `st.session_state`; token/secret không nằm trong domain model hoặc snapshot bundle. [Source: `pages/8_Jira_Integration.py`; `src/domain/jira_models.py`; `src/application/jira_snapshot_io.py`]

### 2.2 API token proof of concept

API-token mode nhận Jira site URL, email và token tại runtime. Token nằm trong password widget và không được ghi vào typed connection state. [Source: `pages/8_Jira_Integration.py`; `src/domain/jira_models.py`]

API-token site URL bị giới hạn ở HTTPS root thuộc `*.atlassian.net`; URL có credentials, custom port, path, query hoặc fragment bị từ chối. [Source: `src/integrations/jira_cloud.py`, function `normalize_site_url`]

**[ASSUME]** Missing data: organization authentication policy. Reasoning: OAuth 2.0 (3LO) được đặt làm mode mặc định vì code hỗ trợ consent flow và không cần lưu API token dùng chung; organization owner vẫn phải phê duyệt mode áp dụng cho production. [Source: `src/domain/jira_models.py`; `pages/8_Jira_Integration.py`]

## 3. Jira data contract

| Data | API/logic | Use |
| --- | --- | --- |
| Board configuration | `GET /rest/agile/1.0/board/{boardId}/configuration` | Discover estimation field và statuses thuộc cột Done; không hard-code custom field/status. [Source: `src/integrations/jira_cloud.py`; `src/application/jira_services.py`; [Jira board API](https://developer.atlassian.com/cloud/jira/software/rest/api-group-board/)] |
| Sprints | `GET /rest/agile/1.0/board/{boardId}/sprint` với pagination | Chọn Sprint cần sync. [Source: `src/integrations/jira_cloud.py`; [Jira Sprint API](https://developer.atlassian.com/cloud/jira/software/rest/api-group-sprint/)] |
| Issues | `POST /rest/api/3/search/jql`, JQL `sprint = <id>`, token pagination | Issue, assignee account ID, time tracking, board estimate và current Done state. [Source: `src/integrations/jira_cloud.py`; `src/application/jira_services.py`; [Jira enhanced JQL search](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/)] |
| Snapshot bundle | UTF-8 JSON `saturn-jira-snapshots-v1` | Stateless backup/restore của Sprint boundary evidence; không chứa token, URL, summary hoặc person identifier. [Source: `src/application/jira_snapshot_io.py`] |

## 4. Metric rules

### 4.1 Workload

- Demand cá nhân dùng `remainingEstimateSeconds` hoặc `originalEstimateSeconds`, rồi chia cho `3600` để ra giờ. [Source: `src/application/jira_services.py`, function `calculate_workload`]
- Issue Done bị loại khỏi open workload. Issue chưa assign và issue thiếu time estimate được liệt kê riêng. Missing estimate là unknown demand, không phải zero. [Source: `src/application/jira_services.py`, function `calculate_workload`]
- Mapping dùng immutable Jira `accountId`, không dùng display name làm key. [Source: `pages/8_Jira_Integration.py`; `src/domain/jira_models.py`]
- Capacity giờ chỉ được tính khi Saturn `RuleSet.effective_status == "approved"`; nếu chưa approve, utilization không được publish. [Source: `src/application/jira_services.py`, function `build_saturn_capacity_hours`]
- Story point/board estimate không được quy đổi sang giờ cá nhân. [Source: `src/application/jira_services.py`; `pages/9_Jira_Workload_Velocity.py`; [Atlassian story points](https://support.atlassian.com/jira-software-cloud/docs/what-are-story-points/)]

### 4.2 Velocity

- Commitment lấy từ non-subtask issues trong Sprint-start snapshot. Completed lấy từ non-subtask issues nằm trong board Done mapping tại Sprint-close snapshot. [Source: `src/application/jira_services.py`, functions `capture_sprint_snapshot` and `calculate_velocity`]
- Estimate unit được discover từ board configuration; `issueCount` được biểu diễn là một unit cho mỗi issue, còn field estimation dùng numeric field của board. [Source: `src/application/jira_services.py`, functions `parse_board_configuration` and `parse_issue`]
- Scope added/removed là chênh lệch issue keys giữa start và close snapshot. [Source: `src/application/jira_services.py`, function `calculate_velocity`]
- Average velocity không xuất hiện cho đến khi người dùng chọn một historical window lớn hơn zero. [Source: `src/application/jira_services.py`, function `average_completed_velocity`; `pages/9_Jira_Workload_Velocity.py`]
- Live capture không tự nhận là exact boundary; snapshot được gắn `reconstructed_live_sync` và có warning. [Source: `src/application/jira_services.py`, function `capture_sprint_snapshot`]

Jira mô tả Velocity Chart theo commitment tại thời điểm Sprint bắt đầu và completed tại khi Sprint kết thúc. [Source: [Atlassian Velocity Chart](https://support.atlassian.com/jira-software-cloud/docs/view-and-understand-the-velocity-chart/)]

## 5. Operating workflow

1. Mở **Jira Integration**, chọn OAuth hoặc API-token POC và nhập Board ID. [Source: `pages/8_Jira_Integration.py`]
2. Load board; kiểm tra velocity unit và Done statuses trước khi tiếp tục. [Source: `pages/8_Jira_Integration.py`]
3. Chọn và sync Sprint. [Source: `pages/8_Jira_Integration.py`]
4. Map Jira assignee sang Saturn resource. [Source: `pages/8_Jira_Integration.py`]
5. Capture start snapshot tại Sprint start và close snapshot tại Sprint close. Nếu capture muộn, dashboard giữ warning `reconstructed`. [Source: `src/application/jira_services.py`; `pages/8_Jira_Integration.py`]
6. Download snapshot bundle sau capture; restore bundle khi mở session mới. [Source: `src/application/jira_snapshot_io.py`; `pages/8_Jira_Integration.py`]
7. Mở **Jira Workload & Velocity**; chỉ bật Saturn reconciliation sau khi xác nhận active Saturn scenario đại diện cho Jira Sprint đang chọn. [Source: `pages/9_Jira_Workload_Velocity.py`]

## 6. Data-quality gates and risks

| Risk/anomaly | Confidence | Implemented control |
| --- | --- | --- |
| Story points bị dùng như personal hours | Confidence score: 100%. Code có hai field path tách biệt cho board estimate và time tracking. [Source: `src/domain/jira_models.py`; `src/application/jira_services.py`] | Workload chỉ đọc time seconds; UI nêu rõ không quy đổi story point. |
| Missing time estimate bị hiểu là zero | Confidence score: 100%. `None` được thêm vào `unknown_estimate_issue_keys`. [Source: `src/application/jira_services.py`] | Unknown-demand list và warning. |
| Assignee mapping sai do trùng/đổi display name | Confidence score: 100%. Mapping key là Jira `accountId`. [Source: `src/domain/jira_models.py`; `pages/8_Jira_Integration.py`] | Display name chỉ dùng để hiển thị. |
| Velocity sai do hard-code Done/status/estimate field | Confidence score: 100%. Board config được đọc trước khi parse issue. [Source: `src/application/jira_services.py`] | Discover board estimation field và last-column Done statuses. |
| Live sync bị trình bày như exact boundary snapshot | Confidence score: 100%. Live capture luôn có verification warning. [Source: `src/application/jira_services.py`] | Evidence label là `Reconstructed`. |
| Session restart làm mất velocity history | Confidence score: 100%. State store dùng Streamlit session. [Source: `src/storage/jira_store.py`] | Credential-free snapshot download/upload. [Source: `src/application/jira_snapshot_io.py`] |
| Bundle của board khác bị import nhầm | Confidence score: 100%. UI so khớp `board_id`. [Source: `pages/8_Jira_Integration.py`] | Block import khi Board ID không khớp. |

## 7. Verification

Test runner không phụ thuộc pytest và chạy toàn bộ function `test_*` trong `tests/golden`. [Source: `tests/run_all.py`]

```powershell
python -B tests\run_all.py
```

Jira tests cover REST pagination, OAuth URL/state, dynamic board mapping, timezone normalization, workload unknowns, capacity approval, velocity/scope/subtask rules và snapshot bundle validation. [Source: `tests/golden/test_jira_client.py`; `tests/golden/test_jira_services.py`]

## 8. Missing production decisions

CRITICAL DATA MISSING: Jira Cloud tenant, Board ID and live credentials - Not found in context.

CRITICAL DATA MISSING: Atlassian OAuth app client ID, client secret and registered callback URL - Not found in context.

CRITICAL DATA MISSING: Approved OAuth scopes and organization authentication policy - Not found in context.

CRITICAL DATA MISSING: Jira board estimation configuration and Done-column mapping for the target board - Not found in context.

CRITICAL DATA MISSING: Mapping between Jira account IDs and Saturn resource IDs - Not found in context.

CRITICAL DATA MISSING: Approved historical velocity averaging window - Not found in context.

CRITICAL DATA MISSING: Persistent multi-user data store, retention and access-control policy - Not found in context.

**[ASSUME]** Missing data: shared multi-user persistence. Reasoning: current implementation remains a stateless Streamlit pilot using session state plus explicit snapshot backup/restore; production shared history requires a separately approved repository and access model. [Source: `src/storage/jira_store.py`; `src/application/jira_snapshot_io.py`]
