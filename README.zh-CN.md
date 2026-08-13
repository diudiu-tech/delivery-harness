# Delivery Harness Engineering Platform

简体中文 | [English](README.md)

![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

面向即时配送运营场景的 AI Harness 参考实现。项目将确定性工作流、Mock 业务工具、轻量知识检索、OpenAI 兼容模型接口、评测脚手架和人工复核型安全检查组合在一起。

> [!IMPORTANT]
> 本仓库是教学与验证用途的 MVP，不是可直接投产的配送或赔付系统。业务工具使用合成数据，多数状态保存在内存中，模型输出仅供建议。请勿将应用直接暴露到公网、输入真实个人/订单数据，或在缺少鉴权、策略执行和人工审批的情况下执行赔付决策。

## 已实现能力

- 两条同步工作流：异常订单分析、赔付建议。
- 六个本地注册工具：订单、轨迹、ETA、运力、赔付规则和工单。
- 兼容 OpenAI Chat Completions 的客户端，以及按场景选择模型的路由。
- 内存文档导入、文本切片、关键词检索、规则库和案例库。
- 内存评测 Case、同步评测运行，以及基础规则/专家答案/工具调用评分。
- 请求 Trace ID、API 耗时日志、反馈存储和建议式输出检查。
- 本地 Ollama 配置、单元测试、Maven Wrapper 和 GitHub Actions CI。

以下能力**尚未实现**：自主 Agent 规划、真实业务工具集成、持久化存储、向量 Embedding/Milvus RAG、鉴权、限流、分布式追踪、自动执行赔付。详见[已知限制](#已知限制)。

## 架构

```mermaid
flowchart LR
    Client["REST 客户端"] --> Gateway["harness-gateway"]
    Gateway --> Agent["harness-agent\n固定工作流"]
    Agent --> Tools["harness-tool\nMock 业务工具"]
    Agent --> Knowledge["harness-knowledge\n内存检索"]
    Agent --> LLM["harness-llm\nOpenAI 兼容客户端"]
    LLM --> Model["Ollama 或兼容服务"]
    Gateway --> Eval["harness-eval"]
    Gateway --> Observe["harness-observe"]
    Eval --> Agent
```

所有工作流步骤都在 Gateway 进程内同步执行。除配置的模型服务外，默认应用不依赖其他外部服务。

## 模块说明

| 模块 | 实际职责 |
| --- | --- |
| `harness-common` | DTO、异常、JSON/文本工具和 Trace 上下文 |
| `harness-gateway` | 可执行的 Spring Boot REST 应用和请求日志 |
| `harness-agent` | 工作流注册表、两条固定工作流、输出格式化和建议式检查 |
| `harness-tool` | 确定性 Mock 工具和工具调用记录 |
| `harness-llm` | 模型路由、Prompt 注册、JSON 提取和 HTTP 客户端 |
| `harness-knowledge` | 内存文档、切片、规则、案例和关键词检索 |
| `harness-eval` | 内存 Case、同步评测运行和基础评分器 |
| `harness-observe` | 内存 Trace、指标、反馈和审计基础组件 |
| `llm-inference` | 固定版本的 Ollama 容器定义和冒烟测试脚本 |

`harness-gateway/src/main/resources/db/migration` 下的 SQL 是未来持久化方案的参考 Schema，当前内存实现不会执行这些脚本。

## 环境要求

- JDK 17 或更高版本
- Maven 3.6.3 或更高版本，也可使用仓库内置的 Maven Wrapper
- 仅在容器中运行 Ollama 时需要 Docker Compose
- 足够运行所选模型的内存；默认示例为 `qwen2.5:7b`

## 快速开始

### 1. 启动本地模型

如果本机已经运行 Ollama，可以跳过第一条命令。

```bash
docker compose up -d ollama
docker compose exec ollama ollama pull qwen2.5:7b
```

Compose 端口只绑定到 `127.0.0.1`。模型权重需要单独下载，不属于本仓库及其 MIT 许可证范围。

### 2. 构建与测试

```bash
./mvnw clean verify
```

### 3. 启动 API

```bash
./mvnw -pl harness-gateway -am package
java -jar harness-gateway/target/harness-gateway-0.1.0-SNAPSHOT.jar
```

API 默认监听 `http://localhost:8080`。

### 4. 调用工作流

异常订单分析：

```bash
curl --fail-with-body \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"order_id":"DEMO-001"}' \
  http://localhost:8080/api/v1/analyze/abnormal-order
```

赔付建议：

```bash
curl --fail-with-body \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"order_id":"DEMO-002","complaint_type":"OVERTIME"}' \
  http://localhost:8080/api/v1/analyze/compensation
```

所有响应使用统一封装：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "executionId": "...",
    "traceId": "...",
    "status": "SUCCESS",
    "steps": []
  },
  "traceId": "..."
}
```

同一个 Trace ID 也会写入 `X-Trace-Id` 响应头。

## API 一览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/v1/analyze/abnormal-order` | 执行异常订单分析 |
| `POST` | `/api/v1/analyze/compensation` | 生成赔付建议 |
| `POST` | `/api/v1/knowledge/ingest` | 导入并切分内存文档 |
| `POST` | `/api/v1/knowledge/search` | 检索内存规则、文档和案例 |
| `POST` | `/api/v1/eval/cases` | 添加内存评测 Case |
| `GET` | `/api/v1/eval/cases` | 查询评测 Case |
| `POST` | `/api/v1/eval/run` | 同步运行选定 Case |
| `GET` | `/api/v1/eval/run/{runId}` | 查询评测运行 |
| `GET` | `/api/v1/eval/run/{runId}/results` | 查询评测结果 |
| `GET` | `/api/v1/observe/trace/{traceId}` | 查询已记录的 Trace（如有数据） |
| `GET` | `/api/v1/observe/traces` | 查询 Trace 列表（如有数据） |
| `GET` | `/api/v1/observe/metrics` | 查询内存指标（如有数据） |
| `POST` | `/api/v1/observe/feedback` | 在内存中保存反馈 |
| `GET` | `/api/v1/observe/feedback` | 查询内存反馈 |

## 配置

如需本地配置参考，可复制 `.env.example`。Spring 会直接读取以下环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | 绑定地址；仅在有意隔离的远程/容器部署中显式设为 `0.0.0.0` |
| `SERVER_PORT` | `8080` | API 端口 |
| `HARNESS_LLM_ENDPOINT` | `http://localhost:11434` | OpenAI 兼容服务地址 |
| `HARNESS_LLM_MODEL` | `qwen2.5:7b` | 默认模型名 |
| `HARNESS_LLM_API_KEY` | 空 | 可选 Bearer Token，禁止提交真实值 |
| `HARNESS_LLM_TIMEOUT_SECONDS` | `120` | 模型读取超时秒数 |
| `HARNESS_MAX_COMPENSATION_AMOUNT` | `50.0` | 建议式赔付金额上限检查 |

安装模型后可运行冒烟测试：

```bash
HARNESS_LLM_MODEL=qwen2.5:7b ./llm-inference/smoke-test.sh
```

## 测试

```bash
./mvnw clean verify
```

当前共 30 项测试，覆盖文本切片边界、模型输出 JSON 提取、Guardrail、评测输入、Trace/错误语义，以及应用启动/API 行为。CI 会在每次 Push 和 Pull Request 上运行同样的 Maven 校验。

## 安全与数据处理

- 当前没有鉴权、授权、租户隔离和限流。
- 工具数据与地址均为合成示例；公开部署时请勿替换为真实个人数据。
- 导入的知识内容会影响 Prompt，应将知识导入视为 Prompt Injection 信任边界。
- 建议式检查器只识别少量短语和金额条件，不是完整策略引擎。
- 内存存储是无持久化、无容量治理的演示组件，重启后数据清空。
- 报告漏洞或部署衍生项目之前，请阅读 [SECURITY.md](SECURITY.md)。

## 已知限制

- 工具返回确定性 Mock 数据，不调用真实配送系统。
- 知识检索使用子串匹配和固定分数；向量检索类只是脚手架。
- 规则、文档、案例、评测、反馈、指标、Trace 和审计记录均未持久化。
- 评测评分器基于简单字符串匹配，未经统计校准。
- 工作流要求模型输出 JSON，但最终结果目前仍保留模型原始文本。
- Guardrail 会在工作流步骤和最终输出中返回通过/失败，但不能替代人工复核。
- PostgreSQL 参考 Schema 尚未连接 Repository。

## Roadmap

- 增加 API 鉴权、授权与请求配额。
- 用版本化 Adapter 和契约测试替换 Mock 工具。
- 通过显式 Profile 接入持久化 Repository 和 Migration。
- 实现 Embedding、向量检索和引用校验。
- 持久化 Trace、指标、反馈和审计事件，并增加保留策略。
- 引入类型化工作流输出、更强策略执行和敏感信息脱敏。
- 扩充评测集与经过校准的质量/安全指标。

## 参与贡献

欢迎贡献。请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，遵守[行为准则](CODE_OF_CONDUCT.md)，安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 许可证

源代码使用 [MIT License](LICENSE)。单独下载的模型权重和第三方服务遵循各自许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
