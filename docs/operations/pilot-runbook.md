# 部署、恢复与试点运行手册

更新日期：2026-10-15。对应[产品 PRD](../prd/delivery-harness-product-prd.md)、[实现映射](../implementation/delivery-harness-prd-implementation.md)和[v2 API](../api/v2.md)。

本轮交付内部辅助决定与复核流程。真实身份、经营政策、订单系统、PostgreSQL、模型和业务效果必须分别验收。**FR-14 资金执行与实际留存删除在 M2 验收后另行立项；本手册没有支付或自动删除的启用步骤。**批准案件仍为 NOT_EXECUTED，既有业务系统的人工办理不由本应用代办。

## 1. 本机 demo

使用 JDK 17 和 Maven Wrapper 构建。测试无需运行 Ollama。

```bash
./tools/check-format.sh
./mvnw clean verify
cp .env.example .env
set -a
source .env
set +a
export HARNESS_BUILD_GIT_SHA=$(git rev-parse HEAD)
java -jar harness-api/target/harness-api.jar
```

应用不会自动加载 `.env`；由 shell 或进程管理器显式提供。默认监听 `127.0.0.1:8080`，访问 `http://127.0.0.1:8080/workbench/`。合成订单为 DEMO-001～006；默认 `runtime-default-v1` 使用确定性规则与模板，没有模型服务也能运行。

所有 `/api/**` 请求都须显式携带 `Authorization: Bearer ...`。未提供凭证不会自动获得演示角色。工作台将访问凭证保存在页面内存，刷新后重新输入。

| 演示身份 | 公开合成访问凭证 | 角色与用途 |
| --- | --- | --- |
| demo-agent | `demo-agent-token` | AGENT、OPERATOR：建案、材料与执行，不承担独立审批 |
| demo-reviewer | `demo-reviewer-token` | REVIEWER：核验他人材料、领取普通复核 |
| demo-supervisor | `demo-supervisor-token` | REVIEWER、SUPERVISOR：高风险复核和人工例外提议；不能自批 |
| demo-admin | `demo-admin-token` | POLICY_EDITOR、QUALITY、AUDITOR、ADMIN：编辑政策/配置/数据集，管理与审计 |
| demo-publisher | `demo-publisher-token` | QUALITY、POLICY_PUBLISHER：作为另一用户审核发布政策、知识与配置 |

这些凭证只用于本机合成演示，不是 pilot 身份。角色范围同时生效；管理身份不等于所有业务角色。编辑者与发布者须为不同 user_id，主管与案件/决定作者也必须分离。

可先查询当前身份与只读源契约元数据：

```bash
curl -H 'Authorization: Bearer demo-agent-token' \
  http://127.0.0.1:8080/api/v2/session
curl -H 'Authorization: Bearer demo-agent-token' \
  http://127.0.0.1:8080/api/v2/adapters/status
```

源状态只描述契约，不发起订单探测，也不证明真实数据源健康。其响应不包含 endpoint、订单凭证或客户正文。

### 演示复核与发布

1. 用 demo-agent 创建 `DEMO-002 / OVERTIME` 案件并提交赔付分析；检查冻结证据、秒级时间轴、规则和权威金额。默认演示政策会得到 20 元建议，属于默认 10 元阈值以上的高风险案例。
2. 切换 demo-supervisor 领取并审核该案件。案件和决定作者不能领取或批准自己的复核；高风险需 SUPERVISOR。批准后检查仍为 NOT_EXECUTED。
3. 损坏案例须上传冻结正文与损坏等级，再由另一 REVIEWER 核验，重新执行后才可采用损坏事实。外链或自报等级不能替代已核验正文。补证使旧审批失效。
4. 手工例外只形成新的待审决定。REFUND 必须具备冻结的可退余额，不能用订单原金额补齐。例外作者另找合法主管审核；默认五个公开身份只有一位主管，完整例外闭环需另配独立主管身份。请求中的 amount 为 JSON number，版本为 JSON integer，不能用字符串代替。
5. 用 demo-admin 编辑政策/知识/运行配置；切换 demo-publisher 发布。运行配置先以候选版本运行带精确标签的评测，只有同一冻结配置的通过报告可用于发布；同一用户创建后切换角色也不能自发自批。
6. 查看步骤、失败/降级、审计与已知/未知费用。导出使用 `POST /api/v2/cases/{id}/export`；旧 GET 返回 405。客户说明使用服务端核验模板，模型原始文案不能作为核验结果。

默认 H2 内存库在进程结束后丢失。演练本机持久恢复可设置：

```bash
export HARNESS_DATABASE_URL='jdbc:h2:file:./data/harness;DB_CLOSE_ON_EXIT=FALSE'
```

H2 文件不要由多个 JVM 同时打开。本地 H2 恢复测试不代表 PostgreSQL 恢复已通过。

## 2. pilot 身份、网关与部署要求

pilot 只接受可信 IdP 签发的 JWT。应用校验 RS256 签名、issuer、audience、exp/nbf，并读取 sub、tenant_id、roles、city_codes 和 station_ids；演示租户和演示主体在 pilot 被拒绝。它是资源服务器，没有内置组织登录页面，客户端须通过组织批准的登录流程获取 access token。

下面是配置形状，域名、数据库和凭证由部署团队填写：

```bash
HARNESS_MODE=pilot
HARNESS_DEMO_IDENTITIES='[]'
HARNESS_DATABASE_URL=jdbc:postgresql://db.example.internal:5432/harness
HARNESS_DATABASE_USERNAME=harness
HARNESS_OIDC_ISSUER=https://login.example.org/issuer
HARNESS_OIDC_AUDIENCE=delivery-harness
HARNESS_OIDC_JWK_SET_URI=https://login.example.org/keys
HARNESS_BROWSER_ALLOWED_HOSTS=workbench.example.org
HARNESS_BROWSER_ALLOWED_ORIGINS=https://workbench.example.org
HARNESS_ORDER_ENDPOINT='https://orders.example.internal/readonly/orders/{order_id}'
HARNESS_ORDER_ALLOWED_HOSTS=orders.example.internal
HARNESS_DETERMINISTIC_ONLY=true
```

数据库密码、订单访问密钥和可选模型密钥由进程管理器或密钥管理服务注入，不提交仓库。未指定 JWK URL 时按 HTTPS issuer 发现密钥；指定时仍校验 issuer 和 audience。IdP 不可用、签名不符、过期或 scope 缺失时应拒绝访问，不能回退演示身份。

生产 pilot 必须使用团队维护的持久数据库。H2/本地签名 JWT 用于自动化夹具，不能作为真实 PostgreSQL 或 IdP 的上线证明。上线前记录实际数据库版本、真实 issuer/audience、用户与角色范围、撤权与密钥轮换结果。

应用默认仅绑定本机；通过受控 TLS 网关暴露服务。Host 仅允许明确配置的主机名，Origin 精确到协议、主机和端口，不使用 `*`。代理须保留与白名单一致的单一 Host；不能用任意转发头绕过检查。非浏览器请求可以没有 Origin，但仍须通过 Host 和显式 bearer 验证。

API 不用 cookie/session 登录，也不从查询参数或表单读取 token。CSRF 保持开启，仅对显式 bearer 请求应用无环境凭证的例外。浏览器 Host/Origin 校验同时阻止演示接口被其他站点或重绑定域名调用。401 不逐条写无限业务审计；已认证的越权拒绝需可追溯。

审核人目录登记成功认证过的主体，按 tenant/city/station 与角色筛选；它不是完成同步的组织 roster。服务重启后，审核人先请求 `/api/v2/session` 重新登记，再进行转派。真实 IdP 权限变更、用户撤权和目录更新仍须组织联调验收。

### pilot 首次发布

pilot 不播种示例政策、知识或评测数据。分别准备编辑者、独立发布者、案件操作员、普通审核人与高风险主管：

1. 导入经业务授权且脱敏的政策，确认币种、生效时点、城市/站点、金额基数与可退余额口径；由另一用户验证发布。
2. 建立脱敏知识版本和冻结数据集；真实标注须说明来源、标签一致性与盲测边界，合成样本不能证明业务增益。
3. 建立 `deterministic_only=true` 的候选运行配置；model 可使用 `configured-default` 占位标识，不需要连接一个假模型服务。配置只引用已发布政策和知识。
4. 运行评测并检查 NOT_MEASURED、决定状态、金额/币种和规则标签。由另一用户使用匹配配置哈希的通过报告发布；pilot 发布要求报告记录构建 Git SHA。
5. 发布限定范围的路由，再以影子方式核对真实案例。没有已发布配置或可信运行时绑定时应拒绝在线执行，不能静默读取实时内存规则。pilot v1 返回 410，不能绕过 v2 边界。

## 3. 真实只读订单契约

订单请求为允许主机上的 GET，包含 X-Tenant-ID；服务器配置访问密钥后附带 Authorization。禁止重定向。建议源响应形状如下，示例不是已认证的业务源：

```json
{
  "schema_version": "1",
  "source_version": "orders-api-revision",
  "collected_at": "2026-10-15T06:00:00Z",
  "source_timezone": "Asia/Shanghai",
  "currency": "CNY",
  "historical_context_available": false,
  "order": {
    "orderId": "ORD-1001",
    "cityCode": "SHA",
    "stationId": "ST-01",
    "status": "DELIVERED",
    "orderAmount": 40.00,
    "refundableBalance": 30.00,
    "createTime": "2026-10-15T12:00:00+08:00",
    "acceptTime": "2026-10-15T12:01:00+08:00",
    "arriveShopTime": "2026-10-15T12:06:00+08:00",
    "pickupTime": "2026-10-15T12:12:00+08:00",
    "expectedDeliveryTime": "2026-10-15T12:30:00+08:00",
    "deliveryTime": "2026-10-15T13:05:00+08:00"
  }
}
```

金额非负且精确到分；refundableBalance 不得超过 orderAmount。可退余额缺失保持 null，元数据为 UNKNOWN；0 是明确无余额。不能把订单原金额当作可退余额，也不能把未知替换为 0。缺失余额会阻止确定退款建议。

时间接受 offset ISO 字符串，或由 source_timezone 解释的 local 时间，规范化至上海时间。夏令时歧义/不存在的 local 时间和不合理未来事件被拒绝；阶段缺失继续显示 UNKNOWN，逆序显示质量问题。缺少历史 ETA/运力时显示 HISTORY_UNAVAILABLE，当前快照不能证明历史责任。

响应体在接收过程中超过 1 MB 即取消读取；404→NOT_FOUND、401/403→UNAUTHORIZED、429→RATE_LIMITED、超时→504、字段/金额/时区漂移→INVALID_DATA。失败不查询合成 fixture。执行时订单范围变化会拒绝旧案件继续使用新范围事实，应重新建案核验。

联合验收须覆盖真实授权、订单不存在、限流、超时、异常字段、跨租户/站点、余额缺失/零/不足、时间边界和历史上下文。请求成功不等于源事实已完成业务核验。

## 4. 模型开关、预算与质量

首先保持全局 `HARNESS_DETERMINISTIC_ONLY=true` 和冻结配置的 `deterministic_only=true`。确定性 pilot 不需要启动模型，也不必设置一个虚构供应商或白名单域名。

仅在允许模型调用时，配置真实 `HARNESS_LLM_ENDPOINT`、`HARNESS_LLM_ALLOWED_HOSTS`、固定模型标识和密钥引用；非本机 endpoint 使用 HTTPS。白名单在实际调用时验证，空白名单拒绝模型调用；客户端不能在请求中注入 endpoint，只能引用服务器管理的 endpoint_ref。不要因模型被白名单拒绝而放宽浏览器或数据源白名单。

总 deadline 覆盖排队和执行；模型重试次数有界且共享剩余预算。在线任务默认 2 workers、50 queue、15 秒 deadline，评测资源独立；这些是默认限制，不是容量成绩。模型超时、结构/引用不合格时检查 DEGRADED 与模型不可采用标记，基础规则结果不等于已自动批准。

客户可用说明由服务端模板生成，业务 Prompt 不能覆盖代码的 system 约束。上线前逐项核对允许进入模型的字段、供应商留存安排、实际协议和价格版本。缺 token 或价格时费用为 UNKNOWN；不能用字符数推算成已知 token，也不能把未知费用写成零。

## 5. 故障处置与恢复

| 现象 | 处置 |
| --- | --- |
| Host/Origin 403 | 核对网关转发 Host 与精确 origin；保留白名单约束，不改成通配符 |
| 401 或 JWT claim 拒绝 | 检查 issuer/audience、签名密钥、exp/nbf、主体与scope；重新经组织登录获取合法凭证 |
| 数据库或 Flyway 启动失败 | 停止接入流量；保存版本、迁移 history 与安全日志；在隔离副本验证兼容性，不改已应用迁移或手工跳过校验 |
| 订单源失败/范围变化 | 保留原证据与错误；排除源故障或重新建案；不生成替代订单 |
| 模型失败/输出不可采用 | 保持人工入口，检查确定性结论和 DEGRADED；必要时关闭模型并重新验证发布配置 |
| 429 队列或租户配额 | 降低提交速率，查看队列；重试同一提交使用原 Idempotency-Key 与相同正文 |
| worker/进程中断 | 恢复扫描在租约过期后重新调度；独立心跳和deadline维护任务，fence阻止迟到结果覆盖终态 |
| background_failures 增长 | 按安全日志的 operation/code/trace_id 排查数据库或worker故障；日志不应输出客户正文或凭证 |
| 审批/审计失败 | 不宣告批准；同事务应回滚，恢复后重新读取当前版本再提交 |
| 409 版本冲突 | 刷新案件、材料、决定与配置版本；不要盲改 expected_version 或复用旧审批 |
| AUTHORSHIP_UNVERIFIED | 历史决定缺少可信发起人，重新执行分析；领取或主管角色不能补足作者证据 |
| 评测重启中断 | 核对 PROCESS_RESTARTED 与冻结内容，创建新 run；不覆盖原报告 |
| 磁盘/容量告警 | 限制写入与新增材料，保留证据并扩容；不能清空数据库或启用未交付的删除功能 |

业务审计使用 SQL 分页，游标同时编码 created_at 和 event_id。客户端应原样传递 cursor，不按字符串自行排序时间，也不只用时间戳构造下一页；相同时间事件仍需要 ID 边界。案件队列过滤在 SQL LIMIT 前执行，不能仅过滤当前页后宣称没有匹配案件。

策略撤回会阻止新运行继续采用；历史决定和评测按冻结内容核对。应用版本回退前先确认数据库迁移兼容性；若新 schema 不兼容，不直接启动旧二进制连接当前库。先停止流量，在隔离副本验证恢复方案，再由部署负责人选择已验证的版本/配置恢复路径。

## 6. 备份与上线证据

使用组织批准的数据库一致备份工具，保存数据库、构建 SHA、配置版本、Flyway history 和密钥管理引用。不要把真实密钥或客户证据提交到仓库。H2 文件库演练先关闭持有文件的 JVM；其成功不代表 PostgreSQL 在线备份可用。

备份前停止接收新的执行和变更；可排空的任务先等待终态。若必须保存活跃任务，使用数据库一致快照并保留其幂等、租约、证据和配置绑定。不得通过手改任务终态或删除审计来制造“已恢复”。

恢复至隔离数据库，先检查迁移校验，再以模型关闭的配置启动。核对租户/范围、案件当前决定、证据快照、材料正文与哈希、审核版本、审计顺序、评测配置和任务幂等绑定；观察恢复任务与后台失败计数。完成受控读写与独立审批验证后再逐步开放流量。

每次真实恢复演练记录备份点、数据完整性、实际恢复时长和差异。RPO/RTO、在线 SLO、业务准确率与人工节省未测量时写“待验证”，不能照抄 PRD 目标作为达成值。

上线记录至少包含当前提交的构建/测试/覆盖率报告、依赖审查和 CodeQL 结论，真实 IdP 与订单契约、组织权限签字、经营政策授权、PostgreSQL 迁移/恢复、容量与故障演练，以及影子评测结论。自动回归和本地签名 JWT 夹具不能替代这些真实环境证据。

资金办理与实际留存删除仅在 M2 验收后另行立项；后续设计和验收不得由本轮演示成功隐含授权。

## 8. 轻量列表与契约产物

列表和运营指标仅读取显式 summary_payload，不加载完整运行包。完整知识、规则、
运行配置和反馈更正需调用授权详情；运行配置的可见范围同时覆盖政策及每份知识，
评测还校验每个样本。单个冻结运行包限制为 512 KiB。反馈由独立核验人确认、
关联修复，再以包含该反馈样本的通过评测记录回归，不能把原始投票当标签。

API 契约由 `GET /api/v2/openapi` 按实际路由/DTO 生成并需 bearer；测试导出到
`harness-api/target/generated-api/openapi.v2.json`。不维护手写 JSON。core LINE
门槛只以根 POM `jacoco.core.line.minimum=0.70` 为准，API 跨模块报告分母不同。
