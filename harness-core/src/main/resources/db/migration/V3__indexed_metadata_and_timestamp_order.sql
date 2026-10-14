ALTER TABLE platform_records ALTER COLUMN created_at TYPE TIMESTAMP(6) WITH TIME ZONE
    USING CAST(created_at AS TIMESTAMP(6) WITH TIME ZONE);
ALTER TABLE platform_records ALTER COLUMN updated_at TYPE TIMESTAMP(6) WITH TIME ZONE
    USING CAST(updated_at AS TIMESTAMP(6) WITH TIME ZONE);
ALTER TABLE platform_records ALTER COLUMN queue_due_at TYPE TIMESTAMP(6) WITH TIME ZONE
    USING CAST(queue_due_at AS TIMESTAMP(6) WITH TIME ZONE);
ALTER TABLE platform_audit_events ALTER COLUMN created_at TYPE TIMESTAMP(6) WITH TIME ZONE
    USING CAST(created_at AS TIMESTAMP(6) WITH TIME ZONE);
ALTER TABLE platform_records ADD COLUMN case_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN order_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN complaint_type VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN record_status VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE platform_records ADD COLUMN record_state VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE platform_audit_events ADD COLUMN case_id VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE platform_audit_events ADD COLUMN scope_city VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE platform_audit_events ADD COLUMN scope_station VARCHAR(120) NOT NULL DEFAULT '';
CREATE INDEX platform_case_children ON platform_records (tenant_id, kind, case_id, record_id);
CREATE INDEX platform_status_page ON platform_records (tenant_id, kind, record_status, record_id);
CREATE INDEX platform_state_page ON platform_records (tenant_id, kind, record_state, record_id);
CREATE INDEX platform_order_cases ON platform_records (tenant_id, kind, order_id, complaint_type, record_id);
CREATE INDEX platform_audit_cursor ON platform_audit_events (tenant_id, created_at, event_id);
CREATE INDEX platform_audit_scope_cursor ON platform_audit_events
    (tenant_id, scope_city, scope_station, created_at, event_id);
CREATE TABLE platform_material_content (
    tenant_id VARCHAR(120) NOT NULL,
    material_id VARCHAR(160) NOT NULL,
    content_base64 TEXT NOT NULL,
    PRIMARY KEY (tenant_id, material_id)
);
