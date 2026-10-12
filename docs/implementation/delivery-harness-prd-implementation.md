# Delivery Harness PRD 实施与验收映射

本文对应[产品 PRD](../prd/delivery-harness-product-prd.md)、[v2 API](../api/v2.md)和[试点运行手册](../operations/pilot-runbook.md)。审阅对象为 `codex/implement-product-prd` 分支的当前实现，整理日期为 2026-10-12。

本轮已经形成可运行的演示工作台、可信工作流内核、持久业务记录和可测试的外部适配器契约。**这不等于 PRD 已完成全部生产验收。**真实组织身份、经营政策、订单服务、模型供应商、PostgreSQL、资金渠道、业务效果和生产运维仍需要相应环境与责任人提供证据。

PRD 中的“现状”保留原始分析基线；本文件记录后续实现。PRD 的业务目标、容量预算、试点规模和里程碑估算不会因代码完成而自动成为已达成成绩。

## 1. 验收口径与本轮证据

| 证据层次 | 本轮能证明什么 | 不能据此推导什么 |
| --- | --- | --- |
| 代码与本地回归 | 状态、公式、授权、不可变版本、事务、幂等和异常分支按测试预期执行 | 真实订单数据质量、真实模型准确率、业务节省时间 |
| 演示闭环 | 合成订单通过 v2 案件、分析、补证、审核、版本管理、评测和运营页面完成操作 | 演示规则获得经营或财务授权 |
| Adapter 契约 | 本地 HTTP 响应、错误、字段漂移、白名单和回调签名可按契约处理 | 已接入某个真实订单系统或支付供应商 |
| 存储回归 | H2 内存及文件库上的 Migration、事务回滚、CAS 和重新打开数据库已验证 | 真实 PostgreSQL 版本上的锁、并发、容灾、备份和恢复已验证 |
| 质量门禁 | 候选配置实际绑定、字段与规则精确评分、缺失标签不当作满分、发布需匹配通过报告 | 合成样本代表真实业务分布或证明模型具有净收益 |

2026-10-12 的最终 `./mvnw --batch-mode --no-transfer-progress clean verify` 已通过：**243 项测试，0 failures / 0 errors / 0 skipped**（common 6、core 183、API 54），成功打包可运行 JAR。core 行覆盖率 **75.15%**（3357/4467），跨模块行覆盖率 **84.32%**（4107/4871）；核心覆盖率门槛从 0.30 提高到 **0.70**。新增资金清理/额度并发、留存父记录并发以及个人信息发布/Prompt/旧快照拦截均在此批次中。报告位于 `harness-core/target/site/jacoco/`、`harness-api/target/site/jacoco-aggregate/` 与各模块 `target/surefire-reports/`。后续修改应重新验证，历史成绩不能替代新代码的验收。

本文件编写阶段不执行资金请求、不连接真实业务源，也不补做生产负载或 PostgreSQL 演练。代码与测试映射表示有对应实现或本地证据；下文标注的外部门禁仍然有效。

## 2. 运行方式、模块与默认边界

### 2.1 默认配置

| 配置 | 当前默认值与行为 |
| --- | --- |
| 模式 | `HARNESS_MODE=demo`；pilot 显式启用，test 用于测试环境 |
| 监听地址与端口 | `127.0.0.1:8080`，可通过 `SERVER_ADDRESS`、`SERVER_PORT` 修改 |
| 中文工作台 | `http://127.0.0.1:8080/workbench/` |
| 数据库 | `jdbc:h2:mem:harness;DB_CLOSE_DELAY=-1`；进程结束后数据清空 |
| 默认 v2 运行配置 | `runtime-default-v1`，`deterministic_only=true`，不需要模型服务 |
| 全局模型关闭开关 | `HARNESS_DETERMINISTIC_ONLY=false` 为配置默认值；工作流同时读取全局开关和冻结运行配置，因此默认 v2 工作台仍使用确定性模式 |
| 在线执行资源 | 默认 2 个 worker、50 个内存排队位置、15 秒任务 deadline；另有持久 admission、租户配额和公平调度 |
| 评测执行资源 | 独立线程池与有界队列，不占用在线工作流线程池；这不是生产容量承诺 |
| 审批阈值与金额上限 | 默认人工高风险阈值 10.00，赔付上限 50.00；二者含义不同 |
| 资金执行 | `HARNESS_COMPENSATION_EXECUTION_ENABLED=false`；批准案件不会支付 |
| 留存删除 | `HARNESS_RETENTION_ENABLED=false`；默认保留期 180 天，只能规划和审查，不能默认删除 |
| 构建版本 | 未设置 `HARNESS_BUILD_GIT_SHA` 时如实记录 `UNKNOWN` |

`.env.example` 需要由进程管理器或 shell 显式加载；应用不会自动读取 `.env`。demo 提供合成身份和订单；第二个演示身份用于独立材料核验、例外审批和发布。pilot 不接受这些演示凭证，不自动播种演示政策、知识或评测数据。

pilot 启动要求服务器身份、非内存 JDBC 配置、真实只读订单 endpoint 及允许主机。模型实际请求还要通过服务端模型 endpoint 白名单与协议校验；配置不允许调用方注入任意 URL。真实环境的 TLS、凭证生命周期、模型连通性和协议兼容性需按运行手册另行验收。

### 2.2 三个 Maven 模块

| 模块 | 当前职责与主要文件 |
| --- | --- |
| `harness-common` | DTO、状态、异常与 JSON/Trace 工具；包括 `OrderInfo`、`RuleInfo`、`WorkflowExecution`、旧评测 DTO |
| `harness-core` | 工作流、时间轴、规则、检索、模型网关及 `platform` 业务服务；JDBC `PlatformStore` 在这里实现事务、不可变记录、CAS 和审计 |
| `harness-api` | Spring Boot 入口、v1/v2 Controller、身份与浏览器边界过滤、工作台、环境配置、Flyway Migration 和 API 回归 |

存储通过 JDBC 使用统一的租户/记录种类/记录 ID 主键与版本字段。`V1__platform_records.sql` 建立业务记录及审计表；`V2__case_queue_projection.sql` 添加城市、站点、风险优先级和到期排序投影及索引。Flyway 在启动时运行。测试使用真实 JDBC/Flyway 与 H2，不把内存 Map 当作 v2 持久化替代品。

当前 v2 持久对象涵盖案件、材料、订单证据快照、决定、执行和步骤、审核及动作、政策、知识、配置、评测数据集/运行/结果、反馈、幂等记录、资金台账和留存计划。旧 v1 的部分兼容存储与词法资产仍存在；pilot 返回 v1 迁移错误，不能通过旧接口绕过 v2 的真实源和授权边界。

### 2.3 可追溯的主要入口

- [时间轴与工作流](../../harness-core/src/main/java/com/delivery/harness/agent/)：`OrderTimeline`、两个 Workflow、`WorkflowEngine`、`StepRecorder`、`OutputFormatter`、`EvidenceValidator`、`GuardrailChecker`。
- [平台服务](../../harness-core/src/main/java/com/delivery/harness/platform/)：`adapter`、`auth`、`casework`、`execution`、`quality`、`operations`、`compensation`、`store`。
- [v2 Controller 与安全过滤](../../harness-api/src/main/java/com/delivery/harness/gateway/platform/)；[工作台](../../harness-api/src/main/resources/static/workbench/)；[Migration](../../harness-api/src/main/resources/db/migration/)。
- [核心平台测试](../../harness-core/src/test/java/com/delivery/harness/platform/)；[工作流测试](../../harness-core/src/test/java/com/delivery/harness/agent/)；[API 测试](../../harness-api/src/test/java/com/delivery/harness/gateway/)。

## 3. 已实现的关键产品语义

### 3.1 金额、时间和结果状态独立

v2 的 `authoritative_amount` 是可空权威值。缺少证据、未覆盖政策、规则冲突或未核验损坏不能静默变成 0 元。JSON 使用忽略 null 的配置，因而空权威金额可能表现为字段省略；调用端必须按 unknown 处理，不能默认 0。

为兼容旧演示客户端，`suggested_amount` 可能仍保存候选值，`timeline` 可能保留旧数字字段。是否可形成决定，应读取 `decision_status`、`authoritative_amount`、`timeline_v2` 和证据质量；候选值不能成为资金授权。

| 维度 | 字段与含义 |
| --- | --- |
| 执行 | `SUCCESS / DEGRADED / FAILED / TIMED_OUT / CANCELLED`，说明流程与依赖情况 |
| 决定 | `DETERMINED / INSUFFICIENT_EVIDENCE / OUT_OF_SCOPE / POLICY_CONFLICT`，说明是否有可确定的业务结论 |
| 时间 | `ON_TIME / LATE / UNKNOWN / NOT_APPLICABLE`；阶段值按秒保留，缺失或逆序另列质量原因 |
| 人工复核 | 独立 review 状态，绑定决定及证据版本；高风险和例外采用独立审核身份 |
| 模型解释 | `model_adoptable` 说明结构、引用及相关检查是否允许采用，不授权资金或认定责任 |
| 客户说明 | `verified_customer_message` 只由服务端确定性模板产生；模型原文状态仅为 `DRAFT / BLOCKED` |
| 资金 | `NOT_EXECUTED / PENDING / SUBMITTED / UNKNOWN / SUCCEEDED / FAILED`；批准不等于执行成功 |

最长耗时阶段只是基线。真实只读订单缺少历史 ETA/运力时不会调用合成工具伪造历史背景，而是明确记录 `HISTORY_UNAVAILABLE`。缺少轨迹、天气及站点历史证据的责任归因只能作为待审假设。

### 3.2 独立身份、发布与审核

身份来自服务器配置，角色与 tenant/city/station scope 同时生效，`ADMIN` 不是所有业务角色的替代。`PrincipalDirectory` 提供同租户、同范围的审核人目录；转派只能选择真实存在且有权限的审核人，领取/转派同步案件负责人。不存在或不具备范围的目标不会让任务进入无主状态。

材料上传人与核验人分离；人工例外作者不能批准自己的例外；政策、知识与运行配置具有独立发布约束。候选运行配置发布必须关联同配置、同冻结内容的通过评测。已经形成的证据、决定和配置核心内容不可原地改写。

### 3.3 费用和业务效果分开记录

供应商没有返回 token 或缺少有效价格版本时，用量/费用保持 `UNKNOWN`。已知费用按供应商 token 与明确币种、版本价格计算，不用字符数替代 token。

运营汇总采用 **known cost + unknown count** 口径：`model_cost.totals_by_currency_and_price_version` 只累加已知费用；`known_executions` 与 `unknown_executions` 分别记录已知样本数和未知数量，`status` 区分 `KNOWN / PARTIALLY_KNOWN / UNKNOWN`。未知数量不是 0 元成本，也不与不同币种或价格版本直接混算。

现有原始执行耗时可计算 p50/p95/p99，SUCCESS 与 DEGRADED 分别统计。真实人工处理时长、采用率、改判率、准确率提升、单位案件成本和客诉效果尚没有试点实测，不能用“完成任务数”代替这些指标。

## 4. FR-01～FR-14 功能实施映射

下表的“实现”指代码、演示或本地契约回归。每项最后一列保留未被本轮证据覆盖的生产条件。

| FR | 已落地行为与主要模块 | 对应本地测试 | 验收边界与后续门禁 |
| --- | --- | --- | --- |
| FR-01 身份、授权与数据边界 | `PlatformSecurityFilter`、`AuthContext`、`PrincipalDirectory`、`Redaction`、`BrowserBoundaryFilter`；服务端身份、租户及城市/站点 scope、v1 pilot 禁用、受控引用/导出、拒绝访问审计、出站允许字段 | `PlatformSecurityFilterTest`、`PrincipalDirectoryTest`、`CaseServiceTest.scopesAndTenantAreServerEnforced`、`OrderSourceTest.sourceMetadataAndOrderPresentationCannotLeakUncontractedPersonalFields`、`QualityAcceptanceApiTest` | 已验证本地核心边界；真实 SSO、凭证轮换/撤销、组织权限清单、完整端点渗透和隐私审批仍需验收 |
| FR-02 可信证据与时间轴 | `OrderSource` 冻结源版本、原始时间和采集质量；`OrderTimeline` 秒级阶段、nullable 值、UNKNOWN/INVALID、并列最长阶段、取消不适用；`RuntimeBinding` 使用服务器冻结证据 | `OrderTimelineTest`、`OrderSourceTest`、`TrustedWorkflowTest.liveOrdersNeverUseSyntheticReferenceTools` | 真实源契约已编码；真实字段准确性、时间业务语义和历史背景源未接入验收，不能给历史责任定论 |
| FR-03 政策与确定性决定 | `RuleBaseService`、`CompensationRuleToolkit`、`PolicyService`；发布版本、生效/范围、冲突拒绝、BigDecimal 金额、FLOOR 分钟、封顶与计算出处、无政策/缺证据区别、材料验证 | `RuleBaseServiceTest`、`CompensationRuleToolkitTest`、`QualityServiceTest.policyPublishRejectsOverlapAndRequiresIndependentPublisher`、`CompensationSuggestionApiTest` | 演示政策可回归；真实政策及适用时点、订单金额与可退余额口径必须签字。补送及券的实际业务拆项、财务科目需真实系统适配 |
| FR-04 类型结果、引用与文案 | `OutputFormatter`、`EvidenceValidator`、`GuardrailChecker`；两类严格嵌套 Schema、ID/版本/路径/类型/数值/单位/规则快照检查、假设分离、确定性核验说明、原始模型文案草稿化 | `OutputFormatterTest`、`EvidenceValidatorTest`、`GuardrailCheckerTest`、两类分析 API 测试 | 已覆盖虚构引用和金额逃逸回归；不是自由文本事实核验器，也不能据此宣称任意 Prompt 注入已被识别 |
| FR-05 降级与失败可观测 | 两类 Workflow、`WorkflowEngine`、`StepRecorder`、`ExecutionService`；required/optional、基础结果保留、失败步骤/耗时、同一 execution 的失败轨迹、模型关闭与模型故障区别 | `TrustedWorkflowTest`、`WorkflowEngineTest`、`ExecutionServiceTest.dependencyFailurePersistsCompletedAndFailedSteps`、两类 API 模型故障测试 | 本地故障注入通过；真实模型/订单/数据库故障组合、告警投递及运维响应需演练 |
| FR-06 持久快照与原子审计 | JDBC `PlatformStore` 与两版 Flyway Migration；不可变证据/决定、事务内业务写与审计、CAS、案件队列投影、版本哈希；引用保留与受控留存 | `PlatformStoreTest`、`CaseServiceTest.approvalAuditStorageFailureRollsBackAndCanBeRetried`、`RetentionServiceTest` | H2 事务与文件重新打开已有回归；真实 PostgreSQL Migration、隔离级别、备份恢复、RPO/RTO 均未实测 |
| FR-07 异步、幂等与配额 | `ExecutionService`；202 创建、持久队列、相同键正文哈希、租户及全局 admission、公平调度、取消、总 deadline、租约与 fence；`QualityEvalService` 独立线程池 | `ExecutionServiceTest` 的 20 并发同键、公平队列、超时、取消及租约恢复测试 | 代码限额与本地并发已验证；生产吞吐、队列等待、批量评测满载下在线 SLO、多实例/DB 争用需压测 |
| FR-08 案件、材料与人工闭环 | `CaseService`、`ManualExceptionService`、`PrincipalDirectory`、工作台；建案/补证/独立核验/领取/转派/批准/退回/例外/结案，绑定决定与证据，资金状态独立 | `CaseServiceTest`、`PrincipalDirectoryTest`、`PlatformAcceptanceApiTest.caseToFrozenRuleDecisionAndHumanApprovalDoesNotPay`、`CompensationLedgerTest` | 可运行演示闭环；真实审核人 roster、授权额度、附件处理制度、操作体验和既有工单流程需试点验证 |
| FR-09 知识生命周期与检索 | `KnowledgeService`、`PolicyService`、`RetrievalService`；预览、版本切片、独立审核发布/撤回、精准规则 ID、中文同义词、scope/time 过滤、历史引用 | `QualityServiceTest` 的知识生命周期和 scoped 搜索测试、`QualityAcceptanceApiTest.knowledgeReferencePublishesAtomicallyAndWithdrawsFromSearch`、`RetrievalScoringTest` | 词法方案与历史引用已实现；完整恶意语料红队、发布中途故障、磁盘耗尽及大语料质量/性能尚未验收；未宣称需要或已引入向量系统 |
| FR-10 可复现评测与质量门禁 | `QualityEvalService`、`ExactScorer`、`RuntimeBinding`；冻结数据集/配置/证据、实际调用哈希、异步进度/重放/比较/重试、精确金额及规则集合、NOT_MEASURED、盲测防调参复制、同版本报告发布 | `QualityServiceTest`、`QualityAcceptanceApiTest`；旧接口另由 `ObserveAndEvalApiTest`、`ScorerTest` 验证降级不冒充准确 | 合成与边界回归已有代码证据；真实标注、标注一致性、冻结盲测、样本代表性及模型增益未验证。报告生成成功不等于通过硬门禁 |
| FR-11 模型配置、费用与退出 | `RuntimeConfigService`、`RuntimeBinding`、`LlmGateway`、`HttpLlmClient`；实际 Prompt/model/参数绑定、允许 endpoint、有限重试、协议探测、固定案件灰度路由/回滚、确定性开关、真实用量/版本价 | `BoundedLlmClientTest`、`QualityServiceTest.candidatePromptAndModelAreActuallyBoundToGatewayAndRestored`、费用与路由测试、`TrustedWorkflowTest.deterministicRuntimeNeverCallsModelAndUsesFrozenServerOrder` | 配置绑定与关闭路径已验证；供应商账单核对、本地模型资源成本、真实模型兼容性和退出收益决策仍需证据 |
| FR-12 观测、反馈与改进 | `OperationsService`、`StructuredFeedbackService`、执行步骤及审计；完成/降级分开、原始耗时分位数、known cost 与 unknown 数量、案件/决定版本反馈、独立核验、关联修复及回归 | `OperationsServiceTest`、`QualityServiceTest.feedbackEnforcesTenantDecisionScopeIdentityAndIndependentVerification`、`ObserveAndEvalApiTest` | 生命周期可运行；真实告警平台接入、运营看板采样、人工效率/改判及业务效果实验尚未完成 |
| FR-13 真实只读 Adapter | `OrderSource`；允许主机 HTTP GET、无重定向、严格 source envelope、ID/scope/金额/时间校验、offset/local 时区规范化、明确错误、无合成回退；真实历史背景缺口显式记录 | `OrderSourceTest`、`ExecutionServiceTest.upstreamScopeChangesCannotLeakNewStationFactsThroughAnOldCase`、`TrustedWorkflowTest.liveOrdersNeverUseSyntheticReferenceTools` | 适配器实现和本地 HTTP 契约通过；尚未认证某个真实业务服务，真实账号/字段/限流/回退策略及历史服务需双方验收 |
| FR-14 受控资金扩展 | `PaymentChannel`、`ConfiguredPaymentChannel`、`CompensationLedgerService`；默认关闭、FUNDS 分权、批准快照/政策再验证、当前余额及未决预留、订单累计上限、outbox、签名/租户绑定回调、只读对账、旧单不覆盖新单 | `CompensationLedgerTest`；`CaseServiceTest` 和 `ExecutionServiceTest` 支付锁回归 | 仅扩展契约与 synthetic 渠道回归。未移动真实资金；真实渠道必须保证原子 eligibility version/余额扣占、业务幂等与可靠查单，且完成认证、故障/乱序/对账演练后才能另行开放 |

## 5. AT-01～AT-26 验收用例映射

“本地覆盖”只表示所列测试与实现对应，不替代 PRD 中对真实环境的验收要求。

| AT | PRD 场景与当前行为 | 对应模块/测试证据 | 当前结论 |
| --- | --- | --- | --- |
| AT-01 | 完整准时订单为 ON_TIME；确定性不赔，不能虚构超时原因 | `OrderTimelineTest.reportsNoAnomalyWhenDeliveredWithinThePromise`；`AbnormalOrderAnalysisApiTest.recognisesAnOrderThatWasNotLate`；赔付 API 准时测试 | 本地覆盖；另有合法 ON_TIME 精确评测满分回归 |
| AT-02 | 缺少承诺时间为 UNKNOWN，可信阶段值不补零 | `OrderTimelineTest.unknownPromiseRetainsNullTrustedValuesAndDoesNotClaimOnTime` | 本地覆盖；旧兼容数字不能用于 v2 决策 |
| AT-03 | 全部非空但逆序仍 INVALID，保存原始时间 | `OrderTimelineTest.allPresentButReversedTimestampsRemainInvalidAndRawDataIsRetained`；`OrderSourceTest` 的 DST/未来时间测试 | 本地覆盖；真实源修复流程需双方确认 |
| AT-04 | 15/16、30/31 分钟及相邻秒按明确 FLOOR 政策处理 | `CompensationRuleToolkitTest.secondBoundariesFollowExplicitFloorPolicy` | 本地覆盖；这是演示政策口径，不是默认经营政策 |
| AT-05 | 同优先级重叠规则不能任意首匹配 | `CompensationRuleToolkitTest.equalPriorityOverlapIsAConflictAndNotAnArbitraryFirstMatch`；`QualityServiceTest.policyPublishRejectsOverlapAndRequiresIndependentPublisher` | 本地覆盖发布拒绝与运行冲突 |
| AT-06 | 缺件无政策为 OUT_OF_SCOPE，权威金额空，不形成确定拒赔 | `CompensationRuleToolkitTest.missingEvidenceAndUnsupportedPolicyNeverBecomeZeroAmountDecisions`；`CompensationSuggestionApiTest.blocksUnsupportedComplaintPolicyWithoutClaimingAZeroPayoutDecision` | 本地覆盖；转人工是能力边界 |
| AT-07 | 自报 FULL 不等于已核验损坏，候选值不能批准 | `CompensationRuleToolkitTest.unverifiedDamagePreservesOnlyV1Candidate`；`TrustedWorkflowTest.clientSelfReportedVerificationCannotAuthorizeDamageDecision`；`CaseServiceTest.damageMaterialNeedsIndependentVerification` | 本地覆盖；只有同事务冻结的已核验材料授权可信标记 |
| AT-08 | 两条工作流模型故障保留确定性结果及失败步骤 | `TrustedWorkflowTest.bothWorkflowsKeepDeterministicResultsAndFailedModelStepsWhenModelIsDown`；两类分析 API 模型故障测试 | 本地覆盖；实际供应商超时组合故障待演练 |
| AT-09 | 错数值、错类型/单位/版本、伪造规则不能成为可采用模型结论 | `EvidenceValidatorTest`；`OutputFormatterTest.rejectsRulesAsTextAndMissingOrLegacyEvidenceFromBothSchemas`；`AbnormalOrderAnalysisApiTest.rejectsWrongCitationValuesWhileKeepingTheParsedModelAvailableForReview` | 本地覆盖；不可只凭解析成功或 HIGH 放行 |
| AT-10 | 模型提出冲突金额时权威金额不变，模型话术不可核验采用 | `GuardrailCheckerTest.allowsTheAuthoritativeAmountToBeQuotedButRejectsConflicts`；`CompensationSuggestionApiTest.ignoresAnAmountTheModelInventsAndFlagsIt`；确定性模板安全回归 | 本地覆盖分层防线；原始模型文案仅 DRAFT/BLOCKED |
| AT-11 | ETA/运力故障或非历史证据不会形成完整可信归因 | `TrustedWorkflowTest.optionalToolAndRetrievalExceptionsAreRecordedAndDegradeInsteadOfDiscardingTimeline`、`liveOrdersNeverUseSyntheticReferenceTools` | 本地覆盖缺口处理；历史 ETA/运力本身未接真实服务 |
| AT-12 | 租户及组织越权、旧接口绕过受阻；拒绝有审计 | `PlatformSecurityFilterTest`；`CaseServiceTest.scopesAndTenantAreServerEnforced`；`QualityAcceptanceApiTest.exactIdsUnknownVersionsAndScopedPublishPermissionsAreEnforced` | 核心边界本地覆盖；试点需按案件/执行/引用/导出端点清单完成全面权限验收 |
| AT-13 | 知识内容为参考文本，不能改变身份、确定性金额或调用权限 | `KnowledgeService.preview` 的指令告警；固定工作流、服务端 identity、规则金额及引用校验；模型金额与权限相关回归 | 防线已编码；完整恶意知识语料及 Prompt 注入红队未完成，不能宣称通用攻击免疫 |
| AT-14 | 同幂等键同正文返回同任务，不同正文冲突 | `ExecutionServiceTest.concurrentTwentyRequestsShareOneDurableExecution`；`PlatformAcceptanceApiTest` 同键重放 | 本地 20 并发回归覆盖；真实多实例高负载另验 |
| AT-15 | 并发领取只有一个赢家；更新证据使旧决定/审批失效 | `PlatformStoreTest.concurrentClaimOnlyAllowsOneWinner`；`CaseServiceTest.addedEvidenceInvalidatesApprovalAndOldReviewCannotSubmit`；材料冻结竞态测试 | 本地 CAS/事务覆盖；生产 PostgreSQL 锁及并发组合另验 |
| AT-16 | 持久记录可重新打开；过期执行租约用冻结内容恢复；迟到结果不能覆盖 | `PlatformStoreTest.recordsSurviveDatabaseShutdownAndReopen`；`ExecutionServiceTest.expiredWorkerLeaseRecoversUsingFrozenEvidenceAndConfiguration`；`QualityServiceTest.startupMarksInterruptedDurableRunAndKeepsFrozenSnapshots` | H2 文件、租约恢复和中断评测记录本地覆盖；真实进程强杀、DB重启及审批期间恢复未全量演练。中断评测标记失败，另建冻结重试 run |
| AT-17 | 知识切片随版本原子发布，撤回不再进入新检索，历史引用仍可核对 | `QualityServiceTest.knowledgeDraftAndWithdrawnChunksAreNotSearchableButHistoricalCitationRemains`；知识 API 生命周期测试；`PlatformStore` 事务 | 发布/撤回与历史引用本地覆盖；发布中途存储故障、容量耗尽和大库导入未实测 |
| AT-18 | 候选 Prompt/model/参数真正进入请求，记录哈希并在结束后释放 binding | `QualityServiceTest.candidatePromptAndModelAreActuallyBoundToGatewayAndRestored`；异步冻结评测与配置 API 测试 | 本地请求绑定覆盖；真实模型协议探测需供应商环境 |
| AT-19 | 金额差 0.01 触发硬门禁，不被其他维度平均分掩盖 | `QualityServiceTest.wrongCentAndExtraRulesCauseHardGateFailureAndMissingLabelsRemainUnmeasured`；`ExactScorer` | 本地精确金额覆盖；发布只能使用真正通过的标注报告 |
| AT-20 | 无期望为 NOT_MEASURED，多余规则降低 precision 并破坏 exact-match | 同上；`ScorerTest`、`OverallScoreTest`；`ExactScorer` 的规则集合评分 | 本地覆盖；没有标签不能自动产生“准确”或“满分” |
| AT-21 | 在线/评测线程池隔离，有界 admission、公平队列、队列状态可见 | `ExecutionServiceTest.globalAdmissionIsBoundedAndQueuedTenantsAlternateFairly`；`ExecutionService.queueStatus`；`QualityEvalService` 独立有界池 | 资源隔离与公平本地覆盖；评测满载时在线 p95/p99、吞吐和DB资源争用需要真实负载测试 |
| AT-22 | 关闭模型仍有冻结订单、时间轴、政策结果、模板和人工入口 | `TrustedWorkflowTest.deterministicRuntimeNeverCallsModelAndUsesFrozenServerOrder`；默认 runtime 的 v2 接受测试 | 本地与默认演示覆盖；关闭模型不等于自动批准 |
| AT-23 | 业务审计失败使审批一起回滚，恢复后按合法版本重试 | `PlatformStoreTest.domainAndAuditRollbackTogether`；`CaseServiceTest.approvalAuditStorageFailureRollsBackAndCanBeRetried` | 本地故障注入覆盖；真实数据库网络故障和审计存储恢复需演练 |
| AT-24 | 真实 Adapter 404 返回 NOT_FOUND，不能生成合成替代单 | `OrderSourceTest.liveNotFoundNeverFallsBackToSyntheticData`；pilot v1 边界测试 | 本地 HTTP 契约覆盖；真实源认证及查无订单实际响应待联合验收 |
| AT-25 | 冻结旧证据/政策/配置可重放，新版本不改写历史决定 | `RuleBaseServiceTest.publishedVersionIsImmutableAndSnapshotsCannotBeMutatedByCallers`；`QualityServiceTest.asyncEvaluationFreezesEvidenceAndReplayNeverCallsModel`；`PlatformStoreTest.immutableSnapshotsCannotBeRewritten` | 本地不可变与重放覆盖；真实政策变更和版本迁移由业务签字验证 |
| AT-26 | 审批通过不等于支付；默认执行关闭；资金状态与审核分离 | `CaseServiceTest.approvalBindsImmutableDecisionAndNeverPays`；`PlatformAcceptanceApiTest.caseToFrozenRuleDecisionAndHumanApprovalDoesNotPay`；`CompensationLedgerTest.disabledExecutionCannotMoveMoney` | 本地与演示覆盖；真实支付必须单独通过 FR-14 门禁 |

## 6. 生产试点尚需完成的外部门禁

| 门禁 | 所需实际证据 | 本轮状态 |
| --- | --- | --- |
| 身份和数据治理 | 真实组织目录、角色/范围签字、独立审核人与发布人、凭证发放/轮换/撤销、TLS网关及敏感字段清单 | 服务器配置及范围校验已实现；真实组织接入未验收 |
| 真实订单与材料 | 真实只读源联合契约、源版本/时区/金额含义、可退字段、限流与故障样本、证据保真与访问制度 | 通用 Adapter 与本地 HTTP 回归已实现；未认证真实业务源 |
| 经营政策 | 真正适用的币种、金额基数、生效时点、场景/地区、补送/券/红包拆项、审批阈值和例外权限 | 演示政策可运行；不能直接用于真实经营或资金 |
| 真实 PostgreSQL | 指定版本的 Migration、事务/锁、并发CAS、索引计划、连接池、异常回滚与恢复验证 | JDBC/Flyway及H2回归已有；真实PostgreSQL实测未完成 |
| 模型与质量 | 实际供应商协议和白名单、价格/用量账单核对、脱敏真实标注、标注一致性、冻结盲测、错误分层和影子实验 | 绑定与精确门禁已实现；未证明真实准确率、人工节省或净收益 |
| 容量与可靠性 | 在线与评测满载、长尾延迟、限流、存储容量、进程/数据库故障、多租户公平和生产告警 | 本地有界队列及故障回归已有；生产SLO、容量与告警实测未完成 |
| 备份恢复 | PostgreSQL 一致备份、构建/配置/密钥引用备份、隔离恢复、版本/审批/队列/台账核对、RPO/RTO 演练 | 有运行流程说明；没有真实备份恢复成绩 |
| 资金渠道 | 财务授权、真实可退余额、原子 eligibility version/余额检查、渠道幂等查单、签名/租户绑定、乱序/超时/重复演练、对账和人工异常处置 | 扩展默认关闭；synthetic 台账测试不移动真实资金 |
| 留存与退役 | 保留期签字、保留锁、引用退役、独立管理员、dry-run核对、删除回执、备份中的敏感数据策略 | 引用感知计划和事务删除可测；生产策略与大数据迁移未验收 |

资金和留存开关不会因为普通审核或演示流程成功而自动开启。无法确认外部终态的资金单保持 UNKNOWN，通过原业务补偿号查单对账；被资金或评测引用的数据阻止普通留存删除，须另行审查退役迁移。

现阶段不宣称已经完成对客自动发送、商家/骑手处罚、真实资金办理、向量检索增益或生产业务效果证明。工作台是内部辅助流程，人工审核与既有业务系统的办理职责仍然存在。

## 7. 交付验证与审阅步骤

最终构建使用 JDK 17 和仓库 Maven Wrapper：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

主集成应记录测试总数、失败/跳过、构建版本与打包产物。并行开发期间目标测试不执行 `clean`；最终构建串行执行，避免多个进程同时修改共享 `target`。

演示审阅按以下次序完成：

1. 启动 demo，打开 `/workbench/`，确认页面合成标记、默认确定性配置和本机地址。
2. 使用 `DEMO-002 / OVERTIME` 创建案件，检查冻结证据、时间轴、规则、权威金额和人工阈值；审核后仍显示未执行补偿。
3. 创建损坏案件，上传冻结正文、由另一身份核验后重新执行；确认自报损坏、外部链接元数据和旧审批不能充当新的已核验证据。
4. 领取或转派时只使用该案件范围内的审核人；人工例外生成新决定，另一个合法主管审批。页面应显示当前不可变决定而不是旧 execution 的结果。
5. 创建候选政策/知识/运行配置，独立审核发布；执行带精确金额、币种、决定状态和规则集合标签的评测，检查硬门禁及 NOT_MEASURED，最后由独立发布者上线配置。
6. 查看执行步骤、失败/降级、审计、已知费用和未知数量；关闭模型后验证基础流程仍可用。客户可复制说明只使用服务端核验模板。
7. 保持资金与留存执行关闭。真实环境按第6节逐项补齐证据后，再由对应负责人决定是否开放试点与独立扩展。

人工审阅结果与自动回归报告都应绑定实际构建版本。新的修复、配置或 Migration 进入分支后，历史测试成绩不替代当前验证；外部门禁未完成时，交付状态应写为“演示与契约实现已交付，生产试点门禁待验收”。
