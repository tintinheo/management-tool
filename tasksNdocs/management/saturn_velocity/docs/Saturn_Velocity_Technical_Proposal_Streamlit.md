TECHNICAL PROPOSAL

# Saturn Velocity on Streamlit Community Cloud

*Kiến trúc, calculation engine, migration và deployment proposal*

Business source: Saturn Velocity 2024-2025-2026 (1).xlsx

Hosting target: Streamlit Community Cloud [Source: UR-REQ; ST-CC]

Decision status: **[ASSUME]** Missing data: architecture approver chưa được cung cấp. Reasoning: persistence, auth và rule governance còn mở. Proposal for review.

## 1. Recommendation

Recommended MVP: **[ASSUME]** Missing data: retention và concurrency chưa được chốt. Reasoning: Community Cloud không bảo đảm local file persistence. Xây app Streamlit stateless: import XLSX hoặc nhập tay, giữ working data trong session, tính bằng domain engine thuần Python, cho tải snapshot/export; chỉ thêm external database khi business yêu cầu lưu chung nhiều người dùng. [Source: ST-PERSIST; UR-REQ]

Community Cloud hỗ trợ triển khai app trực tiếp từ GitHub và cấp URL streamlit.app; dependencies cần được khai báo trong repository. [Source: ST-DEPLOY; ST-DEPS]

Không nên sao chép nguyên table name hoặc structured-reference name dài từ Excel vào code. **[ASSUME]** Missing data: canonical domain vocabulary chưa được duyệt. Reasoning: table name nguồn thay đổi theo sheet. Đề xuất dùng model ổn định Sprint, Resource, LeaveEvent, Scenario và CalculationSnapshot. [Source: WB-s192-CALC; WB-s158-SUM; WB-s192-SUM]

## 2. Architecture drivers

| Driver | Evidence |
| --- | --- |
| Free deployment | Community Cloud là nền tảng miễn phí để triển khai và chia sẻ Streamlit app. [Source: ST-CC] |
| GitHub delivery | Deploy chọn repository, branch và entrypoint; source repository là nền tảng của app. [Source: ST-DEPLOY; ST-STATUS] |
| Unreliable local persistence | Community Cloud không bảo đảm dữ liệu ghi vào local file tồn tại vĩnh viễn. [Source: ST-PERSIST] |
| Sensitive source data | Workbook chứa tên resource và leave date/leave days. [Source: WB-s192-INPUT] |
| Calculation traceability | Workbook có công thức, schema drift và lỗi hiển thị tại s175. [Source: WB-s192-CALC; WB-s158-SUM; WB-s175-ERR] |
| Scenario support | Hai sheet s189 thể hiện hai phương án OT cho cùng một sprint. [Source: WB-s189-1; WB-s189-2] |

## 3. Target architecture

**[ASSUME]** Missing data: topology production chưa được phê duyệt. Reasoning: yêu cầu chỉ xác định Streamlit Community Cloud. Đề xuất kiến trúc module hóa dưới đây để MVP nhẹ nhưng calculation engine có thể tái sử dụng. [Source: UR-REQ]

```text
Browser
  -> Streamlit UI / Session Controller
      -> Application Services
          -> Validation + Calculation Engine
          -> Excel Import/Export Adapter
          -> Snapshot Repository
              -> Session/Download (MVP)
              -> External SQL Store (optional)
```

| Layer | Responsibility | Implementation proposal |
| --- | --- | --- |
| Presentation | Form, editable grids, KPI, warnings, downloads | **[ASSUME]** Missing data: UI design. Reasoning: Streamlit là hosting target. Dùng Streamlit multipage UI. [Source: UR-REQ; ST-CC] |
| Application | Orchestrate import, calculate, compare, approve, export | **[ASSUME]** Missing data: workflow API. Reasoning: tách use-case khỏi UI để test. Python service functions. [Source: WB-s189-1; WB-s189-2] |
| Domain | Typed entities, rules, validation | **[ASSUME]** Missing data: canonical model. Reasoning: schema sheet thay đổi. Dataclass/Pydantic-style models. [Source: WB-s158-SUM; WB-s192-SUM] |
| Calculation | Pure functions, no UI state, versioned rules | **[ASSUME]** Missing data: approved formula set. Reasoning: cần golden tests và audit. [Source: WB-s192-CALC; WB-s175-ERR] |
| Adapters | XLSX import/export và persistence | **[ASSUME]** Missing data: migration scope. Reasoning: cần đọc nhiều schema và tránh local persistence. [Source: WB-INDEX; ST-PERSIST] |

## 4. Proposed repository structure

**[ASSUME]** Missing data: coding standard/repository hiện hữu không được cung cấp. Reasoning: Community Cloud chạy từ repository root và cần dependency declaration. Đề xuất cấu trúc sau. [Source: ST-DEPS; ST-STATUS]

```text
streamlit_app.py
pages/
src/domain/
src/application/
src/calculation/
src/importers/
src/exporters/
src/storage/
tests/golden/
.streamlit/config.toml
requirements.txt
README.md
```

Community Cloud tìm dependency file tại thư mục entrypoint rồi repository root; tài liệu chính thức khuyến nghị khai báo dependencies và pin Streamlit version để tránh auto-upgrade ngoài dự kiến. [Source: ST-DEPS]

## 5. Application pages

| Page | Purpose |
| --- | --- |
| Home / Import | **[ASSUME]** Missing data: landing page chưa được mô tả. Reasoning: cần bắt đầu từ workbook hoặc blank sprint. Upload XLSX, chọn sheet(s), xem import report. [Source: WB-INDEX] |
| Sprint setup | **[ASSUME]** Missing data: field ownership. Reasoning: metadata hiện ở A36:A40. Form cho dates, holiday, buffer, backup và named constant. [Source: WB-s192-META; WB-s192-CALC] |
| Resources & Leave | **[ASSUME]** Missing data: master-data source. Reasoning: resource/leave là driver. Editable grid với validation type-safe. [Source: WB-s192-INPUT; WB-s192-RES] |
| Tickets | **[ASSUME]** Missing data: ticket process. Reasoning: chỉ có schema. Optional grid/import/export, chưa nối engine. [Source: WB-ALL-TICKET] |
| Scenarios | **[ASSUME]** Missing data: scenario naming/governance. Reasoning: s189 có hai phương án. Clone, override và compare. [Source: WB-s189-1; WB-s189-2] |
| Results | **[ASSUME]** Missing data: KPI priority. Reasoning: summary có Dev/QC/Biz và gap. KPI cards, resource breakdown, formula trace. [Source: WB-s192-SUM; WB-s192-CALC] |
| Validation & Export | **[ASSUME]** Missing data: severity/export schema. Reasoning: s175 có lỗi và Excel là source format. Warning list, acknowledgement, snapshot và download. [Source: WB-s175-ERR; WB-INDEX] |

## 6. Domain model

| Model | Fields | Source / assumption |
| --- | --- | --- |
| Sprint | sprint_id, name, start_date, end_date, development_end_date, public_holidays, buffer, backup, fixed_day_deduction | WB-s192-META; WB-s192-CALC |
| Resource | resource_id, display_name, default_velocity, default_type | WB-s192-RES |
| ScenarioResource | scenario_id, resource_id, velocity, leave_days, ot_hours, ot_days, v_percent, others, type | WB-s192-RES |
| LeaveEvent | resource_id, date, days, status, note | WB-s192-INPUT |
| Ticket | fields matching A2:K2; no calculation binding by default | WB-s192-INPUT; WB-ALL-TICKET |
| Scenario | scenario_id, sprint_id, name, overrides, base_scenario_id | WB-s189-1; WB-s189-2 |
| RuleSet | rule_version, hours_per_day, fixed_day_deduction, formulas, effective_status | **[ASSUME]** Missing data: rule governance. Reasoning: constants/OT policy chưa rõ. [Source: WB-s192-CALC; WB-s189-1] |
| Snapshot | input_payload, output_payload, warnings, rule_version, source_locator | **[ASSUME]** Missing data: audit schema. Reasoning: cần replay và reconciliation. [Source: WB-s175-ERR; WB-s192-SUM] |

## 7. Calculation engine design

**[ASSUME]** Missing data: authoritative formulas chưa được phê duyệt. Reasoning: dùng s192 làm baseline vì đây là schema mới nhất trong workbook, nhưng rule phải versioned và test bằng golden cases. [Source: WB-s192-META; WB-s192-CALC; WB-INDEX]

| Output | Canonical expression | Evidence / normalization |
| --- | --- | --- |
| dev_days | networkdays(start_date, end_date, holidays) - fixed_day_deduction - backup | s192 C44 trừ literal 3 và Back up. [Source: WB-s192-CALC] |
| remaining_dev_days | networkdays(as_of_date, end_date) | Thay TODAY() bằng as_of_date explicit để snapshot deterministic. Baseline dùng TODAY(). [Source: WB-s192-CALC] |
| leave_days | sum(leave_event.days where resource matches scenario) | Baseline SUMIFS theo Resource. [Source: WB-s192-CALC] |
| fte_no_ot | velocity_per_day × (dev_days - leave_days) × v_percent | Baseline s192 K47:K52. [Source: WB-s192-CALC] |
| full_v | velocity_per_day × (dev_days - leave_days + ot_days) × v_percent | Baseline s192 H47:H52. [Source: WB-s192-CALC] |
| v | fte_no_ot × (1 - buffer) | Baseline s192 F47:F52. [Source: WB-s192-CALC] |
| v_ot | full_v × (1 - buffer) | Baseline s192 G47:G52. [Source: WB-s192-CALC] |
| team_dev/team_qc | sum per resource where type is Dev/QC | Baseline SUMIFS ở E44:H44. [Source: WB-s192-CALC] |
| team_velocity_biz | min(dev_v, qc_v) | Baseline s192 K44. [Source: WB-s192-CALC] |
| qc_minus_dev | qc_v - dev_v | Baseline s192 P44. [Source: WB-s192-CALC] |
| buffer_days | buffer × dev_days | Baseline s192 R44. [Source: WB-s192-CALC] |

Unresolved formula inputs: CRITICAL DATA MISSING: ý nghĩa fixed_day_deduction = 3, quy tắc hours_per_day, và định nghĩa EzA/SC/SE/Regression - Not found in context. [Source: WB-s192-CALC; WB-s192-SUM; WB-s189-1]

## 8. Excel import and migration strategy

1. **[ASSUME]** Missing data: canonical import scope. Reasoning: các sheet cùng pattern nhưng schema thay đổi. Detect workbook/sheet, không assume một fixed used range. [Source: WB-INDEX; WB-s158-SUM; WB-s192-SUM]

1. **[ASSUME]** Missing data: version tags không tồn tại. Reasoning: header là bằng chứng tốt nhất. Detect header signatures tại ticket A2:K2, metadata A36:A40, summary row 43 và resource row 46. [Source: WB-s192-INPUT; WB-s192-META; WB-s192-SUM; WB-s192-RES]

1. **[ASSUME]** Missing data: alias mapping chưa được duyệt. Reasoning: Resource Dev/Resource QC cũ và Resource + Type mới biểu diễn cùng khái niệm. Normalize vào ScenarioResource. [Source: WB-s158-SUM; WB-s192-RES]

1. **[ASSUME]** Missing data: duplicate policy. Reasoning: s189 có hai sheet cùng sprint. Preserve thành hai scenario. [Source: WB-s189-1; WB-s189-2]

1. **[ASSUME]** Missing data: error policy. Reasoning: s175 có cached error. Import raw inputs và source outputs, nhưng đánh dấu source_output_invalid. [Source: WB-s175-ERR]

1. **[ASSUME]** Missing data: ticket import behavior. Reasoning: không có row mẫu. Chỉ map header và record khi người dùng cung cấp file có dữ liệu. [Source: WB-ALL-TICKET]

## 9. Validation and error handling

| Control | Proposal |
| --- | --- |
| Type validation | **[ASSUME]** Missing data: data dictionary chưa có. Reasoning: Leave date đang trộn date/TBD. Dùng typed fields và status riêng. [Source: WB-s192-INPUT] |
| Rule validation | **[ASSUME]** Missing data: allowed ranges chưa được cung cấp. Reasoning: input là capacity drivers. Chỉ áp dụng range constraints sau business sign-off; trước đó cảnh báo dữ liệu thiếu/không numeric. [Source: WB-s192-META; WB-s192-RES] |
| Calculation error | **[ASSUME]** Missing data: severity matrix. Reasoning: s175 có #VALUE!. Exception hoặc non-finite output trở thành blocking error, không render như KPI. [Source: WB-s175-ERR] |
| Reconciliation | **[ASSUME]** Missing data: tolerance. Reasoning: cần đối chiếu workbook. Hiển thị source value, calculated value, delta và rule version. [Source: WB-s192-SUM; WB-s192-CALC] |
| Import diagnostics | **[ASSUME]** Missing data: fail-fast policy. Reasoning: schema drift. Report sheet, detected schema, mapped fields, ignored fields và errors. [Source: WB-s158-SUM; WB-s192-SUM] |

## 10. Persistence options

| Option | When to use | Design and constraint |
| --- | --- | --- |
| A. Stateless MVP | **[ASSUME]** Missing data: shared history not confirmed. Reasoning: satisfy free deploy with minimum infrastructure. [Source: UR-REQ; ST-CC] | Session state + user download/upload snapshot. Không phụ thuộc local disk. [Source: ST-PERSIST] |
| B. External SQL | **[ASSUME]** Missing data: retention/concurrency/security. Reasoning: cần khi nhiều người cùng dùng hoặc giữ history. [Source: ST-PERSIST] | Repository interface trỏ tới remote SQL; credentials qua secrets. [Source: ST-SECRETS] |

Decision: CRITICAL DATA MISSING: lựa chọn stateless hay persistent multi-user - Not found in context. Không dùng SQLite/local JSON như production database trên Community Cloud vì local file persistence không được bảo đảm. [Source: ST-PERSIST]

## 11. Security and privacy

- Workbook chứa tên resource và leave information. [Source: WB-s192-INPUT]

- Community Cloud cho phép cấu hình app public hoặc private; private viewer có cơ chế sign-in theo nền tảng. [Source: ST-SHARE]

- Streamlit yêu cầu administrator của GitHub repository để deploy app và có cơ chế quyền gắn với repository. [Source: ST-STATUS; ST-SECURITY]

- Secrets không nên commit vào repository; Community Cloud có secrets field trong app settings. [Source: ST-SECRETS]

- Streamlit OIDC cung cấp authentication nhưng không cung cấp authorization; role enforcement phải do app thực hiện. [Source: ST-AUTH]

- **[ASSUME]** Missing data: data classification. Reasoning: dữ liệu nhân sự có trong workbook. Production app mặc định private cho đến khi có phê duyệt public. [Source: WB-s192-INPUT; ST-SHARE]

- **[ASSUME]** Missing data: identity provider và role matrix. Reasoning: authentication/authorization là hai lớp khác nhau. MVP private-sharing dùng platform access; app-level roles chỉ triển khai khi có matrix. [Source: ST-AUTH; ST-SHARE]

- **[ASSUME]** Missing data: demo policy. Reasoning: app public có thể lộ dữ liệu. Demo public chỉ dùng dữ liệu tổng hợp hoặc giả lập. [Source: WB-s192-INPUT; ST-SHARE]

## 12. Community Cloud deployment

1. Chuẩn bị GitHub repository với entrypoint và dependency file. [Source: ST-DEPS; ST-STATUS]

1. Kết nối Streamlit Community Cloud với GitHub và tạo app bằng repository, branch, file path. [Source: ST-DEPLOY]

1. Cấu hình secrets trong Advanced settings nếu dùng external database hoặc OIDC. [Source: ST-DEPLOY; ST-SECRETS; ST-AUTH]

1. Theo dõi build log và mở URL streamlit.app sau khi deploy. [Source: ST-DEPLOY]

1. Đặt sharing mode phù hợp với data classification. [Source: ST-SHARE; ST-SECURITY]

**[ASSUME]** Missing data: version matrix chưa được cung cấp. Reasoning: Community Cloud chỉ hỗ trợ Python versions còn nhận security updates và dependencies có thể auto-upgrade nếu không pin. Đề xuất pin Streamlit và thư viện sau khi test, đồng thời ghi Python version trong deployment settings. [Source: ST-DEPLOY; ST-DEPS; ST-STATUS]

## 13. Test strategy

| Test group | Case |
| --- | --- |
| Golden latest | Import s192, tính lại summary/resource outputs và đối chiếu với A43:Y52 sau khi business xác nhận rule. [Source: WB-s192-SUM; WB-s192-CALC] |
| Legacy adapter | Import s158 và map schema cũ không làm mất field nguồn. [Source: WB-s158-META; WB-s158-SUM] |
| Scenario comparison | Import hai sheet s189 thành hai scenario và giữ khác biệt OT/output. [Source: WB-s189-1; WB-s189-2] |
| Error handling | s175 phải tạo blocking diagnostic cho vùng lỗi, không coi #VALUE! là KPI hợp lệ. [Source: WB-s175-ERR] |
| Volatile date | remaining_dev_days dùng as_of_date explicit; cùng snapshot phải replay ra cùng kết quả. Baseline Excel dùng TODAY(). [Source: WB-s192-CALC] |
| Ticket isolation | Ticket grid không ảnh hưởng capacity khi chưa có approved binding rule. [Source: WB-ALL-TICKET] |
| Deployment smoke | App khởi động từ repository root và cài đúng dependency declaration. [Source: ST-DEPS; ST-STATUS] |

Tolerance: CRITICAL DATA MISSING: numeric reconciliation tolerance và rounding policy - Not found in context. Không đặt epsilon mặc định nếu chưa có sign-off. [Source: WB-s192-SUM; WB-s175-ERR]

## 14. Observability and audit

- **[ASSUME]** Missing data: logging policy. Reasoning: import/schema errors cần điều tra. Log technical event không chứa raw personal data. [Source: WB-s192-INPUT; ST-SECURITY]

- **[ASSUME]** Missing data: audit retention. Reasoning: snapshot cần replay. Lưu source locator, rule version, as_of_date, inputs, outputs và warnings trong downloadable snapshot hoặc external DB. [Source: WB-s192-CALC; ST-PERSIST]

- **[ASSUME]** Missing data: production support model. Reasoning: Community Cloud cung cấp logs cho người có write access vào repository. Ghi troubleshooting hướng đến developer có quyền phù hợp. [Source: ST-DEPLOY]

## 15. Delivery phases

| Phase | Exit outcome |
| --- | --- |
| Rule validation prototype | **[ASSUME]** Missing data: approved rule owner. Reasoning: phải chốt constant, OT conversion và golden cases trước khi xây đầy đủ. Deliver calculation engine + reconciliation report. [Source: WB-s192-CALC; WB-s175-ERR] |
| Stateless MVP | **[ASSUME]** Missing data: persistence chưa quyết định. Reasoning: phù hợp Community Cloud và tránh local storage. Deliver UI, import, scenario, validation, dashboard, export. [Source: ST-PERSIST; UR-REQ] |
| Controlled pilot | **[ASSUME]** Missing data: pilot users và data policy. Reasoning: workbook chứa personal data. Deploy private, dùng approved dataset, collect defects. [Source: WB-s192-INPUT; ST-SHARE] |
| Persistent / integrated release | **[ASSUME]** Missing data: multi-user retention và external systems. Reasoning: chỉ thực hiện nếu được xác nhận. Add remote database, auth roles hoặc ticket integration. [Source: ST-PERSIST; ST-AUTH; WB-ALL-TICKET] |

## 16. Technical risk register

| ID | Risk | Confidence / evidence | Mitigation |
| --- | --- | --- | --- |
| TR-01 | Incorrect rule replication | **[ASSUME]** Confidence score: 100%. Missing data: approved formula set. Reasoning: s175 error và literal 3. [Source: WB-s175-ERR; WB-s192-CALC] | Golden tests + rule version + business sign-off. |
| TR-02 | Schema-dependent importer | **[ASSUME]** Confidence score: 100%. Missing data: canonical schema. Reasoning: s158/s192 khác nhau. [Source: WB-s158-SUM; WB-s192-SUM] | Header detection + adapters + import report. |
| TR-03 | Lost data on restart | **[ASSUME]** Confidence score: 100%. Missing data: persistence requirement. Reasoning: local storage không được guarantee. [Source: ST-PERSIST] | Stateless download hoặc external DB. |
| TR-04 | Public exposure | **[ASSUME]** Confidence score: 100%. Missing data: data classification. Reasoning: personal leave data và public sharing option. [Source: WB-s192-INPUT; ST-SHARE] | Private app; sanitized public demo. |
| TR-05 | Dependency drift | **[ASSUME]** Confidence score: 95%. Missing data: approved versions. Reasoning: docs cảnh báo auto-upgrade nếu không pin. [Source: ST-DEPS] | Pin versions; smoke test before update. |
| TR-06 | Authorization gap | **[ASSUME]** Confidence score: 100%. Missing data: role matrix. Reasoning: OIDC không tự cung cấp authorization. [Source: ST-AUTH] | Platform private access hoặc app-level RBAC. |

## 17. Architecture decision requests

CRITICAL DATA MISSING: Stateless download-only hay persistent multi-user database - Not found in context. [Source: ST-PERSIST]

CRITICAL DATA MISSING: App public, private-sharing hay OIDC - Not found in context. [Source: ST-SHARE; ST-AUTH]

CRITICAL DATA MISSING: Canonical rule set và owner phê duyệt - Not found in context. [Source: WB-s192-CALC; WB-s175-ERR]

CRITICAL DATA MISSING: Historical sheets nào phải migrate - Not found in context. [Source: WB-INDEX]

CRITICAL DATA MISSING: Ticket module chỉ nhập/xuất hay tích hợp hệ thống nguồn - Not found in context. [Source: WB-ALL-TICKET]

## Source register

Mọi citation dạng [Source: mã] trong tài liệu được giải nghĩa ở bảng dưới đây. Với workbook, locator là sheet và vùng ô; với Streamlit, locator là trang tài liệu chính thức.

| Mã | Nguồn | Locator / phạm vi |
| --- | --- | --- |
| UR-REQ | Yêu cầu người dùng | Phân tích workbook; viết BRD và technical proposal cho web application có thể deploy miễn phí trên streamlit.io. |
| WB-INDEX | Saturn Velocity 2024-2025-2026 (1).xlsx | Worksheet index: s158 đến s192, gồm các biến thể s189-1stOT và s189-FTEOT. |
| WB-s158-META | Workbook, sheet s158 | A36:A40 - cửa sổ sprint/dev, public holiday, buffer, backup. |
| WB-s158-SUM | Workbook, sheet s158 | A43:O44 - schema summary/capacity giai đoạn đầu. |
| WB-s192-INPUT | Workbook, sheet s192 | A2:R12 - header ticket và dữ liệu resource/leave/public holiday. |
| WB-s192 | Workbook, sheet s192 | A2:Y70 - toàn bộ vùng dữ liệu, công thức và summary của sheet. |
| WB-s192-META | Workbook, sheet s192 | A36:A40 - Start/End, Development Days, Public Holiday, Buffer, Backup. |
| WB-s192-SUM | Workbook, sheet s192 | A43:Y44 - schema và kết quả summary/capacity mới nhất trong workbook. |
| WB-s192-CALC | Workbook, sheet s192 | C44:R44 và C47:L52 - công thức sprint và công thức capacity theo resource. |
| WB-s192-RES | Workbook, sheet s192 | A46:O52 - resource, velocity/day, leave, OT, V, FTE, V%, Others, Type. |
| WB-s189-1 | Workbook, sheet s189-1stOT | A36:Y53 - cùng sprint s189 với một bộ OT và kết quả capacity. |
| WB-s189-2 | Workbook, sheet s189-FTEOT | A36:Y53 - cùng sprint s189 với bộ OT khác và kết quả capacity khác. |
| WB-s175-ERR | Workbook, sheet s175 | Các giá trị hiển thị tại E44:G44, E48:F51 và E57 là #VALUE! trong bản render nguồn. |
| WB-ALL-TICKET | Workbook, tất cả worksheet | A3:K35 - không có dòng ticket được điền; chỉ có header tại A2:K2. |
| ST-CC | Streamlit Docs - Community Cloud | https://docs.streamlit.io/deploy/streamlit-community-cloud |
| ST-DEPLOY | Streamlit Docs - Deploy your app | https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/deploy |
| ST-DEPS | Streamlit Docs - App dependencies | https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/app-dependencies |
| ST-PERSIST | Streamlit Docs - Connecting to data | https://docs.streamlit.io/develop/concepts/connections/connecting-to-data |
| ST-SECRETS | Streamlit Docs - Community Cloud secrets | https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/secrets-management |
| ST-SHARE | Streamlit Docs - Share your app | https://docs.streamlit.io/deploy/streamlit-community-cloud/share-your-app |
| ST-AUTH | Streamlit Docs - Authentication | https://docs.streamlit.io/develop/concepts/connections/authentication |
| ST-STATUS | Streamlit Docs - Status and limitations | https://docs.streamlit.io/deploy/streamlit-community-cloud/status |
| ST-SECURITY | Streamlit Docs - Trust and security | https://docs.streamlit.io/deploy/streamlit-community-cloud/get-started/trust-and-security |
