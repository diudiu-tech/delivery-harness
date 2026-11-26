ALTER TABLE platform_records ADD COLUMN scope_city VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN scope_station VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN queue_priority INTEGER NOT NULL DEFAULT 1;
ALTER TABLE platform_records ADD COLUMN queue_due_at VARCHAR(40) NOT NULL DEFAULT '9999-01-01T00:00:00Z';
CREATE INDEX platform_case_queue ON platform_records (tenant_id, kind, queue_priority, queue_due_at, created_at, record_id);
CREATE INDEX platform_record_scope ON platform_records (tenant_id, kind, scope_city, scope_station);
