-- Harness Engineering Platform - Core Schema

-- 知识文档
CREATE TABLE IF NOT EXISTS knowledge_document (
    document_id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    category VARCHAR(64),
    content TEXT NOT NULL,
    tags TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 文档切片
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    chunk_id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES knowledge_document(document_id),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(32),
    category VARCHAR(64),
    tags TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chunk_document ON knowledge_chunk(document_id);

-- 结构化规则
CREATE TABLE IF NOT EXISTS rule_base (
    rule_id VARCHAR(64) PRIMARY KEY,
    rule_name VARCHAR(256) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    category VARCHAR(64),
    content TEXT NOT NULL,
    priority INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    tags TEXT,
    conditions JSONB,
    actions JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rule_type ON rule_base(rule_type);
CREATE INDEX idx_rule_category ON rule_base(category);

-- 历史案例
CREATE TABLE IF NOT EXISTS case_base (
    case_id VARCHAR(64) PRIMARY KEY,
    case_type VARCHAR(32),
    scenario VARCHAR(64),
    title VARCHAR(256),
    summary TEXT,
    input JSONB,
    expected_output JSONB,
    actual_output JSONB,
    expert_answer TEXT,
    tags TEXT,
    status VARCHAR(16) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_case_scenario ON case_base(scenario);

-- Prompt 模板
CREATE TABLE IF NOT EXISTS prompt_template (
    template_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    version INTEGER DEFAULT 1,
    scenario VARCHAR(64),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Workflow 模板
CREATE TABLE IF NOT EXISTS workflow_template (
    workflow_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    scenario VARCHAR(64) NOT NULL,
    description TEXT,
    version INTEGER DEFAULT 1,
    steps JSONB,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Workflow 执行记录
CREATE TABLE IF NOT EXISTS workflow_execution (
    execution_id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64),
    trace_id VARCHAR(64),
    scenario VARCHAR(64),
    input JSONB,
    output JSONB,
    status VARCHAR(16),
    steps JSONB,
    total_duration_ms BIGINT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);
CREATE INDEX idx_execution_trace ON workflow_execution(trace_id);
CREATE INDEX idx_execution_scenario ON workflow_execution(scenario);

-- 工具定义
CREATE TABLE IF NOT EXISTS tool_definition (
    tool_name VARCHAR(64) PRIMARY KEY,
    description TEXT,
    category VARCHAR(32),
    parameters JSONB,
    return_type VARCHAR(64),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 工具调用日志
CREATE TABLE IF NOT EXISTS tool_invocation_log (
    invocation_id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64),
    tool_name VARCHAR(64),
    parameters JSONB,
    invoked_at TIMESTAMP,
    duration_ms BIGINT,
    success BOOLEAN,
    error_message TEXT
);
CREATE INDEX idx_tool_log_trace ON tool_invocation_log(trace_id);

-- 评测 Case
CREATE TABLE IF NOT EXISTS eval_case (
    case_id VARCHAR(64) PRIMARY KEY,
    scenario VARCHAR(64),
    title VARCHAR(256),
    description TEXT,
    input JSONB,
    expected_output JSONB,
    expected_rule_ids TEXT,
    expected_tool_calls TEXT,
    expert_answer TEXT,
    tags TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 评测运行记录
CREATE TABLE IF NOT EXISTS eval_run (
    run_id VARCHAR(64) PRIMARY KEY,
    model_version VARCHAR(64),
    prompt_version VARCHAR(64),
    case_ids TEXT,
    status VARCHAR(16),
    total_cases INTEGER,
    completed_cases INTEGER,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

-- 评测结果
CREATE TABLE IF NOT EXISTS eval_result (
    result_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) REFERENCES eval_run(run_id),
    case_id VARCHAR(64),
    actual_output JSONB,
    rule_accuracy DOUBLE PRECISION,
    expert_alignment DOUBLE PRECISION,
    tool_execution_accuracy DOUBLE PRECISION,
    output_completeness DOUBLE PRECISION,
    hallucination_rate DOUBLE PRECISION,
    overall_score DOUBLE PRECISION,
    duration_ms BIGINT,
    error_message TEXT
);
CREATE INDEX idx_eval_result_run ON eval_result(run_id);

-- 链路追踪
CREATE TABLE IF NOT EXISTS trace_record (
    trace_id VARCHAR(64) PRIMARY KEY,
    scenario VARCHAR(64),
    input JSONB,
    output JSONB,
    spans JSONB,
    total_duration_ms BIGINT,
    total_tokens INTEGER,
    status VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 反馈记录
CREATE TABLE IF NOT EXISTS feedback_record (
    feedback_id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64),
    rating VARCHAR(16),
    correction TEXT,
    comment TEXT,
    submitted_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_feedback_trace ON feedback_record(trace_id);

-- 审计日志
CREATE TABLE IF NOT EXISTS audit_log (
    audit_id VARCHAR(64) PRIMARY KEY,
    action VARCHAR(64),
    operator VARCHAR(64),
    trace_id VARCHAR(64),
    details JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_trace ON audit_log(trace_id);
