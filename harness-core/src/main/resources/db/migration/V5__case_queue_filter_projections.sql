ALTER TABLE platform_records ADD COLUMN risk_level VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN assignee VARCHAR(120) NOT NULL DEFAULT '';
CREATE INDEX platform_case_risk_queue ON platform_records
    (tenant_id, kind, risk_level, queue_priority, queue_due_at, created_at, record_id);
CREATE INDEX platform_case_assignee_queue ON platform_records
    (tenant_id, kind, assignee, queue_priority, queue_due_at, created_at, record_id);
