# Saturn Velocity — User Guide

This guide describes the workflow implemented by the Streamlit application. It does not replace approval of business rules, Jira access, data classification, or retention policy. [Source: `streamlit_app.py`; `pages/1_Sprint_Setup.py`; `docs/Jira_Integration.md`]

## 1. Quick start

### Run locally on Windows

From PowerShell:

```powershell
Set-Location "D:\portfolio\management-tools\tasksNdocs\management\saturn_velocity"
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m streamlit run streamlit_app.py
```

The repository also contains `run.bat`, which changes to the application directory and runs `python -m streamlit run streamlit_app.py`. Dependencies are declared in `requirements.txt`. [Source: `run.bat`; `requirements.txt`]

### First-time workflow

1. Open the application entry page and either upload a Saturn XLSX workbook or create a Sprint manually. [Source: `streamlit_app.py`]
2. Review Sprint dates and versioned rules in **Sprint Setup**. Do not approve a RuleSet until its business meaning has been confirmed. [Source: `pages/1_Sprint_Setup.py`; `src/domain/models.py`]
3. Maintain resources and leave in **Resources & Leave**. [Source: `pages/2_Resources_Leave.py`]
4. Use **Scenarios** for alternative capacity assumptions. [Source: `pages/4_Scenarios.py`]
5. Review calculated capacity in **Results**, then use **Validation & Export** before publishing a snapshot. [Source: `pages/5_Results.py`; `pages/6_Validation_Export.py`]
6. If Jira is required, configure **Jira Integration**, synchronize a Sprint, map assignees, and capture velocity snapshots. [Source: `pages/8_Jira_Integration.py`]
7. Use **Jira Workload & Velocity** to review time-based demand, optional Saturn capacity reconciliation, and team velocity. [Source: `pages/9_Jira_Workload_Velocity.py`]

## 2. Navigation reference

| Page | Purpose | Required input or gate |
| --- | --- | --- |
| Home | Import an XLSX workbook or create a blank Sprint and baseline scenario. [Source: `streamlit_app.py`] | None. |
| Sprint Setup | Maintain Sprint dates, public holidays, buffer, backup, fixed-day deduction, hours per day, rule version, and rule status. [Source: `pages/1_Sprint_Setup.py`] | A Sprint must exist. |
| Resources & Leave | Edit scenario resources and dated or TBD leave events. [Source: `pages/2_Resources_Leave.py`] | Sprint and scenario data must exist. |
| Tickets | Maintain the optional ticket grid and download ticket data. Ticket points/status do not drive the capacity engine without approved binding rules. [Source: `pages/3_Tickets.py`] | Sprint and scenario data must exist. |
| Scenarios | Create, clone, activate, delete, and compare planning scenarios. [Source: `pages/4_Scenarios.py`] | Sprint and scenario data must exist. |
| Results | Calculate Sprint KPIs, team capacity, and resource-level results for an explicit as-of date. [Source: `pages/5_Results.py`; `src/calculation/engine.py`] | Valid Sprint and active scenario. |
| Validation & Export | Run validations, acknowledge warnings, lock snapshots, and download export files. [Source: `pages/6_Validation_Export.py`] | A valid calculation result. |
| Project & Outcomes | Maintain project charter fields, Product Goal, Definition of Done, Definition of Workflow, WIP limits, optional SLE, and outcome register. [Source: `pages/7_Project_Outcomes.py`] | Project record; a synthetic sample can be seeded from the page. |
| Jira Integration | Connect to Jira Cloud, load board configuration, synchronize issues, map resources, and maintain Sprint boundary snapshots. [Source: `pages/8_Jira_Integration.py`] | Jira credentials and Board ID. |
| Jira Workload & Velocity | Analyze time-based workload and snapshot-based team velocity. [Source: `pages/9_Jira_Workload_Velocity.py`] | Loaded Jira board and at least one synchronized Sprint. |
| User Guide | Display this guide inside the application. [Source: `pages/10_User_Guide.py`; `docs/User_Guide.md`] | None. |

## 3. Import or create a Sprint

### Import an XLSX workbook

1. On **Home**, choose an `.xlsx` file.
2. Select **Import Workbook**.
3. Review the import report for every detected sheet, schema, resource count, and warning.
4. The last eligible sheet returned by the importer is selected as the active Sprint/scenario by the entry page. [Source: `streamlit_app.py`; `src/importers/excel_importer.py`]

Do not treat an import warning as an approved business exception. Reconcile the warning to the workbook and approved rules before publishing results. [Source: `pages/6_Validation_Export.py`]

### Create a Sprint manually

Provide Sprint name, start date, development end, end date, public holidays, buffer, backup days, fixed-day deduction, and baseline scenario name. The page blocks an end date or development end earlier than the start date. [Source: `streamlit_app.py`]

CRITICAL DATA MISSING: Authoritative business meaning of the fixed-day deduction inherited from the workbook - Not found in context. [Source: `src/domain/models.py`; `src/calculation/engine.py`]

## 4. Sprint rules and approval

**Sprint Setup** separates Sprint parameters from versioned RuleSet parameters. Available RuleSet statuses are `draft`, `approved`, and `deprecated`. [Source: `pages/1_Sprint_Setup.py`]

The Jira capacity reconciliation publishes capacity hours only when the RuleSet status is `approved` and `hours_per_day` is greater than zero. [Source: `src/application/jira_services.py`, function `build_saturn_capacity_hours`]

**[ASSUME]** Missing data: business approver and approval workflow. Reasoning: the UI records a status but the source does not contain an organization identity, authorization matrix, or external approval system. Users must apply the organization's approved governance procedure before selecting `approved`. [Source: `pages/1_Sprint_Setup.py`; `src/domain/models.py`]

## 5. Resources, leave, and scenarios

### Resources

Resource inputs include role/type, velocity, leave, OT, availability percentage, and scenario association. Calculation output is produced per resource and aggregated for Dev/QC capacity. [Source: `src/domain/models.py`; `src/calculation/engine.py`]

### Leave

Leave events store a resource ID, optional date, day amount, status, and note. `TBD` leave can remain undated; remaining Jira capacity warns when undated/TBD leave cannot be deducted by date. [Source: `src/domain/models.py`; `src/application/jira_services.py`]

### Scenarios

Use scenarios for alternative assumptions instead of overwriting the baseline. The Scenarios page supports comparison and reports deltas against the first selected scenario. [Source: `pages/4_Scenarios.py`]

## 6. Tickets, project, and process governance

The **Tickets** grid is optional. The current page explicitly keeps ticket points/status disconnected from the capacity engine until binding rules are approved. [Source: `pages/3_Tickets.py`]

The **Project & Outcomes** page supports:

- project name, owner, objective, delivery approach, and governance status;
- Product Goal and versioned Definition of Done criteria;
- configurable workflow states, started/finished points, WIP limits, and optional SLE;
- outcome definition, measure, owner, and review status. [Source: `pages/7_Project_Outcomes.py`; `src/domain/pmi_models.py`]

CRITICAL DATA MISSING: Organization-approved task lifecycle, Definition of Done, WIP limits, SLE policy, and outcome approval roles - Not found in context.

## 7. Results, validation, and export

The calculation engine uses an explicit `as_of_date` so a stored calculation does not depend implicitly on the date when it is reopened. [Source: `src/calculation/engine.py`]

Use the publication workflow in this order:

1. Review validation errors and warnings.
2. Resolve errors; an error blocks snapshot locking.
3. Explicitly acknowledge remaining warnings.
4. Lock the calculation snapshot.
5. Download the available export artifacts.
6. Retain the downloaded file according to the approved retention policy. [Source: `pages/6_Validation_Export.py`; `src/storage/session_store.py`]

**[ASSUME]** Missing data: persistent multi-user repository. Reasoning: the current application stores operational state in Streamlit session state; downloaded snapshots are the available hand-off mechanism in the implemented stateless mode. [Source: `src/storage/session_store.py`; `src/storage/pmi_store.py`; `pages/6_Validation_Export.py`]

## 8. Jira Cloud setup

### OAuth 2.0 (3LO)

Create `.streamlit/secrets.toml` locally or configure equivalent secrets in Streamlit Community Cloud:

```toml
[jira_oauth]
client_id = "<atlassian-client-id>"
client_secret = "<atlassian-client-secret>"
redirect_uri = "<exact-jira-integration-page-url>"
scopes = "read:jira-work read:board-scope:jira-software read:sprint:jira-software"
```

The redirect URI must match the configured Jira Integration page. OAuth state is checked before exchanging the authorization code. Tokens stay in the Streamlit session and are not included in exported domain data or velocity bundles. [Source: `pages/8_Jira_Integration.py`; `src/application/jira_snapshot_io.py`]

### API-token proof of concept

Enter the Jira Cloud site root, account email, API token, and numeric Board ID on **Jira Integration**. API-token mode accepts only an HTTPS root under `*.atlassian.net`; a URL containing credentials, custom port, path, query, or fragment is rejected. [Source: `pages/8_Jira_Integration.py`; `src/integrations/jira_cloud.py`]

The API token is taken from a password input and is not stored in `JiraConnection`. [Source: `pages/8_Jira_Integration.py`; `src/domain/jira_models.py`]

CRITICAL DATA MISSING: Jira Cloud tenant, Board ID, live credentials, OAuth application configuration, and organization-approved scopes - Not found in context.

## 9. Jira synchronization workflow

1. Select OAuth or API-token POC.
2. Enter or authorize the connection and provide Board ID.
3. Select **Load board configuration**.
4. Review the board name, estimation unit, and Done status count. Stop if the returned configuration does not match the team's board contract.
5. Select a Sprint and choose **Sync selected Sprint**.
6. Map each Jira `accountId` to the matching Saturn resource ID. Display names are shown for readability but are not mapping identifiers.
7. Capture a Sprint-start snapshot at Sprint start and a Sprint-close snapshot at Sprint close.
8. Download the velocity snapshot bundle after capture. Restore it in a later session when required. [Source: `pages/8_Jira_Integration.py`; `src/application/jira_services.py`; `src/application/jira_snapshot_io.py`]

The snapshot bundle is scoped by Board ID and contains estimate/done/subtask maps, timestamps, source labels, and warnings. It does not contain Jira credentials, site URL, issue summary, or person identifiers. [Source: `src/application/jira_snapshot_io.py`]

## 10. Workload interpretation

Individual workload uses Jira `remainingEstimateSeconds` or `originalEstimateSeconds`, converted to hours by dividing seconds by `3600`. Story points and other board estimates are not converted to personal hours. [Source: `src/application/jira_services.py`, function `calculate_workload`]

The dashboard treats data as follows:

- Done issues are excluded from open workload.
- Unassigned issues are listed separately.
- Missing time estimates are unknown demand, not zero demand.
- Jira assignees without Saturn mappings are listed separately.
- Utilization is calculated only when approved Saturn capacity is available.
- Over-allocation is true when known demand hours exceed available capacity hours. [Source: `src/application/jira_services.py`; `src/domain/jira_models.py`; `pages/9_Jira_Workload_Velocity.py`]

Before enabling Saturn reconciliation, confirm that the active Saturn scenario represents the selected Jira Sprint. The application presents this as an explicit checkbox and does not infer the relationship. [Source: `pages/9_Jira_Workload_Velocity.py`]

CRITICAL DATA MISSING: Approved mapping between the selected Jira Sprint and active Saturn Sprint/scenario - Not found in context.

## 11. Velocity interpretation

Velocity uses the estimation configuration discovered from the Jira board:

- commitment is the sum of known estimates for non-subtask issues in the start snapshot;
- completed is the sum of known estimates for non-subtask issues in the close snapshot whose status belongs to the board's Done column;
- scope added and removed are issue-key differences between close and start snapshots;
- a missing estimate remains unknown and creates a warning;
- a live capture is labeled reconstructed unless its timestamp is independently supplied as the exact Sprint boundary;
- average completed velocity remains unpublished until the user chooses a positive historical window. [Source: `src/application/jira_services.py`; `pages/9_Jira_Workload_Velocity.py`]

Do not compare velocity values across boards or time periods without confirming that the estimation field and working agreement are comparable. The application blocks velocity calculation when the estimation field changes between start and close snapshots. [Source: `src/application/jira_services.py`, function `calculate_velocity`]

CRITICAL DATA MISSING: Approved historical velocity averaging window and exact boundary-capture operating procedure - Not found in context.

## 12. Data-quality checklist

Before publishing workload or velocity, confirm:

- correct Jira site and Board ID;
- board estimation unit and Done-column mapping reviewed;
- correct Sprint selected;
- issue sync timestamp reviewed;
- Jira account IDs mapped to the correct Saturn resources;
- missing time estimates and unassigned issues dispositioned;
- Saturn RuleSet approved before capacity reconciliation;
- Saturn and Jira planning boundaries confirmed equivalent;
- start/close snapshots available and reconstruction warnings disclosed;
- historical average window approved;
- snapshot/export downloaded before ending the session. [Source: `pages/8_Jira_Integration.py`; `pages/9_Jira_Workload_Velocity.py`; `pages/6_Validation_Export.py`]

## 13. Troubleshooting

### `python` or Streamlit is not available

Use the virtual-environment commands in **Quick start** and install `requirements.txt`. The provided `run.bat` assumes `python` is available on `PATH`. [Source: `run.bat`; `requirements.txt`]

### No Saturn pages can be opened

Return to **Home** and import a workbook or create a Sprint. Pages that require Saturn data stop when session data is absent. [Source: `streamlit_app.py`; `pages/1_Sprint_Setup.py`; `pages/2_Resources_Leave.py`]

### OAuth configuration is incomplete

Confirm that `client_id`, `client_secret`, and `redirect_uri` exist under `[jira_oauth]` and that the callback URL matches the Atlassian application configuration. [Source: `pages/8_Jira_Integration.py`]

### Jira board cannot be loaded

Confirm the site, authentication input, numeric Board ID, network access, and permissions. The adapter returns sanitized HTTP status/detail or a network message without including credentials or response headers. [Source: `src/integrations/jira_cloud.py`]

### Workload has no utilization

Review whether Saturn reconciliation is enabled, the RuleSet is approved, `hours_per_day` is greater than zero, the Saturn calculation has no errors, and Jira accounts are mapped to Saturn resources. [Source: `src/application/jira_services.py`; `pages/9_Jira_Workload_Velocity.py`]

### Velocity is unavailable

Confirm that both start and close snapshots exist for the same Sprint and use the same board estimation field. [Source: `src/application/jira_services.py`; `pages/9_Jira_Workload_Velocity.py`]

### Data disappeared after a session ended

Restore downloaded calculation/Jira snapshot artifacts. Current session stores are not a shared durable repository. [Source: `src/storage/session_store.py`; `src/storage/pmi_store.py`; `src/storage/jira_store.py`; `src/application/jira_snapshot_io.py`]

## 14. Security and operating limits

- Do not commit `.streamlit/secrets.toml` or paste credentials into source code.
- Use only organization-approved Jira scopes and accounts.
- Downloaded workbook/snapshot artifacts may contain planning or resource information; handle them according to the approved classification and retention policy.
- Disconnecting Jira clears connection data, synchronized issues, mappings, snapshots, sync status, and OAuth session data from the current Streamlit session. [Source: `src/storage/jira_store.py`]

**[ASSUME]** Missing data: data classification, access-control model, retention, and incident-response policy. Reasoning: these organization controls are not represented in the application source and must be approved before production use.

## 15. Verification for maintainers

Run all golden tests without an external test framework:

```powershell
.\.venv\Scripts\python.exe -B tests\run_all.py
```

The runner discovers no-argument functions named `test_*` under `tests/golden` and returns a non-zero exit status when any test fails. [Source: `tests/run_all.py`]
