package db.migration;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.quality.KnowledgeService;
import com.delivery.harness.platform.store.RecordSummaries;
import com.fasterxml.jackson.core.type.TypeReference;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates one version-chain head per knowledge document and leaves a single current publication.
 * Older duplicate publications become SUPERSEDED with an audit event; their frozen content stays.
 */
public class V12__knowledge_document_heads extends BaseJavaMigration {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String ACTOR = "MIGRATION_V12";

    private record Version(String id, long number, boolean published, Map<String, Object> scope) {}

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        Map<String, Map<String, List<Version>>> tenants = new LinkedHashMap<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT tenant_id,record_id,payload FROM platform_records WHERE kind=?"
                                + " ORDER BY tenant_id,record_id")) {
            query.setString(1, KnowledgeService.KIND);
            query.setFetchSize(1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> data;
                    try {
                        data = JsonUtil.fromJson(rows.getString("payload"), MAP);
                    } catch (RuntimeException unreadable) {
                        // An unreadable version can be neither searched nor extended; leave it.
                        continue;
                    }
                    String recordId = rows.getString("record_id");
                    String documentId =
                            data.get("document_id") == null
                                    ? recordId
                                    : data.get("document_id").toString();
                    long number =
                            data.get("document_version") instanceof Number value
                                    ? value.longValue()
                                    : 1L;
                    Map<String, Object> scope = new LinkedHashMap<>();
                    scope.put("city_codes", data.get("city_codes"));
                    scope.put("station_ids", data.get("station_ids"));
                    tenants.computeIfAbsent(rows.getString("tenant_id"), t -> new LinkedHashMap<>())
                            .computeIfAbsent(documentId, d -> new ArrayList<>())
                            .add(
                                    new Version(
                                            recordId,
                                            number,
                                            "PUBLISHED".equals(data.get("state")),
                                            scope));
                }
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Map.Entry<String, Map<String, List<Version>>> tenant : tenants.entrySet()) {
            for (Map.Entry<String, List<Version>> document : tenant.getValue().entrySet()) {
                Version latest = null;
                Version current = null;
                for (Version version : document.getValue()) {
                    if (latest == null || version.number() > latest.number()) latest = version;
                    if (version.published()
                            && (current == null || version.number() > current.number()))
                        current = version;
                }
                for (Version version : document.getValue()) {
                    if (version.published() && version != current) {
                        supersede(connection, tenant.getKey(), version.id(), current.id(), now);
                    }
                }
                Map<String, Object> head =
                        KnowledgeService.documentHead(
                                document.getKey(),
                                latest.scope(),
                                latest.number(),
                                current == null ? null : current.id());
                insertHead(connection, tenant.getKey(), document.getKey(), head, now);
            }
        }
    }

    private static void supersede(
            Connection connection,
            String tenant,
            String recordId,
            String currentId,
            OffsetDateTime now)
            throws SQLException {
        Map<String, Object> data;
        long version;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT record_version,payload FROM platform_records WHERE tenant_id=? AND"
                                + " kind=? AND record_id=?")) {
            query.setString(1, tenant);
            query.setString(2, KnowledgeService.KIND);
            query.setString(3, recordId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                version = rows.getLong("record_version");
                data = JsonUtil.fromJson(rows.getString("payload"), MAP);
            }
        }
        data.put("state", "SUPERSEDED");
        data.put("superseded_by", ACTOR);
        data.put("superseded_at", now.toInstant().toString());
        data.put("superseded_by_version", currentId);
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE platform_records SET record_version=?,updated_at=?,payload=?,"
                                + "summary_payload=?,record_state=? WHERE tenant_id=? AND kind=?"
                                + " AND record_id=?")) {
            update.setLong(1, Math.addExact(version, 1L));
            update.setObject(2, now);
            update.setString(3, JsonUtil.toJson(data));
            update.setString(
                    4, JsonUtil.toJson(RecordSummaries.of(KnowledgeService.KIND, data)));
            update.setString(5, "SUPERSEDED");
            update.setString(6, tenant);
            update.setString(7, KnowledgeService.KIND);
            update.setString(8, recordId);
            update.executeUpdate();
        }
        audit(
                connection,
                tenant,
                "KNOWLEDGE_SUPERSEDED",
                recordId,
                Map.of(
                        "kind",
                        KnowledgeService.KIND,
                        "before_version",
                        version,
                        "after_version",
                        Math.addExact(version, 1L),
                        "superseded_by_version",
                        currentId),
                now);
    }

    private static void insertHead(
            Connection connection,
            String tenant,
            String documentId,
            Map<String, Object> head,
            OffsetDateTime now)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO platform_records (tenant_id,kind,record_id,record_version,"
                                + "created_at,updated_at,payload,summary_payload) VALUES"
                                + " (?,?,?,1,?,?,?,?)")) {
            insert.setString(1, tenant);
            insert.setString(2, KnowledgeService.DOCUMENT);
            insert.setString(3, documentId);
            insert.setObject(4, now);
            insert.setObject(5, now);
            insert.setString(6, JsonUtil.toJson(head));
            insert.setString(
                    7, JsonUtil.toJson(RecordSummaries.of(KnowledgeService.DOCUMENT, head)));
            insert.executeUpdate();
        }
        audit(
                connection,
                tenant,
                "KNOWLEDGE_DOCUMENT_CREATED",
                documentId,
                Map.of("kind", KnowledgeService.DOCUMENT, "after_version", 1),
                now);
    }

    private static void audit(
            Connection connection,
            String tenant,
            String action,
            String resourceId,
            Map<String, Object> details,
            OffsetDateTime now)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO platform_audit_events (event_id,tenant_id,actor_id,action,"
                                + "resource_id,created_at,payload) VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, tenant);
            insert.setString(3, ACTOR);
            insert.setString(4, action);
            insert.setString(5, resourceId);
            insert.setObject(6, now);
            insert.setString(7, JsonUtil.toJson(details));
            insert.executeUpdate();
        }
    }
}
