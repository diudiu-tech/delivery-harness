CREATE TABLE platform_records (
    tenant_id VARCHAR(120) NOT NULL,
    kind VARCHAR(80) NOT NULL,
    record_id VARCHAR(160) NOT NULL,
    record_version BIGINT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (tenant_id, kind, record_id)
);
CREATE INDEX platform_records_kind_time ON platform_records (tenant_id, kind, created_at);
CREATE TABLE platform_audit_events (
    event_id VARCHAR(160) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    action VARCHAR(120) NOT NULL,
    resource_id VARCHAR(160) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX platform_audit_tenant_time ON platform_audit_events (tenant_id, created_at);
