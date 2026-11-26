CREATE INDEX platform_active_recovery ON platform_records
    (kind, record_status, tenant_id, record_id);
