package com.delivery.harness.platform.store;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.common.util.TraceUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

/** Durable metadata, bounded indexed queries, and transactionally appended audit events. */
@Service
public class PlatformStore {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformStore.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int INTERNAL_LIMIT = 1000;
    private final DataSource source;

    public PlatformStore(DataSource source) {
        this.source = source;
    }

    public record Entity(
            String id,
            String tenantId,
            String kind,
            long version,
            String createdAt,
            String updatedAt,
            Map<String, Object> data) {}

    public record Page(List<Entity> items, String cursor) {}

    public record AuditPage(List<Map<String, Object>> items, String cursor) {}

    public Optional<Entity> get(String tenant, String kind, String id) {
        return read(tx -> tx.get(tenant, kind, id));
    }

    public Entity require(String tenant, String kind, String id) {
        return read(tx -> tx.require(tenant, kind, id));
    }

    /** Compatibility helper for internal callers; never fetches more than 1000 records. */
    public List<Entity> list(String tenant, String kind) {
        return query(tenant, kind, null, Set.of(), null, "", INTERNAL_LIMIT);
    }

    public Entity create(
            String tenant,
            String kind,
            String id,
            Map<String, Object> data,
            String actor,
            String action) {
        return transact(tx -> tx.create(tenant, kind, id, data, actor, action));
    }

    public Entity update(
            String tenant,
            String kind,
            String id,
            long expectedVersion,
            Map<String, Object> data,
            String actor,
            String action) {
        return transact(tx -> tx.update(tenant, kind, id, expectedVersion, data, actor, action));
    }

    private <T> T read(Function<Tx, T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            return work.apply(new Tx(connection));
        } catch (SQLException failure) {
            throw databaseFailure(failure);
        }
    }

    public <T> T transact(Function<Tx, T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(new Tx(connection));
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    logDatabaseFailure(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw databaseFailure(failure);
        }
    }

    public List<Entity> page(String tenant, String kind, String cursor, int limit) {
        validateLimit(limit, 100);
        return query(tenant, kind, null, Set.of(), null, cursor, limit);
    }

    public List<Entity> query(
            String tenant,
            String kind,
            String caseId,
            Set<String> statuses,
            String state,
            String cursor,
            int limit) {
        validateLimit(limit, INTERNAL_LIMIT);
        return read(tx -> tx.query(tenant, kind, caseId, statuses, state, cursor, limit));
    }

    public List<Entity> caseRecords(String tenant, String kind, String caseId, int limit) {
        return query(tenant, kind, caseId, Set.of(), null, "", limit);
    }

    public Page scopedPage(
            RecordScope actor,
            String kind,
            String caseId,
            Set<String> statuses,
            String state,
            String cursor,
            int limit) {
        validateLimit(limit, 100);
        return read(
                tx -> {
                    List<Object> values = new ArrayList<>();
                    StringBuilder sql =
                            recordQuery(
                                    actor.tenantId(),
                                    kind,
                                    caseId,
                                    statuses,
                                    state,
                                    cursor,
                                    values);
                    appendScope(sql, values, "r.scope_city", actor.cityCodes());
                    appendScope(sql, values, "r.scope_station", actor.stationIds());
                    sql.append(" ORDER BY r.record_id LIMIT ?");
                    values.add(Math.addExact(limit, 1));
                    List<Entity> records = tx.select(sql.toString(), values);
                    boolean more = records.size() > limit;
                    if (more) {
                        records.remove(records.size() - 1);
                    }
                    String next = more ? records.get(records.size() - 1).id() : "";
                    return new Page(List.copyOf(records), next);
                });
    }

    public long countScoped(RecordScope actor, String kind, String status) {
        return read(
                tx -> {
                    List<Object> values = new ArrayList<>(List.of(actor.tenantId(), kind));
                    StringBuilder sql =
                            new StringBuilder(
                                    "SELECT COUNT(*) FROM platform_records WHERE tenant_id=? AND"
                                            + " kind=?");
                    appendScope(sql, values, "scope_city", actor.cityCodes());
                    appendScope(sql, values, "scope_station", actor.stationIds());
                    if (status != null) {
                        sql.append(" AND record_status=?");
                        values.add(status);
                    }
                    try (PreparedStatement query = tx.connection.prepareStatement(sql.toString())) {
                        bind(query, values);
                        try (ResultSet rows = query.executeQuery()) {
                            rows.next();
                            return rows.getLong(1);
                        }
                    } catch (SQLException failure) {
                        throw databaseFailure(failure);
                    }
                });
    }

    public List<Entity> scopedRecent(RecordScope actor, String kind, int limit) {
        validateLimit(limit, INTERNAL_LIMIT);
        return read(
                tx -> {
                    List<Object> values = new ArrayList<>(List.of(actor.tenantId(), kind));
                    StringBuilder sql =
                            new StringBuilder(
                                    "SELECT * FROM platform_records WHERE tenant_id=? AND kind=?");
                    appendScope(sql, values, "scope_city", actor.cityCodes());
                    appendScope(sql, values, "scope_station", actor.stationIds());
                    sql.append(" ORDER BY created_at DESC,record_id DESC LIMIT ?");
                    values.add(limit);
                    return tx.select(sql.toString(), values);
                });
    }

    public Page caseQueue(RecordScope actor, String cursor, int limit) {
        return caseQueue(actor, CaseFilters.none(), cursor, limit);
    }

    public Page caseQueue(RecordScope actor, CaseFilters filters, String cursor, int limit) {
        validateLimit(limit, 100);
        return read(
                tx -> {
                    List<Object> values = new ArrayList<>(List.of(actor.tenantId()));
                    StringBuilder sql =
                            new StringBuilder(
                                    "SELECT * FROM platform_records WHERE tenant_id=? AND"
                                            + " kind='case'");
                    appendScope(sql, values, "scope_city", actor.cityCodes());
                    appendScope(sql, values, "scope_station", actor.stationIds());
                    appendFilter(sql, values, "record_status", filters.status());
                    appendFilter(sql, values, "order_id", filters.orderId());
                    appendFilter(sql, values, "risk_level", filters.riskLevel());
                    appendFilter(sql, values, "complaint_type", filters.complaintType());
                    appendFilter(sql, values, "assignee", filters.assignee());
                    appendFilter(sql, values, "scope_city", filters.cityCode());
                    appendFilter(sql, values, "scope_station", filters.stationId());
                    if (cursor != null && !cursor.isBlank()) {
                        Map<String, Object> after = decodeCursor(cursor);
                        if (!(after.get("priority") instanceof Number priority)) {
                            throw PlatformException.invalid("Invalid case queue cursor");
                        }
                        OffsetDateTime due = cursorTime(after, "due");
                        OffsetDateTime created = cursorTime(after, "created");
                        String id = cursorText(after, "id");
                        int rank = priority.intValue();
                        sql.append(
                                        " AND (queue_priority>? OR (queue_priority=? AND"
                                                + " queue_due_at>?)")
                                .append(
                                        " OR (queue_priority=? AND queue_due_at=? AND"
                                                + " created_at>?)")
                                .append(
                                        " OR (queue_priority=? AND queue_due_at=? AND created_at=?"
                                                + " AND record_id>?))");
                        values.addAll(
                                List.of(
                                        rank, rank, due, rank, due, created, rank, due, created,
                                        id));
                    }
                    sql.append(
                            " ORDER BY queue_priority,queue_due_at,created_at,record_id LIMIT ?");
                    values.add(Math.addExact(limit, 1));
                    List<Entity> records = tx.select(sql.toString(), values);
                    boolean more = records.size() > limit;
                    if (more) {
                        records.remove(records.size() - 1);
                    }
                    String next = "";
                    if (more) {
                        Entity last = records.get(records.size() - 1);
                        next =
                                encodeCursor(
                                        Map.of(
                                                "priority",
                                                priority(last.data()),
                                                "due",
                                                due(last.data()).toInstant(),
                                                "created",
                                                last.createdAt(),
                                                "id",
                                                last.id()));
                    }
                    return new Page(List.copyOf(records), next);
                });
    }

    private static void appendFilter(
            StringBuilder sql, List<Object> values, String column, String value) {
        if (!value.isEmpty()) {
            sql.append(" AND ").append(column).append("=?");
            values.add(value);
        }
    }

    /** Internal recovery scan across tenants; each call reads at most one bounded active page. */
    public Page activePage(String kind, Set<String> statuses, String cursor, int limit) {
        validateLimit(limit, 100);
        if (statuses == null || statuses.isEmpty() || statuses.size() > 10) {
            throw PlatformException.invalid("Recovery requires 1..10 explicit statuses");
        }
        return read(
                tx -> {
                    List<Object> values = new ArrayList<>(List.of(kind));
                    StringBuilder sql =
                            new StringBuilder("SELECT * FROM platform_records WHERE kind=?");
                    sql.append(" AND record_status IN (")
                            .append(String.join(",", Collections.nCopies(statuses.size(), "?")))
                            .append(")");
                    values.addAll(new TreeSet<>(statuses));
                    if (cursor != null && !cursor.isBlank()) {
                        Map<String, Object> after = decodeCursor(cursor);
                        String tenant = cursorText(after, "tenant");
                        sql.append(" AND (tenant_id>? OR (tenant_id=? AND record_id>?))");
                        values.addAll(List.of(tenant, tenant, cursorText(after, "id")));
                    }
                    sql.append(" ORDER BY tenant_id,record_id LIMIT ?");
                    values.add(Math.addExact(limit, 1));
                    List<Entity> rows = tx.select(sql.toString(), values);
                    boolean more = rows.size() > limit;
                    if (more) rows.remove(rows.size() - 1);
                    String next = "";
                    if (more) {
                        Entity last = rows.get(rows.size() - 1);
                        next = encodeCursor(Map.of("tenant", last.tenantId(), "id", last.id()));
                    }
                    return new Page(List.copyOf(rows), next);
                });
    }

    public Set<String> tenants() {
        return read(
                tx -> {
                    try (PreparedStatement query =
                                    tx.connection.prepareStatement(
                                            "SELECT DISTINCT tenant_id FROM platform_records ORDER"
                                                    + " BY tenant_id LIMIT 1000");
                            ResultSet rows = query.executeQuery()) {
                        Set<String> tenants = new HashSet<>();
                        while (rows.next()) {
                            tenants.add(rows.getString(1));
                        }
                        return tenants;
                    } catch (SQLException failure) {
                        throw databaseFailure(failure);
                    }
                });
    }

    public AuditPage auditPage(RecordScope actor, String cursor, int limit) {
        validateLimit(limit, 100);
        return read(tx -> tx.auditPage(actor, cursor, limit));
    }

    public List<Map<String, Object>> audits(String tenant) {
        return read(tx -> tx.auditEvents(tenant));
    }

    public boolean hasMaterialContent(String tenant, String materialId) {
        return read(tx -> tx.hasMaterialContent(tenant, materialId));
    }

    public byte[] materialContent(String tenant, String materialId) {
        return read(tx -> tx.materialContent(tenant, materialId));
    }

    public final class Tx {
        private final Connection connection;

        private Tx(Connection connection) {
            this.connection = connection;
        }

        public Optional<Entity> get(String tenant, String kind, String id) {
            return lookup(tenant, kind, id, false);
        }

        public Optional<Entity> getForUpdate(String tenant, String kind, String id) {
            return lookup(tenant, kind, id, true);
        }

        private Optional<Entity> lookup(String tenant, String kind, String id, boolean lock) {
            String sql =
                    "SELECT * FROM platform_records WHERE tenant_id=? AND kind=? AND record_id=?";
            List<Entity> records =
                    select(sql + (lock ? " FOR UPDATE" : ""), List.of(tenant, kind, id));
            return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
        }

        public Entity require(String tenant, String kind, String id) {
            return get(tenant, kind, id).orElseThrow(() -> missing(kind));
        }

        public Entity requireForUpdate(String tenant, String kind, String id) {
            return getForUpdate(tenant, kind, id).orElseThrow(() -> missing(kind));
        }

        public Entity lock(String tenant, String kind, String id) {
            return requireForUpdate(tenant, kind, id);
        }

        public List<Entity> list(String tenant, String kind) {
            return query(tenant, kind, null, Set.of(), null, "", INTERNAL_LIMIT);
        }

        public List<Entity> page(String tenant, String kind, String cursor, int limit) {
            validateLimit(limit, 100);
            return query(tenant, kind, null, Set.of(), null, cursor, limit);
        }

        public List<Entity> query(
                String tenant,
                String kind,
                String caseId,
                Set<String> statuses,
                String state,
                String cursor,
                int limit) {
            validateLimit(limit, INTERNAL_LIMIT);
            List<Object> values = new ArrayList<>();
            StringBuilder sql = recordQuery(tenant, kind, caseId, statuses, state, cursor, values);
            sql.append(" ORDER BY r.record_id LIMIT ?");
            values.add(limit);
            return select(sql.toString(), values);
        }

        public List<Entity> caseRecords(String tenant, String kind, String caseId, int limit) {
            return query(tenant, kind, caseId, Set.of(), null, "", limit);
        }

        public List<Entity> orderCases(String tenant, String orderId, String complaint, int limit) {
            validateLimit(limit, INTERNAL_LIMIT);
            return select(
                    "SELECT * FROM platform_records WHERE tenant_id=? AND kind='case'"
                            + " AND order_id=? AND complaint_type=? ORDER BY record_id LIMIT ?",
                    List.of(tenant, orderId, complaint, limit));
        }

        private List<Entity> select(String sql, List<Object> values) {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                bind(query, values);
                try (ResultSet rows = query.executeQuery()) {
                    List<Entity> records = new ArrayList<>();
                    while (rows.next()) {
                        records.add(entity(rows));
                    }
                    return records;
                }
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        public Entity create(
                String tenant,
                String kind,
                String id,
                Map<String, Object> data,
                String actor,
                String action) {
            rejectInlineBody(data);
            OffsetDateTime now = now();
            String sql =
                    "INSERT INTO platform_records"
                        + " (tenant_id,kind,record_id,record_version,created_at,updated_at,payload,"
                        + " scope_city, scope_station, queue_priority, queue_due_at, case_id,"
                        + " order_id, complaint_type, record_status, record_state, risk_level,"
                        + " assignee) VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenant);
                statement.setString(2, kind);
                statement.setString(3, id);
                statement.setObject(4, now);
                statement.setObject(5, now);
                statement.setString(6, JsonUtil.toJson(data));
                project(statement, 7, tenant, kind, id, data);
                statement.executeUpdate();
                audit(
                        tenant,
                        actor,
                        action,
                        id,
                        Map.of("kind", kind, "after_version", 1, "after_hash", hash(data)));
                return require(tenant, kind, id);
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        public Entity update(
                String tenant,
                String kind,
                String id,
                long expectedVersion,
                Map<String, Object> data,
                String actor,
                String action) {
            rejectInlineBody(data);
            if (Set.of("decision", "order_snapshot", "review_action").contains(kind)) {
                throw PlatformException.conflict(
                        "Completed snapshots are immutable; create a new version");
            }
            Entity before = require(tenant, kind, id);
            if ("runtime_config".equals(kind)) {
                validateRuntimePublication(before, data);
            }
            if (before.version() != expectedVersion) {
                throw PlatformException.conflict("Record changed; refresh the current version");
            }
            long nextVersion = Math.addExact(expectedVersion, 1);
            String sql =
                    "UPDATE platform_records SET"
                        + " record_version=?,updated_at=?,payload=?,scope_city=?,"
                        + "scope_station=?,queue_priority=?,queue_due_at=?,case_id=?,order_id=?,"
                        + "complaint_type=?,record_status=?,record_state=?,risk_level=?,assignee=?"
                        + " WHERE tenant_id=? AND kind=? AND record_id=? AND record_version=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, nextVersion);
                statement.setObject(2, now());
                statement.setString(3, JsonUtil.toJson(data));
                project(statement, 4, tenant, kind, id, data);
                statement.setString(15, tenant);
                statement.setString(16, kind);
                statement.setString(17, id);
                statement.setLong(18, expectedVersion);
                if (statement.executeUpdate() != 1) {
                    throw PlatformException.conflict(
                            "Concurrent update; refresh the current version");
                }
                audit(
                        tenant,
                        actor,
                        action,
                        id,
                        Map.of(
                                "kind",
                                kind,
                                "before_version",
                                expectedVersion,
                                "after_version",
                                nextVersion,
                                "before_hash",
                                hash(before.data()),
                                "after_hash",
                                hash(data)));
                return require(tenant, kind, id);
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        private void project(
                PreparedStatement statement,
                int first,
                String tenant,
                String kind,
                String id,
                Map<String, Object> data)
                throws SQLException {
            String caseId = "case".equals(kind) ? id : text(data, "case_id");
            Map<String, Object> parent =
                    caseId.isEmpty() || "case".equals(kind)
                            ? Map.of()
                            : get(tenant, "case", caseId).map(Entity::data).orElse(Map.of());
            statement.setString(first, textOr(data, parent, "city_code"));
            statement.setString(first + 1, textOr(data, parent, "station_id"));
            statement.setInt(first + 2, priority(data));
            statement.setObject(first + 3, due(data));
            statement.setString(first + 4, caseId);
            statement.setString(first + 5, textOr(data, parent, "order_id"));
            statement.setString(first + 6, textOr(data, parent, "complaint_type"));
            statement.setString(first + 7, text(data, "status"));
            statement.setString(first + 8, text(data, "state"));
            statement.setString(first + 9, text(data, "risk_level"));
            statement.setString(first + 10, text(data, "assignee"));
        }

        public void putMaterialContent(
                String tenant, String materialId, byte[] bytes, String actor) {
            require(tenant, "material", materialId);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO platform_material_content"
                                    + " (tenant_id,material_id,content_base64) VALUES (?,?,?)")) {
                statement.setString(1, tenant);
                statement.setString(2, materialId);
                statement.setString(3, Base64.getEncoder().encodeToString(bytes));
                statement.executeUpdate();
                audit(
                        tenant,
                        actor,
                        "MATERIAL_CONTENT_FROZEN",
                        materialId,
                        Map.of("size_bytes", bytes.length, "content_hash", sha256(bytes)));
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        public boolean hasMaterialContent(String tenant, String materialId) {
            try (PreparedStatement query =
                    connection.prepareStatement(
                            "SELECT 1 FROM platform_material_content WHERE tenant_id=? AND"
                                    + " material_id=?")) {
                query.setString(1, tenant);
                query.setString(2, materialId);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next();
                }
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        public byte[] materialContent(String tenant, String materialId) {
            try (PreparedStatement query =
                    connection.prepareStatement(
                            "SELECT content_base64 FROM platform_material_content WHERE tenant_id=?"
                                    + " AND material_id=?")) {
                query.setString(1, tenant);
                query.setString(2, materialId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new PlatformException(
                                409,
                                "MATERIAL_CONTENT_NOT_FROZEN",
                                "Material has no frozen content");
                    }
                    return Base64.getDecoder().decode(rows.getString(1));
                }
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        public void audit(
                String tenant,
                String actor,
                String action,
                String resourceId,
                Map<String, Object> details) {
            String sql =
                    "INSERT INTO platform_audit_events"
                            + " (event_id,tenant_id,actor_id,action,resource_id,created_at,payload,"
                            + "case_id,scope_city,scope_station)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)";
            List<Entity> targets =
                    select(
                            "SELECT * FROM platform_records WHERE tenant_id=? AND record_id=? LIMIT"
                                    + " 1",
                            List.of(tenant, resourceId));
            Map<String, Object> target = targets.isEmpty() ? Map.of() : targets.get(0).data();
            String caseId =
                    targets.isEmpty()
                            ? ""
                            : "case".equals(targets.get(0).kind())
                                    ? resourceId
                                    : text(target, "case_id");
            Map<String, Object> parent =
                    caseId.isEmpty()
                            ? Map.of()
                            : get(tenant, "case", caseId).map(Entity::data).orElse(target);
            Map<String, Object> auditDetails = new LinkedHashMap<>(details);
            String trace = TraceUtil.getTraceId();
            if (trace != null) {
                auditDetails.put("trace_id", trace);
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, tenant);
                statement.setString(3, actor);
                statement.setString(4, action);
                statement.setString(5, resourceId);
                statement.setObject(6, now());
                statement.setString(7, JsonUtil.toJson(auditDetails));
                statement.setString(8, caseId);
                statement.setString(9, textOr(target, parent, "city_code"));
                statement.setString(10, textOr(target, parent, "station_id"));
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }

        public AuditPage auditPage(RecordScope actor, String cursor, int limit) {
            List<Object> values = new ArrayList<>(List.of(actor.tenantId()));
            StringBuilder sql =
                    new StringBuilder("SELECT * FROM platform_audit_events WHERE tenant_id=?");
            appendScope(sql, values, "scope_city", actor.cityCodes());
            appendScope(sql, values, "scope_station", actor.stationIds());
            if (cursor != null && !cursor.isBlank()) {
                Map<String, Object> after = decodeCursor(cursor);
                OffsetDateTime created = cursorTime(after, "created");
                sql.append(" AND (created_at<? OR (created_at=? AND event_id<?))");
                values.addAll(List.of(created, created, cursorText(after, "id")));
            }
            sql.append(" ORDER BY created_at DESC,event_id DESC LIMIT ?");
            values.add(Math.addExact(limit, 1));
            List<Map<String, Object>> events = readAudits(sql.toString(), values);
            boolean more = events.size() > limit;
            if (more) {
                events.remove(events.size() - 1);
            }
            String next = "";
            if (more) {
                Map<String, Object> last = events.get(events.size() - 1);
                next =
                        encodeCursor(
                                Map.of(
                                        "created",
                                        last.get("created_at"),
                                        "id",
                                        last.get("event_id")));
            }
            return new AuditPage(List.copyOf(events), next);
        }

        public List<Map<String, Object>> auditEvents(String tenant) {
            return readAudits(
                    "SELECT * FROM platform_audit_events WHERE tenant_id=?"
                            + " ORDER BY created_at,event_id LIMIT 1000",
                    List.of(tenant));
        }

        private List<Map<String, Object>> readAudits(String sql, List<Object> values) {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                bind(query, values);
                try (ResultSet rows = query.executeQuery()) {
                    List<Map<String, Object>> events = new ArrayList<>();
                    while (rows.next()) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("event_id", rows.getString("event_id"));
                        event.put("tenant_id", rows.getString("tenant_id"));
                        event.put("actor", rows.getString("actor_id"));
                        event.put("action", rows.getString("action"));
                        event.put("target", rows.getString("resource_id"));
                        event.put("created_at", time(rows, "created_at"));
                        event.put("details", JsonUtil.fromJson(rows.getString("payload"), MAP));
                        events.add(event);
                    }
                    return events;
                }
            } catch (SQLException failure) {
                throw databaseFailure(failure);
            }
        }
    }

    private static StringBuilder recordQuery(
            String tenant,
            String kind,
            String caseId,
            Set<String> statuses,
            String state,
            String cursor,
            List<Object> values) {
        values.addAll(List.of(tenant, kind, cursor == null ? "" : cursor));
        StringBuilder sql =
                new StringBuilder(
                        "SELECT r.* FROM platform_records r"
                                + " WHERE r.tenant_id=? AND r.kind=? AND r.record_id>?");
        if (caseId != null && !caseId.isBlank()) {
            sql.append(" AND r.case_id=?");
            values.add(caseId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND r.record_status IN (")
                    .append(String.join(",", Collections.nCopies(statuses.size(), "?")))
                    .append(")");
            values.addAll(new TreeSet<>(statuses));
        }
        if (state != null && !state.isBlank()) {
            sql.append(" AND r.record_state=?");
            values.add(state);
        }
        return sql;
    }

    private static void appendScope(
            StringBuilder sql, List<Object> values, String column, Set<String> scopes) {
        if (scopes.contains("*")) {
            return;
        }
        if (scopes.isEmpty()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ")
                .append(column)
                .append(" IN (")
                .append(String.join(",", Collections.nCopies(scopes.size(), "?")))
                .append(")");
        values.addAll(new TreeSet<>(scopes));
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }

    private static void validateLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) {
            throw PlatformException.invalid("limit must be 1.." + maximum);
        }
    }

    private static OffsetDateTime now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime due(Map<String, Object> data) {
        return Instant.parse(String.valueOf(data.getOrDefault("due_at", "9999-01-01T00:00:00Z")))
                .atOffset(ZoneOffset.UTC);
    }

    private static int priority(Map<String, Object> data) {
        return "HIGH".equals(data.get("risk_level")) ? 0 : 1;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    private static String textOr(
            Map<String, Object> data, Map<String, Object> fallback, String key) {
        return data.get(key) == null ? text(fallback, key) : text(data, key);
    }

    private static Entity entity(ResultSet rows) throws SQLException {
        return new Entity(
                rows.getString("record_id"),
                rows.getString("tenant_id"),
                rows.getString("kind"),
                rows.getLong("record_version"),
                time(rows, "created_at"),
                time(rows, "updated_at"),
                JsonUtil.fromJson(rows.getString("payload"), MAP));
    }

    private static String time(ResultSet rows, String field) throws SQLException {
        return rows.getObject(field, OffsetDateTime.class).toInstant().toString();
    }

    private static PlatformException missing(String kind) {
        return new PlatformException(404, "NOT_FOUND", kind + " record not found");
    }

    private static void rejectInlineBody(Map<String, Object> data) {
        if (data.containsKey("content_base64")) {
            throw PlatformException.invalid(
                    "Attachment bodies belong in the dedicated content table");
        }
    }

    private static void validateRuntimePublication(Entity before, Map<String, Object> data) {
        Map<String, Object> original = new LinkedHashMap<>(before.data());
        Map<String, Object> updated = new LinkedHashMap<>(data);
        for (String metadata :
                List.of("state", "evaluation_run_id", "published_by", "published_at")) {
            original.remove(metadata);
            updated.remove(metadata);
        }
        if (!"CANDIDATE".equals(before.data().get("state"))
                || !"PUBLISHED".equals(data.get("state"))
                || data.get("evaluation_run_id") == null
                || !hash(original).equals(hash(updated))) {
            throw PlatformException.conflict(
                    "Runtime executable contents are immutable; only candidate publication is"
                            + " allowed");
        }
    }

    private static Map<String, Object> decodeCursor(String cursor) {
        try {
            return JsonUtil.fromJson(
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), MAP);
        } catch (RuntimeException failure) {
            throw PlatformException.invalid("Invalid pagination cursor");
        }
    }

    private static OffsetDateTime cursorTime(Map<String, Object> cursor, String field) {
        try {
            return Instant.parse(cursorText(cursor, field)).atOffset(ZoneOffset.UTC);
        } catch (IllegalArgumentException failure) {
            throw PlatformException.invalid("Invalid pagination timestamp");
        }
    }

    private static String cursorText(Map<String, Object> cursor, String field) {
        Object value = cursor.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw PlatformException.invalid("Invalid pagination cursor");
        }
        return text;
    }

    private static String encodeCursor(Map<String, Object> cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(JsonUtil.toJson(cursor).getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(Object value) {
        return sha256(JsonUtil.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            SortedMap<String, Object> ordered = new TreeMap<>();
            map.forEach((key, nested) -> ordered.put(String.valueOf(key), canonical(nested)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PlatformStore::canonical).toList();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value;
    }

    private static void logDatabaseFailure(SQLException failure) {
        // SQLException messages can contain SQL text, credentials, or bound customer data.
        LOG.error(
                "Durable storage failure: sql_state={}, vendor_code={}, trace_id={}",
                failure.getSQLState(),
                failure.getErrorCode(),
                TraceUtil.getTraceId());
    }

    private static PlatformException databaseFailure(SQLException failure) {
        if ("23505".equals(failure.getSQLState())) {
            return PlatformException.conflict("Record already exists");
        }
        logDatabaseFailure(failure);
        return new PlatformException(
                503, "STORAGE_UNAVAILABLE", "Durable storage operation failed");
    }
}
