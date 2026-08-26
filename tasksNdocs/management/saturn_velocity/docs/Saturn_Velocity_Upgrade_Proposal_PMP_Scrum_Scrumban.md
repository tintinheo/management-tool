# Saturn Velocity Upgrade Proposal

*Workload, Task, Progress và Forecasting theo mô hình PMP/PMBOK + Scrum/Scrumban*

Nguồn nghiệp vụ chính: `Saturn Velocity 2024-2025-2026 (1).xlsx`.

Proposal này hợp nhất BRD, technical proposal và improvement backlog đã tạo; bổ sung các capability còn thiếu về project/task management, workload, progress và forecasting. [Source: DOC-BRD; DOC-TECH; DOC-IMPROVEMENT]

## 1. Executive proposal

Workbook hiện lưu sprint window, development window, public holiday, buffer, backup, resource velocity, leave, OT và các kết quả capacity Dev/QC/Biz. [Source: WB-s192-META; WB-s192-RESOURCE; WB-s192-SUMMARY]

Workbook có ticket schema gồm Dev, QC, Category, Current Assignee, Ticket ID, Summary, Priority, Point, Status, `Added after Sprint start` và Note; các vùng ticket quan sát được không có populated record. [Source: WB-s192-TICKET; WB-ALL-TICKET]

**[ASSUME]** Missing data: business chưa định nghĩa “PMP-compliant application”. Reasoning: PMP là certification framework, trong khi PMBOK Guide và các PMI standards cung cấp guidance cho project management practices. Proposal này dùng PMBOK Guide Eighth Edition làm standard baseline, PMP Examination Content Outline July 2026 làm capability cross-check và PMI Standard for Earned Value Management cho phần performance/forecasting có cost data. [Source: PMI-PMBOK8; PMP-ECO-2026; PMI-EVM]

**[ASSUME]** Missing data: project hierarchy, task history, schedule baseline, actual progress và cost data chưa có. Reasoning: nguồn hiện tại phù hợp nhất với sprint-capacity planning; chưa đủ dữ liệu để quản lý workload theo task, đánh giá project progress hoặc forecast project outcome theo PMI. Classification: Partial foundation only. Confidence score: 100%. [Source: WB-s192-TICKET; WB-s192-META; WB-s192-RESOURCE; WB-ALL-TICKET; PMP-ECO-2026]

**[ASSUME]** Missing data: target delivery approach chưa được phê duyệt. Reasoning: workbook dùng sprint cadence trong khi PMP ECO bao phủ predictive, adaptive/agile và hybrid approaches. Đề xuất kiến trúc hybrid: PMI project-governance layer ở ngoài, Scrum/Scrumban delivery layer ở trong, và capacity engine hiện tại trở thành một planning service thay vì toàn bộ project-control system. [Source: WB-s192-META; PMP-ECO-2026; SG-2020; KG-LATEST]

## 2. Standards baseline

| Reference | Nội dung dùng trong proposal |
| --- | --- |
| PMBOK Guide Eighth Edition | Bản PMI hiện hành được công bố với trọng tâm value delivery, adaptability, accountability và các performance domains gồm governance, scope, schedule, finance, stakeholders, resources và risk. [Source: PMI-PMBOK8] |
| PMP Examination Content Outline July 2026 | Process domain yêu cầu integrated planning, estimate work effort/resource, scope breakdown, value-based delivery, resource management, schedule planning, task/milestone/dependency estimation, schedule baseline, variation analysis và project-status evaluation. [Source: PMP-ECO-2026, pp.8-10] |
| PMI Standard for Earned Value Management | EVM tích hợp scope, schedule và resources để đo performance/progress và forecast project outcome. [Source: PMI-EVM] |
| Scrum Guide | Scrum cung cấp Sprint, Product/Sprint Backlog, Product/Sprint Goal, Definition of Done, accountabilities và inspection/adaptation events. [Source: SG-2020, pp.5-12] |
| Kanban Guide | Kanban yêu cầu Definition of Workflow, WIP controls, explicit policies, SLE và các flow metrics. [Source: KG-LATEST] |

**[ASSUME]** Missing data: organization chưa phê duyệt compliance checklist. Reasoning: proposal không tuyên bố certification/compliance; nó cung cấp traceable capability alignment và các acceptance gates để business, PMO hoặc audit owner phê duyệt. [Source: PMI-PMBOK8; PMP-ECO-2026]

## 3. Current-state gap assessment

| Capability | Current evidence | Assessment | Gap cần xử lý |
| --- | --- | --- | --- |
| Sprint capacity | Có resource velocity/day, leave, holiday, OT, buffer, backup và Dev/QC/Biz outputs. [Source: WB-s192-META; WB-s192-RESOURCE; WB-s192-CALC] | **[ASSUME]** Missing data: approved interpretation policy. Reasoning: dữ liệu hỗ trợ capacity forecast theo sprint nhưng chưa gắn demand từ task. Classification: Partial. Confidence score: 100%. [Source: PMP-ECO-2026, p.8] | Task demand, assignment, time-phased load, over-allocation và resource-leveling decision. |
| Task management | Có ticket headers nhưng không có populated record trong vùng quan sát. [Source: WB-s192-TICKET; WB-ALL-TICKET] | **[ASSUME]** Missing data: representative records và task lifecycle. Reasoning: schema chưa đủ để chạy task-management process. Classification: Schema only. Confidence score: 100%. [Source: PMP-ECO-2026, p.9] | WBS/work package, dependency, milestone, duration, planned/actual dates, acceptance criteria và change history. |
| Progress management | `Status` tồn tại dưới dạng header; không có baseline, status-date hoặc actual-progress fields. [Source: WB-s192-TICKET; WB-ALL-TICKET] | **[ASSUME]** Missing data: progress-update method. Reasoning: không thể đánh giá current progress hoặc schedule variance từ header-only data. Classification: Not implemented. Confidence score: 100%. [Source: PMP-ECO-2026, p.9] | Baseline, actual start/finish, remaining effort, completion evidence, variance và milestone health. |
| Workload management | Capacity được tính theo resource/sprint/scenario. [Source: WB-s192-RESOURCE; WB-s189-1; WB-s189-2] | **[ASSUME]** Missing data: task-resource allocation. Reasoning: capacity supply chưa được reconcile với demand. Classification: Partial. Confidence score: 100%. [Source: PMP-ECO-2026, p.8] | Assignment, planned effort, availability calendar, demand-capacity balance và reallocation workflow. |
| Forecasting | Hai s189 sheets thể hiện OT scenarios; s192 tính remaining development days. [Source: WB-s189-1; WB-s189-2; WB-s192-CALC] | **[ASSUME]** Missing data: completed task history, dependency network, schedule baseline và cost data. Reasoning: current output là capacity what-if, không phải project completion/outcome forecast. Classification: Partial capacity forecast only. Confidence score: 100%. [Source: PMI-EVM; PMP-ECO-2026, p.9] | Schedule forecast, milestone forecast, forecast confidence, driver explanation và optional EVM. |
| Scrum | Có Sprint dates và ticket schema. [Source: WB-s192-META; WB-s192-TICKET] | **[ASSUME]** Missing data: Product Goal, Sprint Goal, Definition of Done, accountabilities và event outcomes. Reasoning: chỉ có planning inputs, chưa đủ bằng chứng cho complete Scrum. Classification: Partial. Confidence score: 95%. [Source: SG-2020, pp.5-12] | Goals, DoD, ordered backlog, Review/Retro outcomes và team ownership. |
| Scrumban | Có `Status`, point và `Added after Sprint start` headers. [Source: WB-s192-TICKET] | **[ASSUME]** Missing data: states, timestamps, WIP controls, SLE và transition history. Reasoning: không thể đo flow từ current source. Classification: Weak foundation. Confidence score: 100%. [Source: KG-LATEST; WB-ALL-TICKET] | Definition of Workflow, pull/WIP policies, flow metrics, aging và blockers. |
| Auditability | Có formula history và scenario variants; s175 hiển thị formula errors. [Source: WB-s175-ERROR; WB-s189-1; WB-s189-2; WB-s192-CALC] | **[ASSUME]** Missing data: rule governance và reconciliation tolerance. Reasoning: volatile/magic/error formulas làm baseline chưa đáng tin cậy. Classification: Must fix before forecast. Confidence score: 100%. [Source: WB-s175-ERROR; WB-s192-CALC] | Rule version, approved constants, deterministic as-of date, snapshot và golden reconciliation. |

## 4. Target operating model

**[ASSUME]** Missing data: PMO governance model và team operating agreement chưa có. Reasoning: đề xuất phân lớp để cùng một work item không bị quản lý bằng các thuật ngữ xung đột.

```text
Strategy / Value
  -> Project Charter and Outcomes
      -> Scope / Deliverable / WBS / Work Package
          -> Release / Sprint / Product Backlog
              -> Task / Work Item / Dependency / Milestone
                  -> Resource Assignment and Workload
                      -> Scrum/Scrumban Execution
                          -> Progress, Flow and Forecast Snapshots
                              -> Review, Change Control and Improvement
```

Mapping đề xuất:

| PMI/project view | Scrum/Scrumban view | Canonical application object |
| --- | --- | --- |
| Project outcome / benefit | Product Goal | `ProjectOutcome` |
| Deliverable / scope component | Product Backlog grouping | `Deliverable` |
| WBS node / work package | Epic or backlog group | `WorkPackage` |
| Activity/task | Product/Sprint Backlog item or Kanban work item | `Task` |
| Schedule baseline | Sprint/release plan snapshot | `BaselineSnapshot` |
| Status date | Review/flow observation time | `StatusSnapshot.as_of_date` |
| Resource requirement | Team capacity and assignment | `ResourceAssignment` |
| Issue/risk/change | Blocker, impediment or scope change | `Risk`, `Issue`, `ChangeRequest` |

**[ASSUME]** Missing data: canonical terminology chưa được phê duyệt. Reasoning: mapping trên cho phép predictive, agile và hybrid views dùng chung dữ liệu; labels hiển thị phải được business owner xác nhận trước implementation. [Source: PMP-ECO-2026; SG-2020; KG-LATEST]

## 5. Proposed upgrade scope

### 5.1 In scope

**[ASSUME]** Missing data: final release scope chưa được phê duyệt. Reasoning: các capability sau trực tiếp lấp gap đã quan sát và hỗ trợ PMI/Scrum/Scrumban alignment.

- Project, outcome, deliverable, WBS/work-package và task master.
- Task lifecycle, dependency, milestone, acceptance criteria và change history.
- Resource calendar, task assignment, workload demand và sprint capacity supply.
- Schedule baseline, status date, actual progress và progress evidence.
- Risks, issues, blockers và change requests.
- Scrum goals/DoD và Scrumban workflow/WIP/flow metrics.
- Capacity, schedule, milestone và historical-flow forecast.
- Conditional EVM module khi scope/schedule/cost baselines và actual cost đã được phê duyệt.
- Audit snapshots, rule versions, import/export và reconciliation.
- Jira Cloud read-only synchronization cho workload và velocity, với OAuth 2.0 (3LO), API-token POC, resource mapping và portable Sprint snapshots. [Source: CODE-JIRA-UI; CODE-JIRA-SERVICE]
- Streamlit Community Cloud deployment với stateless mode hoặc approved external persistence.

### 5.2 Deferred until prerequisites exist

**[ASSUME]** Missing data: cost baseline, actual cost, historical work-item records, source-ticket API và organization identity model. Reasoning: không thể triển khai đáng tin cậy các capability phụ thuộc dữ liệu này.

- EVM outputs và cost forecast.
- Probabilistic forecast hoặc calibrated SLE.
- Automated resource-leveling recommendation.
- Azure DevOps hoặc source system integration khác Jira Cloud.
- Organization-wide SSO/RBAC beyond an approved role matrix.
- AI-generated recommendation or autonomous schedule change.

### 5.3 Out of scope unless explicitly approved

**[ASSUME]** Missing data: payroll, HR, accounting và portfolio-management scope chưa được cung cấp. Reasoning: workbook chỉ dùng leave/OT cho capacity; không có payroll, accounting ledger hoặc portfolio data. [Source: WB-s192-LEAVE; WB-s192-RESOURCE]

- Payroll hoặc OT payment.
- HR performance scoring.
- General ledger hoặc financial accounting.
- Portfolio optimization across projects.
- Autonomous approval of baseline/change/forecast.

## 6. Consolidated improvement backlog

### 6.1 P0 — Source integrity and auditability

**[ASSUME]** Missing data: priority policy chưa được cung cấp. Reasoning: các issue sau ảnh hưởng trực tiếp đến tính đúng hoặc khả năng replay nên được đề xuất xử lý trước các forecast mới. [Source: WB-s175-ERROR; WB-s192-CALC]

| ID | Issue / improvement | Acceptance gate | Evidence |
| --- | --- | --- | --- |
| FIX-01 | Resolve `#VALUE!` outputs và chặn publish khi calculation error tồn tại. | Golden expected values do business phê duyệt; error không được hiển thị như KPI hợp lệ. | s175 có lỗi tại `E44:G44`, `E48:F51`, `E57`. [Source: WB-s175-ERROR] |
| FIX-02 | Thay literal `3` trong `Dev days` bằng named, versioned rule. | Rule có meaning, owner, version và effective status. | `s192!C44` trừ literal `3`. [Source: WB-s192-CALC] |
| FIX-03 | Thay `TODAY()` bằng explicit `as_of_date`. | Cùng snapshot và rule version phải replay cùng output. | `s192!D44` dùng `TODAY()`. [Source: WB-s192-CALC] |
| FIX-04 | Tách `Leave date` và `TBD` status. | Date chỉ chứa ngày/null; TBD lưu ở status. | `s192!M2:R12` trộn date và text. [Source: WB-s192-LEAVE] |
| FIX-05 | Version schema importer theo header signature. | Không silently drop/mis-map field giữa schema variants. | s158 summary ở `A43:O44`; s192 summary ở `A43:Y44`. [Source: WB-s158-SUMMARY; WB-s192-SUMMARY] |
| FIX-06 | Chuyển duplicate scenario sheets thành `Scenario` entity. | Base, override, reason và delta được lưu riêng; không overwrite. | Hai s189 sheets dùng cùng sprint với OT/output khác nhau. [Source: WB-s189-1; WB-s189-2] |
| FIX-07 | Isolate ticket-dependent calculations đến khi có representative data. | Không có task/progress KPI nào được publish từ header-only data. | Không có populated ticket record trong vùng quan sát. [Source: WB-ALL-TICKET] |
| FIX-08 | Tạo immutable calculation/import/status snapshots. | Snapshot lưu input, output, warning, source locator, as-of date và rule version. | Workbook có formula/schema/scenario variance. [Source: WB-s192-CALC; WB-s175-ERROR; WB-s189-1; WB-s189-2] |

### 6.2 P1 — Scrum/Scrumban process foundation

**[ASSUME]** Missing data: process owner và sequencing chưa được cung cấp. Reasoning: giữ nguyên các improvement đã đề xuất vì chúng là prerequisites cho task/progress semantics.

| ID | Improvement | Acceptance gate | Source |
| --- | --- | --- | --- |
| IMP-01 | Product Goal và Sprint Goal | Sprint planning/review record truy vết được goal và outcome. | [Source: SG-2020, pp.10-11] |
| IMP-02 | Versioned Definition of Done | `Done` chỉ hợp lệ khi có DoD evidence. | [Source: SG-2020, p.12] |
| IMP-03 | Ordered backlog và readiness policy | Task được chọn cho Sprint có order, acceptance criteria và ready status. | [Source: WB-s192-TICKET; SG-2020, pp.10-11] |
| IMP-04 | Capacity là forecast input, không phải commitment KPI | UI tách planned capacity, selected work và completed outcome. | [Source: WB-s192-CALC; SG-2020, p.8] |
| IMP-05 | Team-level ownership | Skill/capacity breakdown không tạo Dev/QC sub-team accountability. | [Source: WB-s192-RESOURCE; SG-2020, p.5] |
| IMP-06 | Scope-change governance | Item thêm sau Sprint start có time, reason, requester, decision và goal impact. | [Source: WB-s192-TICKET; SG-2020, pp.7,11] |
| IMP-07 | No-OT baseline và sustainable-pace guardrail | OT scenario phải có baseline, reason và approval. | [Source: WB-s189-1; WB-s189-2; SG-2020, p.5] |
| IMP-08 | Sprint Review outcome register | Review lưu Increment evidence, feedback và backlog adaptation. | [Source: SG-2020, pp.9-10] |
| IMP-09 | Retrospective experiment register | Improvement có hypothesis, owner, observed result và disposition. | [Source: SG-2020, p.10; KG-LATEST] |
| NF-01 | Configurable Definition of Workflow | States, started/finished points, policies, WIP controls và SLE được versioned. | [Source: KG-LATEST] |
| NF-02 | WIP controls và pull signal | Breach hiển thị rõ; exception có reason. | [Source: KG-LATEST] |
| NF-03 | WIP, throughput, work-item age và cycle time | Metric dùng approved started/finished points và timestamp history. | [Source: KG-LATEST] |
| NF-04 | Aging WIP dashboard | Started item được xếp theo age/blocker/SLE risk. | [Source: KG-LATEST] |
| NF-05 | Cumulative Flow và cycle-time analytics | Charts reconcile với immutable transition records. | [Source: KG-LATEST] |
| NF-06 | Service Level Expectation | SLE chỉ publish khi có approved elapsed period/probability và calculation source. | [Source: KG-LATEST] |
| NF-07 | Flow-risk alerts | Alert chỉ rõ item, policy/SLE bị vi phạm và review action. | [Source: KG-LATEST] |
| NF-08 | Historical flow forecast | Forecast lưu input window, method và confidence. | [Source: WB-ALL-TICKET; KG-LATEST] |
| NF-09 | Product outcome tracking | Dashboard tách capacity, delivery và outcome. | [Source: WB-s192-CALC; SG-2020, pp.9-12] |
| NF-10 | Ticket-system adapter | Import idempotent; source ID/update timestamp được bảo toàn. | [Source: WB-s192-TICKET; WB-ALL-TICKET] |

### 6.3 P1 — New project and task-management capabilities

**[ASSUME]** Missing data: PMO lifecycle, field definitions và approval matrix chưa được cung cấp. Reasoning: các backlog items sau lấp gap PMP workload/task/progress đã xác định; field names và transitions cần sign-off. [Source: PMP-ECO-2026, pp.8-10]

| ID | New improvement | Minimum acceptance criteria |
| --- | --- | --- |
| PM-01 | Project charter, objective và outcome register | Project có owner, objective, outcome hypothesis, governance status và source evidence. |
| PM-02 | Deliverable/WBS/work-package hierarchy | Task phải truy vết tới work package và deliverable; hierarchy validation chặn orphan records. |
| PM-03 | Canonical task master | Task có ID, summary, type, owner, priority/order, planned effort, planned dates, current status và acceptance criteria. |
| PM-04 | Dependency and milestone management | Dependency có predecessor, successor, type và validation; milestone có planned/actual status. |
| PM-05 | Resource assignment | Assignment nối task, resource, planned effort, date window, role/skill và scenario. |
| PM-06 | Resource calendar and availability | Working calendar kết hợp working days, public holiday, leave và approved availability exceptions. |
| PM-07 | Schedule baseline versioning | Approved baseline là immutable; rebaseline tạo version và change reference mới. |
| PM-08 | Status-date progress update | Status snapshot lưu as-of date, actual dates, remaining effort, completion state, evidence và updater. |
| PM-09 | Risk, issue, blocker and change control | Mỗi record có owner, state, impact, action/response và audit trail; change liên kết baseline/scope impact. |
| PM-10 | Integrated status dashboard | Dashboard phân biệt baseline, current plan, actual, forecast, risk/issue và value outcome. |
| PM-11 | Governance and approvals | Baseline, change, forecast publication và exception có role-based approval record. |
| PM-12 | Hybrid traceability | Project → deliverable → work package → release/sprint → task → status/forecast có end-to-end trace. |

### 6.4 P2 — New workload, progress and forecast capabilities

**[ASSUME]** Missing data: metric thresholds, forecast method, history window và cost policy chưa được cung cấp. Reasoning: các items sau chỉ được publish khi prerequisites và formulas được phê duyệt. [Source: PMI-EVM; PMP-ECO-2026; WB-ALL-TICKET]

| ID | New feature | Data prerequisite | Minimum acceptance criteria |
| --- | --- | --- | --- |
| WL-01 | Demand-versus-capacity by resource/time period | Task assignments, planned effort, resource calendar. | Demand và capacity reconcile về source records; period và unit hiển thị rõ. |
| WL-02 | Over-allocation detection | Approved availability and assignment rules. | Breach chỉ ra resource, period, demand, capacity và contributing tasks. |
| WL-03 | Workload heatmap | Time-phased assignment and capacity. | Heatmap có drill-down tới task; không dùng missing data như zero. |
| WL-04 | Reallocation scenario | Alternative assignee/date/effort rules. | Scenario không sửa approved baseline; delta và assumptions được lưu. |
| PR-01 | Baseline-versus-actual progress | Approved baseline và status snapshots. | Mỗi variance truy vết tới baseline version, status date và source update. |
| PR-02 | Milestone health | Milestone plan, dependencies and actual/forecast dates. | Health không publish nếu thiếu planned date hoặc forecast method. |
| PR-03 | Remaining-work view | Remaining effort hoặc approved completion method. | Completed, remaining và unknown được phân biệt; unknown không bị coi là zero. |
| PR-04 | Progress evidence and reconciliation | Task evidence, transition history and update owner. | Dashboard aggregate phải reconcile với task-level records. |
| FC-01 | Deterministic schedule forecast | Valid task durations, dependencies, calendars and status date. | Forecasted dates lưu algorithm/rule version và blocking validation results. |
| FC-02 | Milestone completion forecast | FC-01 prerequisites và milestone mapping. | Mỗi forecast có as-of date, drivers, assumptions và confidence status. |
| FC-03 | Agile/hybrid historical forecast | Completed timestamped work-item history. | Input history window, exclusions, method và result được audit. |
| FC-04 | Capacity scenario forecast | Existing capacity engine plus task demand. | No-OT/base/alternative scenarios compare supply, demand, finish impact và gaps. |
| FC-05 | Forecast explanation | Any approved forecast. | UI hiển thị source data, rule version, top drivers và validation warnings. |
| FC-06 | Conditional EVM | Approved scope/schedule/cost baseline, actual cost and progress measurement method. | EVM page disabled với explicit missing-data list nếu prerequisites thiếu. |
| FC-07 | Forecast snapshot history | Approved persistence and retention policy. | Forecast snapshots immutable, comparable và reproducible. |

### 6.5 P3 — Current-PMI extension candidates

**[ASSUME]** Missing data: sustainability objectives, AI policy và data-governance controls chưa được cung cấp. Reasoning: PMBOK Eighth Edition expands AI coverage, còn PMP 2026 tăng emphasis vào AI, sustainability, value và business impact; các items sau là optional candidates, không phải MVP prerequisites. [Source: PMI-PMBOK8; PMP-EXAM-UPDATE]

| ID | Candidate | Guardrail |
| --- | --- | --- |
| EXT-01 | Sustainability impact field at project/deliverable/change level | Không invent targets; chỉ dùng approved measures. |
| EXT-02 | AI-assisted status summary | Output phải trích source tasks/risks/issues; user review bắt buộc. |
| EXT-03 | AI-assisted scenario explanation | Không tự sửa assignment, baseline hoặc approval state. |
| EXT-04 | Value and business-impact dashboard | Metric owner, definition, source và review cadence phải được phê duyệt. |

## 7. Functional requirements

| Requirement | Proposed behavior | Acceptance gate |
| --- | --- | --- |
| FR-UP-01 Project setup | **[ASSUME]** Missing data: charter template. Reasoning: tạo project, objective, outcome, approach và governance status bằng configurable form. [Source: PMP-ECO-2026, pp.8-10] | Required fields do owner phê duyệt; version/audit metadata được lưu. |
| FR-UP-02 WBS and deliverables | **[ASSUME]** Missing data: hierarchy rules. Reasoning: scope breakdown là capability PMP còn thiếu. [Source: PMP-ECO-2026, p.8] | Tree không có cycle/orphan; task trace được tới scope parent. |
| FR-UP-03 Task planning | **[ASSUME]** Missing data: task field policy. Reasoning: source chỉ có ticket header. [Source: WB-s192-TICKET] | Planned effort/date/status/acceptance fields được validate; unknown không thành zero. |
| FR-UP-04 Dependencies and milestones | **[ASSUME]** Missing data: allowed dependency types. Reasoning: PMP ECO yêu cầu estimate task/milestone/dependency. [Source: PMP-ECO-2026, p.9] | Cycle và invalid references bị block; milestones có planned/actual/forecast views. |
| FR-UP-05 Resource assignments | **[ASSUME]** Missing data: effort unit và assignment policy. Reasoning: nối task demand với current capacity supply. [Source: WB-s192-RESOURCE; PMP-ECO-2026, p.8] | Assignment totals reconcile; unit/period rõ ràng. |
| FR-UP-06 Workload dashboard | **[ASSUME]** Missing data: overload threshold. Reasoning: demand-capacity view cần task assignments và calendar. | Drill-down resource → period → task; missing capacity/demand được cảnh báo. |
| FR-UP-07 Baseline approval | **[ASSUME]** Missing data: approval roles. Reasoning: schedule baseline và change governance cần immutable version. [Source: PMP-ECO-2026, p.9] | Approved baseline không sửa trực tiếp; change tạo successor version. |
| FR-UP-08 Progress update | **[ASSUME]** Missing data: progress measurement method. Reasoning: status evaluation cần actual/remaining data. [Source: PMP-ECO-2026, p.9] | Snapshot có status date, updater, evidence và validation. |
| FR-UP-09 Risk/issue/change | **[ASSUME]** Missing data: taxonomy và thresholds. Reasoning: PMP ECO yêu cầu risk, impediment, governance và escalation practices. [Source: PMP-ECO-2026, pp.10-11] | Owner/state/impact/action/audit trail bắt buộc theo approved policy. |
| FR-UP-10 Forecast service | **[ASSUME]** Missing data: selected algorithms. Reasoning: predictive và agile forecasts cần dữ liệu khác nhau. | Method chỉ chạy khi prerequisites pass; output có source/rule/as-of/confidence status. |
| FR-UP-11 Conditional EVM | **[ASSUME]** Missing data: cost baseline và actual cost. Reasoning: EVM không thể tính từ story points/capacity alone. [Source: PMI-EVM; WB-ALL-TICKET] | Module disabled khi prerequisite thiếu; không synthesize cost. |
| FR-UP-12 Hybrid board | **[ASSUME]** Missing data: workflow states. Reasoning: cần cùng task data nhưng nhiều views. [Source: SG-2020; KG-LATEST] | Board, backlog, WBS và schedule views reconcile cùng task IDs. |
| FR-UP-13 Status reporting | **[ASSUME]** Missing data: reporting audience/template. Reasoning: PMP ECO yêu cầu project-status communication. [Source: PMP-ECO-2026, p.9] | Export ghi as-of date, baseline, actual, forecast, risks, issues và assumptions. |
| FR-UP-14 Import/export | **[ASSUME]** Missing data: canonical integration schema. Reasoning: workbook schema thay đổi và ticket source chưa chọn. [Source: WB-s158-SUMMARY; WB-s192-SUMMARY; WB-ALL-TICKET] | Import report có mapped/skipped/error; export re-import được theo version. |
| FR-UP-15 Audit | **[ASSUME]** Missing data: retention. Reasoning: baseline/progress/forecast decisions cần replay. | Mọi approved snapshot có immutable payload, actor, time, rule version và source locator. |

## 8. Proposed business rules

### 8.1 Capacity supply

**[ASSUME]** Missing data: canonical capacity rules chưa được phê duyệt. Reasoning: các current formulas là evidence duy nhất của calculation behavior; đề xuất giữ chúng làm versioned baseline sau business reconciliation và không tự sửa ý nghĩa metric. [Source: WB-s192-CALC]

**[ASSUME]** Missing data: authoritative formula set, hours-per-day, literal `3` và rounding policy. Reasoning: current engine chỉ được dùng cho production sau khi FIX-01 đến FIX-08 có disposition. [Source: WB-s175-ERROR; WB-s192-CALC]

### 8.2 Workload demand

**[ASSUME]** Missing data: effort unit và allocation semantics. Reasoning: proposed task demand bằng tổng approved assignment effort trong cùng resource/period/scenario; formula và unit phải được sign-off trước publish.

**[ASSUME]** Missing data: overload tolerance. Reasoning: hệ thống có thể xác định direct breach khi approved demand lớn hơn approved capacity; không invent warning bands hoặc utilization targets.

### 8.3 Progress

**[ASSUME]** Missing data: physical-percent-complete, remaining-effort hoặc milestone-weighting policy. Reasoning: progress phải lấy từ approved actual data; `Status` hoặc story point không tự động chuyển thành phần trăm hoàn thành. [Source: WB-s192-TICKET; PMP-ECO-2026, p.9]

**[ASSUME]** Missing data: status-update cadence. Reasoning: mọi progress view phải gắn explicit `as_of_date`, baseline version và source update; không dùng current clock để thay đổi historical result.

### 8.4 Forecast

**[ASSUME]** Missing data: selected forecast method. Reasoning: predictive schedule forecast cần dependency/duration/calendar; agile flow forecast cần historical completed work items; capacity scenario forecast cần task demand và current supply. Hệ thống phải chọn method theo available approved data, không mix dữ liệu không tương thích. [Source: PMP-ECO-2026, p.9; KG-LATEST]

**[ASSUME]** Missing data: confidence policy. Reasoning: forecast thiếu đủ history hoặc dependency coverage phải mang status `insufficient data`, không publish invented completion date.

### 8.5 Earned Value Management

PMI mô tả EVM là phương pháp tích hợp scope, schedule và resources để đo project performance/progress và forecast outcome. [Source: PMI-EVM]

**[ASSUME]** Missing data: work-package budget, time-phased cost baseline, actual cost và progress-measurement technique. Reasoning: EVM feature phải bị disable cho current workbook; proposal không invent PV, EV, AC hoặc derived forecast values. [Source: WB-s192-TICKET; WB-s192-RESOURCE; WB-ALL-TICKET; PMI-EVM]

## 9. Logical data model extension

**[ASSUME]** Missing data: canonical data dictionary chưa được phê duyệt. Reasoning: model dưới đây mở rộng các entities quan sát được trong workbook bằng các entities cần cho project/task/workload/progress/forecast; field definitions và required/optional status phải được business owner sign-off.

| Entity | Minimum proposed fields | Traceability |
| --- | --- | --- |
| Project | project_id, name, objective, approach, owner, governance_status | New; aligned to PMP integrated plan/governance. [Source: PMP-ECO-2026] |
| ProjectOutcome | outcome_id, project_id, definition, measure, owner, review_status | New; aligned to value-based delivery. [Source: PMP-ECO-2026, p.8] |
| Deliverable | deliverable_id, project_id, name, acceptance_criteria, status | New; scope trace. [Source: PMP-ECO-2026, p.8] |
| WorkPackage | work_package_id, parent_id, deliverable_id, owner, status | New; scope breakdown. [Source: PMP-ECO-2026, p.8] |
| Sprint | existing fields plus project_id, sprint_goal, dod_version | Extends workbook Sprint. [Source: WB-s192-META; SG-2020] |
| Task | task_id, work_package_id, sprint_id, summary, type, priority/order, status, acceptance_criteria | Extends workbook ticket schema. [Source: WB-s192-TICKET] |
| TaskPlan | task_id, planned_effort, effort_unit, planned_start, planned_finish, duration, calendar_id | New; schedule/workload. [Source: PMP-ECO-2026, p.9] |
| Dependency | dependency_id, predecessor_task_id, successor_task_id, type, lag_rule | New; task dependency. [Source: PMP-ECO-2026, p.9] |
| Milestone | milestone_id, work_package_id, planned_date, actual_date, forecast_date, status | New; schedule. [Source: PMP-ECO-2026, p.9] |
| Resource | existing fields plus calendar_id, role/skill tags | Extends workbook resource. [Source: WB-s192-RESOURCE] |
| ResourceAssignment | assignment_id, task_id, resource_id, scenario_id, effort, start, finish, role | New; connects demand and supply. [Source: PMP-ECO-2026, p.8] |
| Calendar | calendar_id, working_days, holidays, exceptions | Extends workbook public holiday/leave. [Source: WB-s192-META; WB-s192-LEAVE] |
| BaselineSnapshot | baseline_id, project_id, scope_payload, schedule_payload, approval, source_version | New; baseline control. [Source: PMP-ECO-2026, p.9] |
| StatusSnapshot | status_id, as_of_date, task_actuals, remaining_work, evidence, updater | New; progress control. [Source: PMP-ECO-2026, p.9] |
| ForecastSnapshot | forecast_id, as_of_date, method, inputs, outputs, drivers, warnings, rule_version | New; forecast audit. [Source: PMI-EVM; KG-LATEST] |
| Risk | risk_id, project_id, owner, probability/impact fields, response, status | New; values require approved policy. [Source: PMP-ECO-2026, p.10] |
| Issue | issue_id, project_id, task_id, owner, impact, action, status | New; issue/blocker management. [Source: PMP-ECO-2026, p.10] |
| ChangeRequest | change_id, scope/schedule/cost impact, decision, approver, baseline_reference | Extends `Added after Sprint start`. [Source: WB-s192-TICKET; PMP-ECO-2026] |
| CostBaseline | work_package_id, time_period, approved_budget | Conditional; not present in source. [Source: PMI-EVM] |
| ActualCost | work_package_id, time_period, actual_cost, source_reference | Conditional; not present in source. [Source: PMI-EVM] |

## 10. Application experience

**[ASSUME]** Missing data: final navigation and user roles. Reasoning: đề xuất Streamlit multipage experience sau để tách input, planning, control và audit.

| Page | Purpose |
| --- | --- |
| Home / Import | Import workbook or approved snapshot; show schema/error report. |
| Project & Outcomes | Charter, objective, outcomes, governance and approvals. |
| Scope / WBS | Deliverable and work-package hierarchy. |
| Backlog / Tasks | Task grid, acceptance criteria, dependencies and milestones. |
| Sprint / Workflow | Sprint Goal, DoD, backlog, Kanban states and WIP policies. |
| Resources / Calendars | Resource, availability, leave, holiday and capacity. |
| Assignments / Workload | Demand-capacity table, heatmap, breach detail and scenario. |
| Baseline / Progress | Baseline, status date, actuals, remaining work and evidence. |
| Risks / Issues / Changes | Registers, ownership, impact, action and decision trail. |
| Forecast | Capacity, schedule, milestone, flow and conditional EVM views. |
| Status Dashboard | Value, delivery, progress, workload, risk and forecast summary. |
| Audit / Export | Snapshot history, formula trace, import/export and reconciliation. |

## 11. Technical architecture

**[ASSUME]** Missing data: production topology, concurrency, retention và security classification chưa được phê duyệt. Reasoning: giữ modular architecture của technical proposal hiện tại và thêm project-control services. [Source: DOC-TECH]

```text
Browser
  -> Streamlit Multipage UI
      -> Application Services
          -> Project / WBS / Task Service
          -> Resource / Workload Service
          -> Baseline / Progress Service
          -> Risk / Issue / Change Service
          -> Capacity Calculation Engine
          -> Schedule / Flow / Forecast Engine
          -> Validation / Reconciliation / Audit
              -> Snapshot Repository
                  -> Session + Download/Upload
                  -> External SQL Repository (conditional)
```

Architecture principles:

- **[ASSUME]** Missing data: framework versions. Reasoning: pin tested runtime/dependencies because Community Cloud may upgrade unpinned dependencies. [Source: ST-RESOURCES]
- **[ASSUME]** Missing data: persistence decision. Reasoning: generated files on Community Cloud are not guaranteed to persist across sessions; stateless snapshot download/upload is the safe baseline, while shared history requires an approved external store. [Source: ST-PERSIST]
- **[ASSUME]** Missing data: database/provider decision. Reasoning: use repository interfaces so persistence can switch without embedding database logic in UI.
- **[ASSUME]** Missing data: forecast algorithm. Reasoning: calculation and forecast engines must be pure, versioned and independently testable.
- **[ASSUME]** Missing data: authentication/authorization matrix. Reasoning: credentials for external services belong in Streamlit secrets; permissions require explicit app/platform policy. [Source: ST-SECRETS]
- **[ASSUME]** Missing data: expected dataset size and concurrent users. Reasoning: Community Cloud resource limits can change; cache must be bounded and large history should be queried from external storage rather than loaded entirely. [Source: ST-RESOURCES]

## 12. Persistence modes

| Mode | Proposed use | Constraint |
| --- | --- | --- |
| Stateless pilot | **[ASSUME]** Missing data: shared-history requirement. Reasoning: quickest deployable free target; user uploads workbook/snapshot and downloads immutable result. [Source: UR-REQ; ST-PERSIST] | Không có cross-session shared history. |
| Persistent controlled release | **[ASSUME]** Missing data: retention, concurrency, database và security approval. Reasoning: workload/progress/forecast history cần remote persistence khi nhiều người cộng tác. [Source: ST-PERSIST; ST-SECRETS] | External store và credentials phải được phê duyệt; free availability không được giả định. |

CRITICAL DATA MISSING: stateless pilot hay persistent multi-user release - Not found in context. [Source: UR-REQ; ST-PERSIST]

## 13. Security, privacy and audit

Workbook chứa resource names và leave information. [Source: WB-s192-LEAVE; WB-s192-RESOURCE]

**[ASSUME]** Missing data: data classification, identity provider, role matrix và retention. Reasoning: production app phải giữ private-by-default cho đến khi owner phê duyệt sharing policy; demo public chỉ dùng sanitized hoặc synthetic data. [Source: WB-s192-LEAVE; ST-SECURITY]

**[ASSUME]** Missing data: approval roles. Reasoning: tối thiểu cần phân biệt viewer, contributor và approver cho baseline/change/forecast publication; role names cuối cùng cần sign-off.

**[ASSUME]** Missing data: canonical audit schema chưa được phê duyệt. Reasoning: đề xuất audit record phải lưu:

- source document/sheet/range hoặc source-system ID;
- import/schema version;
- calculation/forecast rule version;
- explicit as-of date;
- inputs, outputs, warnings và validation status;
- actor/update/approval metadata;
- baseline/change/forecast relationships.

**[ASSUME]** Missing data: retention and deletion policy. Reasoning: không đặt retention duration trong proposal; mỗi environment phải cấu hình theo approved policy.

## 14. Validation and test strategy

**[ASSUME]** Missing data: approved test plan và test owners chưa được cung cấp. Reasoning: các test groups dưới đây là minimum proposal để bảo vệ source reconciliation, task integrity, workload/progress accuracy, forecast prerequisites và deployment behavior.

| Test group | Required test |
| --- | --- |
| Source reconciliation | s192 capacity outputs reconcile với approved golden values; s175 errors bị block. [Source: WB-s192-CALC; WB-s175-ERROR] |
| Schema migration | s158 và s192 import qua versioned adapters, không silently drop field. [Source: WB-s158-SUMMARY; WB-s192-SUMMARY] |
| Scenario integrity | Hai s189 scenarios giữ riêng overrides và output delta. [Source: WB-s189-1; WB-s189-2] |
| WBS/task integrity | No orphan/cycle/invalid reference; task trace về deliverable/work package. |
| Schedule integrity | Dependency-cycle, missing calendar, invalid dates và missing baseline bị block. |
| Workload reconciliation | Resource-period demand bằng assignment records; capacity bằng approved engine inputs. |
| Progress reconciliation | Aggregate progress reconcile về status snapshots; unknown không thành zero. |
| Forecast prerequisites | Method không chạy khi thiếu required duration/dependency/history/baseline/cost data. |
| Forecast replay | Cùng source snapshot, as-of date và rule version cho cùng output. |
| EVM guardrail | EVM disabled nếu thiếu scope/schedule/cost baseline, actual cost hoặc progress method. |
| Security | Unauthorized approval/edit blocked; secrets không có trong repository/log. |
| Deployment | App starts from repository root; imports, downloads and bounded caching work on Community Cloud. [Source: ST-FILES; ST-RESOURCES] |

**[ASSUME]** Missing data: numeric tolerance, rounding, performance target và concurrent-user target. Reasoning: không đặt pass threshold hoặc SLA số học cho đến khi business/technical owner phê duyệt.

## 15. Delivery gates

### Gate A — Trusted source and rules

**[ASSUME]** Missing data: calculation owner và expected golden values. Reasoning: exit khi FIX-01 đến FIX-08 có disposition, current formulas reconcile và snapshots replay được. [Source: WB-s175-ERROR; WB-s192-CALC]

### Gate B — Project/task foundation

**[ASSUME]** Missing data: lifecycle, WBS, task fields và approval roles. Reasoning: exit khi PM-01 đến PM-12 có approved model, validation và traceability.

### Gate C — Workload and progress

**[ASSUME]** Missing data: effort unit, resource calendar, baseline và status-update policy. Reasoning: exit khi demand-capacity và baseline-actual views reconcile tới assignments/status snapshots.

### Gate D — Schedule and flow forecast

**[ASSUME]** Missing data: forecast method, history window và confidence policy. Reasoning: exit khi each enabled method passes prerequisites, explains drivers và replays from immutable snapshot.

### Gate E — Conditional EVM and integrations

**[ASSUME]** Missing data: cost baseline/actuals, ticket system, identity and persistence decisions. Reasoning: chỉ mở gate khi business cung cấp authoritative inputs và owners. [Source: PMI-EVM; WB-ALL-TICKET]

### Gate F — Current-PMI extensions

**[ASSUME]** Missing data: AI and sustainability policies. Reasoning: optional extensions chỉ vào production sau data governance, review/approval và source-grounding tests. [Source: PMI-PMBOK8; PMP-EXAM-UPDATE]

**[ASSUME]** Missing data: team capacity, implementation dependencies và release constraints chưa được phê duyệt. Reasoning: proposal không đặt timeline để tránh invent duration.

## 16. Risk and anomaly register

| Risk / anomaly | Confidence | Evidence / reasoning | Required response |
| --- | --- | --- | --- |
| Formula error làm baseline sai | **[ASSUME]** Missing data: root cause. Reasoning: lỗi ô là direct evidence. Confidence score: 100%. | `#VALUE!` ở s175. [Source: WB-s175-ERROR] | Fix, golden reconciliation và publish blocker. |
| Hidden business constant | **[ASSUME]** Missing data: meaning của literal. Reasoning: literal xuất hiện trực tiếp trong formula. Confidence score: 100%. | `s192!C44` trừ `3`. [Source: WB-s192-CALC] | Named/versioned rule và owner approval. |
| Historical output thay đổi theo current date | **[ASSUME]** Missing data: as-of policy. Reasoning: `TODAY()` là direct formula dependency. Confidence score: 100%. | `s192!D44`. [Source: WB-s192-CALC] | Explicit as-of date và immutable snapshot. |
| Workload bị hiểu nhầm từ capacity supply | **[ASSUME]** Missing data: task assignments. Reasoning: workbook có supply nhưng không có demand records. Confidence score: 100%. | Resource capacity có; ticket records không có. [Source: WB-s192-RESOURCE; WB-ALL-TICKET] | Không publish utilization/overload trước assignments. |
| Progress bị suy diễn từ `Status` hoặc point | **[ASSUME]** Missing data: progress method/history. Reasoning: chỉ có headers, không có actual evidence. Confidence score: 100%. | [Source: WB-s192-TICKET; WB-ALL-TICKET] | Explicit status snapshots và evidence. |
| Forecast completion date bị invent | **[ASSUME]** Missing data: duration/dependency/history/baseline. Reasoning: current scenario chỉ là capacity what-if. Confidence score: 100%. | [Source: WB-s189-1; WB-s189-2; PMP-ECO-2026] | Prerequisite gate và insufficient-data state. |
| EVM bị tính từ story point/velocity | **[ASSUME]** Missing data: cost baseline và actual cost. Reasoning: current source không có EVM inputs. Confidence score: 100%. | [Source: WB-s192-TICKET; WB-s192-RESOURCE; PMI-EVM] | Disable EVM cho đến khi authoritative cost data tồn tại. |
| Dev/QC trở thành handoff silo | **[ASSUME]** Missing data: actual team agreements. Reasoning: separate capacity/type tạo risk nhưng không chứng minh operating behavior. Confidence score: 85%. | [Source: WB-s192-RESOURCE; SG-2020, p.5] | Team ownership, DoD và flow visibility. |
| OT trở thành default plan | **[ASSUME]** Missing data: approval/frequency history. Reasoning: hai OT scenarios tồn tại nhưng usage policy không có. Confidence score: 85%. | [Source: WB-s189-1; WB-s189-2] | No-OT baseline, approval and post-result review. |
| Local files bị dùng như production database | **[ASSUME]** Missing data: persistence decision. Reasoning: generated files are not guaranteed to persist across Community Cloud sessions. Confidence score: 100%. | [Source: ST-PERSIST] | Stateless downloads hoặc approved external store. |
| Sensitive employee data bị public | **[ASSUME]** Missing data: classification/sharing policy. Reasoning: source contains names and leave information. Confidence score: 100%. | [Source: WB-s192-LEAVE; WB-s192-RESOURCE] | Private default; sanitized public demo. |
| Scope tăng quá rộng trước khi data nền ổn định | **[ASSUME]** Missing data: release priority and capacity. Reasoning: PM, flow, forecast and integration all depend on P0 data integrity. Confidence score: 95%. | [Source: WB-s175-ERROR; WB-ALL-TICKET] | Enforce delivery gates; do not parallel-publish invalid metrics. |

## 17. Critical data missing and decision requests

CRITICAL DATA MISSING: Definition of “PMP-compliant” và compliance owner - Not found in context.

CRITICAL DATA MISSING: Project charter, objectives, outcome/value measures và governance lifecycle - Not found in context. [Source: WB-s192-SUMMARY]

CRITICAL DATA MISSING: Deliverable/WBS/work-package hierarchy - Not found in context. [Source: WB-s192-TICKET]

CRITICAL DATA MISSING: Representative task records, task lifecycle và allowed statuses - Not found in context. [Source: WB-ALL-TICKET]

CRITICAL DATA MISSING: Task dependencies, milestones, durations, planned start và planned finish - Not found in context. [Source: WB-s192-TICKET]

CRITICAL DATA MISSING: Task-level resource assignments, planned effort, effort unit và availability policy - Not found in context. [Source: WB-s192-RESOURCE; WB-ALL-TICKET]

CRITICAL DATA MISSING: Approved schedule baseline, baseline-change policy và approver - Not found in context.

CRITICAL DATA MISSING: Status date, actual start/finish, remaining effort và progress measurement method - Not found in context. [Source: WB-s192-TICKET]

CRITICAL DATA MISSING: Forecast algorithm, history window, confidence policy và publish threshold - Not found in context.

CRITICAL DATA MISSING: Cost baseline, work-package budget, actual cost và EVM measurement technique - Not found in context. [Source: WB-s192-TICKET; WB-s192-RESOURCE; PMI-EVM]

CRITICAL DATA MISSING: Risk, issue, blocker, change and escalation taxonomies - Not found in context.

CRITICAL DATA MISSING: Product Goal, Sprint Goal, Definition of Done và Scrum accountabilities - Not found in context. [Source: WB-s192-TICKET; WB-s192-META]

CRITICAL DATA MISSING: Definition of Workflow, WIP controls, SLE và work-item transition history - Not found in context. [Source: WB-ALL-TICKET]

CRITICAL DATA MISSING: Authoritative meaning của literal `3`, OT hours-to-days conversion, rounding và reconciliation tolerance - Not found in context. [Source: WB-s192-CALC; WB-s189-1; WB-s189-2]

CRITICAL DATA MISSING: Stateless hay persistent multi-user deployment - Not found in context. [Source: UR-REQ; ST-PERSIST]

CRITICAL DATA MISSING: Data classification, public/private policy, identity provider, role matrix và retention - Not found in context. [Source: WB-s192-LEAVE; ST-SECURITY]

CRITICAL DATA MISSING: Jira tenant, Board ID, live credentials, OAuth app configuration và migration scope - Not found in context. [Source: UR-REQ; CODE-JIRA-UI]

CRITICAL DATA MISSING: AI use policy, human approval and sustainability measures - Not found in context. [Source: PMI-PMBOK8; PMP-EXAM-UPDATE]

## 18. Proposal acceptance criteria

**[ASSUME]** Missing data: final approvers chưa được xác định. Reasoning: proposal sẵn sàng chuyển sang implementation planning khi các điều kiện sau có owner/disposition.

- P0 source/calculation issues có approved resolution.
- Canonical project/WBS/task/resource/baseline/status models được phê duyệt.
- Workload effort unit, availability và assignment policies được phê duyệt.
- Progress measurement và status-date policy được phê duyệt.
- Forecast methods, prerequisites và confidence/publication policy được phê duyệt.
- EVM được explicitly enabled hoặc deferred dựa trên cost-data availability.
- Scrum/Scrumban terminology, workflow, DoD, WIP and SLE policies có owner.
- Deployment mode, security, roles, retention and data classification được phê duyệt.
- Test golden cases và audit snapshot requirements được phê duyệt.

## 19. Source register

| Source ID | Exact source |
| --- | --- |
| UR-REQ | User request: analyze workbook, propose Streamlit Community Cloud web application, add Scrum/Scrumban and PMP workload/task/progress/forecast improvements, output Markdown. |
| DOC-BRD | `Saturn_Velocity_BRD.md`, current BRD generated from the workbook. |
| DOC-TECH | `Saturn_Velocity_Technical_Proposal_Streamlit.md`, current technical proposal. |
| DOC-IMPROVEMENT | `Saturn_Velocity_Scrum_Scrumban_Alignment_Improvement_Backlog.md`, existing improvement backlog. |
| WB-s192-TICKET | `Saturn Velocity 2024-2025-2026 (1).xlsx`, sheet `s192`, range `A2:K35`. |
| WB-s192-LEAVE | Workbook, sheet `s192`, range `M2:R12`. |
| WB-s192-META | Workbook, sheet `s192`, range `A36:A40`. |
| WB-s192-SUMMARY | Workbook, sheet `s192`, range `A43:Y44`. |
| WB-s192-RESOURCE | Workbook, sheet `s192`, range `A46:O52`. |
| WB-s192-CALC | Workbook, sheet `s192`, formulas `C44:R44` và `C47:L52`. |
| WB-s189-1 | Workbook, sheet `s189-1stOT`, range `A36:Y53`. |
| WB-s189-2 | Workbook, sheet `s189-FTEOT`, range `A36:Y53`. |
| WB-s175-ERROR | Workbook, sheet `s175`, rendered values `E44:G44`, `E48:F51`, `E57`. |
| WB-s158-SUMMARY | Workbook, sheet `s158`, range `A43:O44`. |
| WB-ALL-TICKET | Workbook, all observed worksheets, range `A3:K35`; no populated ticket record found. |
| PMI-PMBOK8 | [PMBOK Guide — Eighth Edition, Project Management Institute](https://www.pmi.org/standards/pmbok). |
| PMP-ECO-2026 | [Project Management Professional Examination Content Outline — July 2026, PMI](https://www.pmi.org/-/media/pmi/documents/public/pdf/microsites/announcements/pmp-examination-content-outline-2026.pdf?rev=b4ff9dd4bb9e4279ac7f4e326013ea72). |
| PMP-EXAM-UPDATE | [New PMP exam launched in July 2026, PMI](https://www.pmi.org/certifications/project-management-pmp/new-exam). |
| PMI-EVM | [The Standard for Earned Value Management, PMI](https://www.pmi.org/standards/earned-value-management). |
| SG-2020 | [The Scrum Guide, November 2020](https://scrumguides.org/docs/scrumguide/v2020/2020-Scrum-Guide-US.pdf). |
| KG-LATEST | [The Kanban Guide](https://kanbanguides.org/the-kanban-guide/). |
| ST-FILES | [File organization for Community Cloud apps, Streamlit Docs](https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/file-organization). |
| ST-PERSIST | [Static file serving and runtime-file persistence, Streamlit Docs](https://docs.streamlit.io/develop/concepts/configuration/serving-static-files). |
| ST-RESOURCES | [Manage your app: resources and dependency pinning, Streamlit Docs](https://docs.streamlit.io/deploy/streamlit-community-cloud/manage-your-app). |
| ST-SECRETS | [Secrets management for Community Cloud, Streamlit Docs](https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/secrets-management). |
| ST-SECURITY | [Streamlit Community Cloud trust and security](https://docs.streamlit.io/deploy/streamlit-community-cloud/get-started/trust-and-security). |
| CODE-JIRA-UI | `pages/8_Jira_Integration.py` và `pages/9_Jira_Workload_Velocity.py`, Jira synchronization and dashboards. |
| CODE-JIRA-SERVICE | `src/application/jira_services.py`, workload/capacity/velocity business rules. |
| CODE-JIRA-CLIENT | `src/integrations/jira_cloud.py`, read-only Jira Cloud REST adapter. |
| CODE-JIRA-SNAPSHOT | `src/application/jira_snapshot_io.py`, credential-free stateless velocity history bundle. |
| DOC-JIRA | `Jira_Integration.md`, implementation and operations proposal. |

## 20. Jira implementation addendum

NF-10 đã được triển khai cho Jira Cloud ở mức read-only pilot. Adapter giữ Jira issue key/account ID, discover board estimation field và Done mapping, đồng bộ bằng enhanced JQL search, và không có write operation. [Source: CODE-JIRA-CLIENT; CODE-JIRA-SERVICE]

| Improvement | Implemented state | Remaining gate |
| --- | --- | --- |
| Jira authentication | OAuth 2.0 (3LO) và API-token POC; secret/token không nằm trong exported domain state. [Source: CODE-JIRA-UI; CODE-JIRA-SNAPSHOT] | CRITICAL DATA MISSING: approved production authentication mode and OAuth configuration - Not found in context. |
| Workload demand | Remaining/original time seconds; unknown estimate, unassigned issue và unmapped account được tách riêng. [Source: CODE-JIRA-SERVICE] | CRITICAL DATA MISSING: target Jira data completeness and account-to-resource mapping - Not found in context. |
| Capacity reconciliation | Chỉ publish utilization khi Saturn RuleSet đã approved và người dùng bật reconciliation. [Source: CODE-JIRA-SERVICE; CODE-JIRA-UI] | CRITICAL DATA MISSING: business confirmation that Saturn scenario and selected Jira Sprint represent the same planning boundary - Not found in context. |
| Velocity | Start commitment, close completed, board-specific unit/Done mapping, subtask exclusion, scope delta và explicit average window. [Source: CODE-JIRA-SERVICE] | CRITICAL DATA MISSING: approved historical window and exact operating procedure for boundary capture - Not found in context. |
| Stateless history | Board-scoped JSON snapshot download/upload; no credentials or person identifiers in bundle. [Source: CODE-JIRA-SNAPSHOT] | CRITICAL DATA MISSING: shared multi-user persistence, retention and access policy - Not found in context. |
| Verification | Jira-specific golden tests plus repository-wide no-dependency runner. [Source: `tests/golden/test_jira_client.py`; `tests/golden/test_jira_services.py`; `tests/run_all.py`] | Live API verification remains gated by target tenant credentials. |

**[ASSUME]** Missing data: production persistence and identity model. Reasoning: Jira synchronization is implemented as a stateless Streamlit pilot; collaboration across users requires a separately approved external repository, retention policy and access-control model. Confidence score: 100%. [Source: CODE-JIRA-UI; CODE-JIRA-SNAPSHOT]
