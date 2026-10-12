# 部署、恢复与试点运行手册

本手册对应[PRD](../prd/delivery-harness-product-prd.md)和[v2 API](../api/v2.md)。代码具备可测试的演示与集成契约；真实政策签字、身份管理、渠道认证、备份目标和业务效果实验必须由试点环境提供实际证据。

## 演示启动与完整流程

```bash
# 使用 JDK 17；本项目不要求测试时运行 Ollama。
./mvnw clean verify
cp .env.example .env
set -a
source .env
set +a
# 将构建版本写入评测，未设置时如实为 UNKNOWN。
export HARNESS_BUILD_GIT_SHA=$(git rev-parse HEAD)
java -jar harness-api/target/harness-api.jar
```

访问 `http://127.0.0.1:8080/workbench/`。DEMO-001～006 是合成场景，页面显示演示标记；默认 runtime-default-v1 是确定性规则/模板，无需模型。新建 DEMO-002 的 OVERTIME 案件，生成赔付建议，检查时间轴、政策和权威金额，再领取复核；20元高风险需主管角色。批准后支付仍为尚未执行。使用“设置访问凭证”输入 `.env.example` 的 demo-reviewer 可完成材料、例外、政策/配置的独立核验；令牌只存在页面内存，刷新后重新输入。

新建 DAMAGED 案件，上传正文和损坏等级，切换独立身份核验，然后重新执行才会形成已核验事实。补证使旧审批失效；不要在材料与决定变化后复用旧审批版本。新配置先建立候选、运行带 amount/currency/decision_status 标签的评测，再由另一用户发布；策略回滚通过已验证旧配置路由或发布新路由完成，历史记录不覆盖。

默认 demo H2 内存库随进程结束清空。验证持久恢复可使用 `HARNESS_DATABASE_URL='jdbc:h2:file:./data/harness;DB_CLOSE_ON_EXIT=FALSE'`。数据目录已 gitignore。H2 文件不要被多个 JVM 同时打开。生产试点使用团队维护的 PostgreSQL，并在真实 PostgreSQL 版本上执行 Migration/恢复/并发验收。

## pilot 的启动门禁

1. 配置 `HARNESS_MODE=pilot`、非内存 JDBC URL、用户/密码、服务器 JSON token identities、真实只读订单 endpoint 和其 allowed-hosts。任何关键配置缺失启动失败。
2. 身份为独立随机访问凭证并绑定 tenant_id/user_id/roles/city_codes/station_ids。角色是动作权限，scope 是数据范围，不能只配 ADMIN 代替业务角色。使用可信网关提供 TLS、凭证发放/轮换/撤销、操作留痕。所有 API 请求验证身份；pilot v1 返回 410，不能回到合成工具。
3. 知识、Prompt 和冻结内容禁止明显个人信息格式；先脱敏，不能仅忽略预览警示。此规则检查不能代替对自然语言资料的人工审查。pilot 不自动播种示例政策、知识或评测。策略编辑者导入真实已授权政策，另一发布者校验发布；冻结已脱敏数据集，评测通过再独立发布 runtime。首先使用 deterministic_only=true 做影子验证。
4. 使用模型时显式设置 `HARNESS_LLM_ALLOWED_HOSTS` 与 HARNESS_LLM_ENDPOINT；HTTPS，loopback 测试可 HTTP，禁止重定向/URL用户信息/fragment。端点未许可返回明确拒绝，不能绕过成用户可注入 URL。密钥通过环境密文管理，模型请求使用允许字段。
5. HARNESS_EXECUTION_WORKERS/QUEUE/DEADLINE_SECONDS 默认 2/50/15。总预算包括排队、步骤、模型重试；线上与评测资源分开。不要把高并发参数当作容量承诺。部署限制 DB 连接数和存储容量，监控队列和失败率。

应用默认仅监听 127.0.0.1。扩大绑定地址必须经过可信网关，不能直接把 demo 匿名接口作为线上应用。配置数据来源、本地时区、字段 schema、响应最大长度与错误分类；真实源不可用明确失败，不回退 Fixture。

## 真实订单只读 Adapter 契约

`HARNESS_ORDER_ENDPOINT=https://orders.example.internal/readonly/orders/{order_id}`，`HARNESS_ORDER_ALLOWED_HOSTS=orders.example.internal`。请求 GET，带 X-Tenant-ID，配置后带 Authorization: Bearer，无重定向。返回示例：

```json
{
  "schema_version": "1",
  "source_version": "orders-api-2026-09",
  "collected_at": "2026-09-30T08:00:00Z",
  "source_timezone": "Asia/Shanghai",
  "currency": "CNY",
  "order": {
    "orderId": "ORD-1001", "cityCode": "SHA", "stationId": "ST-01",
    "status": "DELIVERED", "orderAmount": 40.00,
    "createTime": "2026-09-30T12:00:00+08:00",
    "acceptTime": "2026-09-30T12:01:00+08:00",
    "arriveShopTime": "2026-09-30T12:06:00+08:00",
    "pickupTime": "2026-09-30T12:12:00+08:00",
    "expectedDeliveryTime": "2026-09-30T12:30:00+08:00",
    "deliveryTime": "2026-09-30T13:05:00+08:00"
  }
}
```

订单时间可提供带 offset 的 ISO 字符串或由 source_timezone 解释的 local 时间，统一到上海时间；保存采集时间、源时区和原始证据。金额精确到分、非负，订单/组织字段完整，订单 ID 与查询一致。缺少阶段端点仍保留 unknown，逆序不能伪装成正常。若订单城市/站点在执行期间改变，拒绝旧案件继续分析，重新建案核实范围。该契约目前仅订单事实；缺少历史 ETA/运力时明确 HISTORY_UNAVAILABLE，当前快照不能作为历史责任证明。

HTTP 404→NOT_FOUND、401/403→UNAUTHORIZED、429→RATE_LIMITED、超时→504，schema/金额/时区漂移→INVALID_DATA。上线前用脱敏固定响应完成双方契约测试，并确认 orderAmount 的业务含义；不可将其直接解释成财务可退余额。

## 故障、恢复和备份

| 情况 | 应对 |
| --- | --- |
| 模型不可用/结构或引用错 | 确定性决定仍可用，DEGRADED；话术草稿不能复制当核验说明；可关闭模型 |
| 订单源失败 | 明确错误，不造替代订单；查看执行和步骤，换新幂等键发起已授权重试 |
| worker 中断 | 持久任务租约过期后恢复；fence 阻止迟到线程覆盖取消/超时/人工版本 |
| 服务重启中断评测 | 旧 run 标记 PROCESS_RESTARTED，快照保留；创建新 run 重试，不覆盖结果 |
| 审批审计写失败 | 同一事务回滚，不宣告批准；DB恢复后按新读版本重试 |
| 政策/资料撤回 | 新运行不可使用；历史决定/评测使用冻结内容核对 |
| 资金超时 | 台账 UNKNOWN，只查单和对账，不能用新业务 ID 再支付 |

PostgreSQL 由现有 DBA 备份工具做一致性备份；同时保存应用构建版本、Flyway history 和密钥管理引用。密钥不要写入数据库导出或仓库。恢复到隔离数据库后先以 deterministic 模式且资金关闭启动，核对 case.current_decision_id、review、evidence_snapshot_id 与租户一致；执行队列与台账查询恢复后再开放读流量。RPO/RTO 没有真实演练数据前记录“待验证”，不能引用 PRD 目标作为达成值。

留存默认不删除。配置 HARNESS_RETENTION_CASE_DAYS（默认180）后可生成保留图 dry-run；开放 HARNESS_RETENTION_ENABLED 仍需另一 ADMIN 审批 graph_hash。正在处理、保留锁、财务台账和评测快照引用均阻止删除；references/hash/version 变化重新计划。业务审计只保存摘要、主体和删除回执。被资金或评测引用的长期数据需负责人审核专门脱敏/退役迁移，不自动删孤立外键。不要清空 DB 以“解决”空间告警。

## 独立资金通道验收

默认 HARNESS_COMPENSATION_EXECUTION_ENABLED=false。启用真实渠道要求 allowlisted HTTPS、token、至少32字符 callback secret、FUNDS与PAYMENT_CALLBACK独立身份。测试 demo channel 只标记 synthetic=true，不移动真实资金。

渠道提供以下三个接口并明确承诺原子 eligibility version 检查、业务幂等与可靠查单：

- GET `/orders/{order_id}/eligibility`，X-Tenant-ID。返回 order_id,currency=CNY,order_status、remaining_refund、remaining_compensation、version、observed_at，以及 idempotent_submit=true、query_supported=true。单笔、累计上限和当前未决预留都不能超额度；eligibility 最多180秒。
- POST `/compensations`：compensation_id,order_id,amount,currency,method,decision_id,decision_version,eligibility_version,approval_hash。Idempotency-Key 为 tenant_id:compensation_id；X-Tenant-ID 提供租户。提供方必须原子验证余额和 eligibility_version，幂等重复返回原结果。
- GET `/compensations/{compensation_id}` 查询真实渠道结果，返回 compensation_id,amount,currency,status,external_id。超时、ID/金额不符保留 UNKNOWN。应用不能推断“超时就是失败”。

回调按 API 文档 HMAC 方案验签；重复、乱序、过期和跨租户事件必须用故障注入验证。PENDING 与案件/audit 原子提交构成 outbox；每10秒恢复扫描，SUBMITTED/UNKNOWN最少60秒间隔查单。恢复不生成新业务补偿号。无法确定终态时保持案件资金锁，由资金运营对账；关闭执行并重启可停止新自动写，已提交结果继续通过只读查单处理。

没有真实渠道授权、可退余额契约和重复/乱序/故障演练，资金扩展不能开放。真实调用验收、数据管理签字、标注样本和影子质量实验是外部上线条件，已编写代码与本地测试不替代这些条件。
