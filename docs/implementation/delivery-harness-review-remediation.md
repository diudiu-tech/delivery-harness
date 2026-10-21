# PR #35 审查整改索引

核对日期：2026-10-21。本文把原审查意见映射到重新拆分的实现、测试与剩余验收事项，方便按主题复核。原 [PR #35](https://github.com/diudiu-tech/delivery-harness/pull/35) 已标记不可合并。2026-10-21 按用户授权仅重写了它的三个提交身份，源码不变；旧 SHA、缓存或其他克隆仍可能保留原身份。替代交付通过下列拆分 PR 按依赖集成 main，未部署应用。

本索引描述源码与回归证据，不替代产品签字或真实环境验收。构建和安全结果随提交变化，必须查看对应 PR 当前检查，不能沿用旧 SHA 的绿灯。

## 1. 提交方式与交付范围

| 原意见 | 整改与复核入口 | 边界 |
| --- | --- | --- |
| PRD 与实现、资金执行混在一起 | [#36 PRD](https://github.com/diudiu-tech/delivery-harness/pull/36) 单独提交；[实现映射](delivery-harness-prd-implementation.md) 明确 FR-14 与实际留存删除不交付 | PRD 仍是联合评审提案；资金与删除须在 M2 验收后另立项目、ADR 和验收方案 |
| 分支命名、超大提交、无原因正文、错误 docs 类型 | 新分支使用 `<type>/<subject>`；按下列主题拆分，提交使用 Conventional Commits 和换行原因正文；文档与运行时代码分开 | 拆分 PR 有依赖，应按顺序审查；原 #35 另行仅重写身份信息，未改变源码 |
| 破坏性行为未标记 | 可信决定、身份边界和案件接口的相关提交/PR 标记兼容性变化；见 [v2 API](../api/v2.md) | v1 降级、服务端文案、pilot v1 禁用、评分、POST 导出与严格类型需要调用方迁移 |
| CI 夹带在业务改动中 | [#38](https://github.com/diudiu-tech/delivery-harness/pull/38) 单独处理功能分支安全检查触发；[#60](https://github.com/diudiu-tech/delivery-harness/pull/60) 仅改 CI 验证与产物上传 | CODEOWNERS 审查要求保留；自动检查不替代所有者审查 |
| PR 模板缺风险、安全和清单 | 拆分 PR 使用仓库模板，分别回答金额、审批阈值、guardrail、prompt、日志与数据影响 | 不把工作流 analyze 成功等同于实际 CodeQL 安全检查通过 |
| seed 纯格式改动混杂 | 保留数组与小数表示，规则种子只保留语义修订 | 种子仍属于 CODEOWNERS 范围 |
| 公开提交使用公司邮箱 | 拆分源码提交使用仓库本地 noreply 身份；原 #35 当前三个提交的作者和提交者也已改为 noreply | GitHub 合并身份使用 main 历史中已有的个人邮箱；旧 SHA、缓存和克隆仍可能保留旧身份，不能承诺完全删除 |

## 2. 安全、正确性与可维护性

下列测试名可在仓库中检索。完整验证命令与报告路径见 [CONTRIBUTING](../../CONTRIBUTING.md)。

| 原意见 | 实现与 PR | 回归证据或复核点 |
| --- | --- | --- |
| GET 播种、GET 导出写审计、demo 匿名全角色及 CSRF/DNS rebinding | [#42 身份](https://github.com/diudiu-tech/delivery-harness/pull/42)、[#44 案件](https://github.com/diudiu-tech/delivery-harness/pull/44)、[#47 政策](https://github.com/diudiu-tech/delivery-harness/pull/47)、[#50 评测](https://github.com/diudiu-tech/delivery-harness/pull/50)：demo 启动播种、POST 导出、显式 bearer、Host/Origin 校验、无 cookie/session 登录 | `DemoIdentityApiTest`、`PlatformSecurityFilterTest`、`ExportControllerTest`；GET 导出返回 405，匿名 API 返回 401 |
| 反馈授权受用户分支影响；后续回归报告可能越范围 | [#51 反馈](https://github.com/diudiu-tech/delivery-harness/pull/51)：案件范围每次授权，关联回归报告时同时核验完整运行知识范围与样本来源 | `QualityServiceTest` 的越范围拒绝、状态不推进及合法核验人正向对照；原 CodeQL #43 已修复并重新扫描，未作抑制 |
| 版本加法溢出、SQL 异常吞掉、审计依赖可选、迁移放错模块 | [#41 存储](https://github.com/diudiu-tech/delivery-harness/pull/41)：`Math.addExact`、事务内 CAS 与审计、强制依赖、安全 SQL 状态日志、core 类路径 Flyway | `PlatformStoreTest`、`IndexedPlatformStoreTest`；真实迁移执行，V1–V9，不按分号手拆 SQL |
| 普通决定发起人可审批自己 | [#44 案件](https://github.com/diudiu-tech/delivery-harness/pull/44)：同时拒绝案件与决定发起人的领取、批准及不合法转派；历史作者不明时要求重新分析 | `CaseServiceTest`、`PlatformAcceptanceApiTest`；包括兼任主管与独立审核人对照 |
| 全租户扫描、附件正文进入列表、审计内存分页 | [#41](https://github.com/diudiu-tech/delivery-harness/pull/41)、[#46 摘要投影](https://github.com/diudiu-tech/delivery-harness/pull/46)、[#52 运营](https://github.com/diudiu-tech/delivery-harness/pull/52)：SQL 范围过滤和有界查询、正文独立、持久 `summary_payload` | `IndexedPlatformStoreTest`、`OperationsServiceTest`：损坏完整 payload 后摘要和运营读取仍成功，验证未先加载再删字段 |
| 字符串时间顺序错误、同时间戳翻页漏行 | [#41](https://github.com/diudiu-tech/delivery-harness/pull/41)：数据库 TIMESTAMP，游标为 `(created_at, event_id)` | `IndexedPlatformStoreTest`：时间精度与相同时间戳边界；游标不只比较时间 |
| 审批阈值两处来源、英文 REFUND 绕过余额约束 | [#39 可信决定](https://github.com/diudiu-tech/delivery-harness/pull/39)、[#47](https://github.com/diudiu-tech/delivery-harness/pull/47)：统一审批属性和 `CompensationMethod`，政策输入验证，退款依赖已核验余额 | `CompensationRuleToolkitTest`、政策 API 回归；未知余额不当作零或订单金额 |
| v2 规则缺绑定静默回退、knowledge 与 platform 循环依赖 | [#39](https://github.com/diudiu-tech/delivery-harness/pull/39)、[#49 配置](https://github.com/diudiu-tech/delivery-harness/pull/49)：冻结运行接口隔离、可信入口缺绑定失败；范围判断由纯运行时对象承担 | `RuleBaseServiceTest`、`RuntimeConfigurationTest`；pilot 缺少已发布配置时拒绝在线执行 |
| 可配置 prompt 升为 system、代码约束降为 user | [#40 模型边界](https://github.com/diudiu-tech/delivery-harness/pull/40)：代码约束保持 system，配置任务说明作为低信任输入 | `LlmGatewayTest`；检查实际消息顺序、角色和可信约束 |
| 心跳、超时、恢复共用线程，恢复扫描饥饿 | [#45 队列](https://github.com/diudiu-tech/delivery-harness/pull/45)：独立调度器、租约/fence、完成与续租锁定同一行、恢复分页推进 | `ExecutionServiceTest`、`ExecutionRecoveryTest`：交错续租/完成、超过千租户、整页未过期任务后的恢复 |
| pilot 静态明文 token、演示凭证可用于 pilot | [#42](https://github.com/diudiu-tech/delivery-harness/pull/42)：pilot 使用 RS256 OIDC/JWT，验证 issuer、audience、时间与可信身份 claims，拒绝静态演示身份 | `OidcSecurityApiTest`、`DisabledDemoIdentityApiTest`；真实 IdP 联调与撤权仍须验收 |
| 每次 401 都无限写审计 | [#42](https://github.com/diudiu-tech/delivery-harness/pull/42)：未认证失败采用有界进程内节流；已认证授权拒绝按边界审计 | `PlatformSecurityFilterTest`；不把匿名网络请求当作可无限增长的业务记录 |
| 先读完响应再检查大小、慢速正文无限等待 | [#40](https://github.com/diudiu-tech/delivery-harness/pull/40)、[#43 只读源](https://github.com/diudiu-tech/delivery-harness/pull/43)：接收时执行大小限制，截止时间覆盖响应正文与重试 | `BoundedLlmClientTest`、`OrderSourceTest` 的超大响应与慢速正文夹具；资金渠道不在本轮范围 |
| v2 任意 Map 输入，无类型/长度边界 | [#44](https://github.com/diudiu-tech/delivery-harness/pull/44) 至 [#53 例外提议](https://github.com/diudiu-tech/delivery-harness/pull/53)：类型化请求、字符串/集合边界、严格 JSON 数值 | `CaseRequestValidationTest`、`QualityAcceptanceApiTest`、`PlatformAcceptanceApiTest`；例外批准仍为 NOT_EXECUTED |
| 时间轴精度、未知值、引用与 guardrail 不可信 | [#39](https://github.com/diudiu-tech/delivery-harness/pull/39)：秒级权威事实、UNKNOWN/INVALID、引用字段/值/单位验证、服务端客户说明 | `OrderTimelineTest`、`GuardrailCheckerTest`、`OutputFormatterTest`；保留 ADR-0002 设计说明 |
| Java 与前端难以审查、缺格式门禁 | [#37](https://github.com/diudiu-tech/delivery-harness/pull/37)、[#57 工作台](https://github.com/diudiu-tech/delivery-harness/pull/57)：Java 120 字符门禁、可读源码、前端状态回归 | `tools/check-format.sh`、`tools/check-workbench-state.cjs`、`WorkbenchApiTest`；token 仅在内存，页面文本使用 `textContent` |
| README/覆盖率说法不一致、ADR 索引格式错误、手写 OpenAPI 漏路由 | [#56 API](https://github.com/diudiu-tech/delivery-harness/pull/56)、[#58 覆盖率](https://github.com/diudiu-tech/delivery-harness/pull/58)、[#59 文档](https://github.com/diudiu-tech/delivery-harness/pull/59)：实际 MVC/Jackson 生成契约、统一 core 门槛 0.70、ADR 0005–0010 单独主题与索引 | `GeneratedOpenApiTest` 包含知识搜索别名；`target/generated-api/openapi.v2.json` 为构建产物，CI 上传失败会报错；聚合覆盖率与 core 门槛分开解释 |

后续复审另发现评测结果分页遗漏样本编号、执行状态和评分。该项由 [PR #61](https://github.com/diudiu-tech/delivery-harness/pull/61) 补充显式评分投影、V10 历史回填及 API/工作台回归；不读取完整证据和执行正文，缺少标签仍保持 NOT_MEASURED。具体交付状态以该 PR 为准。

## 3. 审查顺序与剩余验收

依赖顺序：**#36 → #37 → #38 → #39 → #40 → #41 → #42 → #43 → #44 → #45 → #46 → #47 → #48 → #49 → #50 → #51 → #52 → #53 → #56 → #57 → #58 → #59 → #60 → #61**。其中 [#48](https://github.com/diudiu-tech/delivery-harness/pull/48) 为知识版本与独立发布，[#50](https://github.com/diudiu-tech/delivery-harness/pull/50) 为冻结配置、数据集和精确字段评测。#54/#55 不属于这组交付。

2026-10-21 起按用户授权依次集成 main，具体合并状态和最新检查见对应 PR。前一项合入 main 后，下一项改为面向 main、追加同步提交并重新检查，使用 squash 保持 main 的线性历史；未绕过保护规则。CODEOWNERS 审查和产品联合评审分别进行，代码合入不替代真实环境验收。

自动测试、本地合成浏览器闭环和 CodeQL 均不能代替真实 IdP、PostgreSQL、订单源、模型供应商、组织分权、容量与故障、备份恢复和业务效果验收。执行这些验收前，按[试点运行手册](../operations/pilot-runbook.md)准备环境与证据；缺少真实系统或凭证时标为未验证，不能补写为通过。
