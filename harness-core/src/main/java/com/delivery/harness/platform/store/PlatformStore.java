package com.delivery.harness.platform.store;

import com.delivery.harness.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Tenant-scoped durable records. Every mutation and its audit event share a JDBC transaction. */
@Service
public class PlatformStore {
    private final DataSource source;
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};
    public PlatformStore(DataSource source) { this.source = source; }
    public record Entity(String id, String tenantId, String kind, long version,
                         String createdAt, String updatedAt, Map<String,Object> data) {}
    public Optional<Entity> get(String tenant, String kind, String id) { return transact(tx -> tx.get(tenant, kind, id)); }
    public Entity require(String tenant, String kind, String id) { return transact(tx -> tx.require(tenant, kind, id)); }
    public List<Entity> list(String tenant, String kind) { return transact(tx -> tx.list(tenant, kind)); }
    public Entity create(String tenant, String kind, String id, Map<String,Object> data, String actor, String action) {
        return transact(tx -> tx.create(tenant, kind, id, data, actor, action));
    }
    public Entity update(String tenant, String kind, String id, long expectedVersion, Map<String,Object> data, String actor, String action) {
        return transact(tx -> tx.update(tenant, kind, id, expectedVersion, data, actor, action));
    }
    public <T> T transact(Function<Tx,T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(new Tx(connection));
                connection.commit();
                return result;
            } catch (RuntimeException | Error e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) { throw databaseFailure(e); }
    }
    public List<Entity> page(String tenant,String kind,String cursor,int limit) {
        if(limit<1 || limit>100) throw PlatformException.invalid("limit must be 1..100");
        return transact(tx -> tx.page(tenant,kind,cursor,limit));
    }
    public record Page(List<Entity> items,String cursor) {}
    /** Database keyset pagination preserves global priority/due/creation ordering and filters scope before limiting. */
    public Page caseQueue(com.delivery.harness.platform.auth.AuthContext.Principal actor,String cursor,int limit) {
        if(limit<1 || limit>100)throw PlatformException.invalid("limit must be 1..100");
        return transact(tx -> {
            List<Object> values=new ArrayList<>();values.add(actor.tenantId());
            StringBuilder sql=new StringBuilder("SELECT * FROM platform_records WHERE tenant_id=? AND kind='case'");
            appendScope(sql,values,"scope_city",actor.cityCodes());appendScope(sql,values,"scope_station",actor.stationIds());
            if(cursor!=null && !cursor.isBlank()) {
                Map<String,Object> after;
                try{after=JsonUtil.fromJson(new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8),MAP);}catch(RuntimeException e){throw PlatformException.invalid("Invalid case queue cursor");}
                if(!(after.get("priority") instanceof Number) || after.get("due")==null || after.get("created")==null || after.get("id")==null)throw PlatformException.invalid("Invalid case queue cursor");
                int priority=((Number)after.get("priority")).intValue();String due=after.get("due").toString(),created=after.get("created").toString(),id=after.get("id").toString();
                sql.append(" AND (queue_priority>? OR (queue_priority=? AND queue_due_at>?) OR (queue_priority=? AND queue_due_at=? AND created_at>?) OR (queue_priority=? AND queue_due_at=? AND created_at=? AND record_id>?))");
                values.addAll(List.of(priority,priority,due,priority,due,created,priority,due,created,id));
            }
            sql.append(" ORDER BY queue_priority,queue_due_at,created_at,record_id LIMIT ?");values.add(limit+1);
            try(PreparedStatement query=tx.connection.prepareStatement(sql.toString())) {
                for(int i=0;i<values.size();i++)query.setObject(i+1,values.get(i));
                try(ResultSet rows=query.executeQuery()) {
                    List<Entity> records=new ArrayList<>();while(rows.next())records.add(entity(rows));boolean more=records.size()>limit;
                    if(more)records.remove(records.size()-1);String next="";
                    if(more){Entity last=records.get(records.size()-1);Map<String,Object> key=Map.of("priority",priority(last.data()),"due",due(last.data()),"created",last.createdAt(),"id",last.id());next=Base64.getUrlEncoder().withoutPadding().encodeToString(JsonUtil.toJson(key).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                    return new Page(List.copyOf(records),next);
                }
            }catch(SQLException e){throw databaseFailure(e);}
        });
    }
    private static void appendScope(StringBuilder sql,List<Object> values,String column,Set<String> scopes) {
        if(scopes.contains("*"))return;
        if(scopes.isEmpty()){sql.append(" AND 1=0");return;}
        sql.append(" AND ").append(column).append(" IN (").append(String.join(",",Collections.nCopies(scopes.size(),"?"))).append(")");values.addAll(new TreeSet<>(scopes));
    }
    private static int priority(Map<String,Object> data){return "HIGH".equals(data.get("risk_level"))?0:1;}
    private static String due(Map<String,Object> data){return String.valueOf(data.getOrDefault("due_at","9999-01-01T00:00:00Z"));}
    private static void projection(PreparedStatement statement,int first,Map<String,Object> data) throws SQLException {
        statement.setString(first,String.valueOf(data.getOrDefault("city_code","")));statement.setString(first+1,String.valueOf(data.getOrDefault("station_id","")));
        statement.setInt(first+2,priority(data));statement.setString(first+3,due(data));
    }
    public Set<String> tenants() {
        return transact(tx -> {
            try (PreparedStatement query=tx.connection.prepareStatement("SELECT DISTINCT tenant_id FROM platform_records");ResultSet rows=query.executeQuery()) {
                Set<String> tenants=new HashSet<>();while(rows.next())tenants.add(rows.getString(1));return tenants;
            } catch(SQLException e) {throw databaseFailure(e);}
        });
    }
    public List<Map<String,Object>> audits(String tenant) {
        return transact(tx -> tx.auditEvents(tenant));
    }
    public final class Tx {
        private final Connection connection;
        private Tx(Connection connection) { this.connection = connection; }
        public Optional<Entity> get(String tenant, String kind, String id) {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM platform_records WHERE tenant_id=? AND kind=? AND record_id=?")) {
                query.setString(1, tenant); query.setString(2, kind); query.setString(3, id);
                try (ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(entity(rows)) : Optional.empty(); }
            } catch (SQLException e) { throw databaseFailure(e); }
        }
        public Optional<Entity> getForUpdate(String tenant,String kind,String id) {
            try(PreparedStatement query=connection.prepareStatement("SELECT * FROM platform_records WHERE tenant_id=? AND kind=? AND record_id=? FOR UPDATE")) {
                query.setString(1,tenant);query.setString(2,kind);query.setString(3,id);try(ResultSet rows=query.executeQuery()){return rows.next()?Optional.of(entity(rows)):Optional.empty();}
            }catch(SQLException e){throw databaseFailure(e);}
        }
        public Entity requireForUpdate(String tenant,String kind,String id){return getForUpdate(tenant,kind,id).orElseThrow(() -> new PlatformException(404,"NOT_FOUND",kind+" record not found"));}
        public Entity require(String tenant, String kind, String id) {
            return get(tenant,kind,id).orElseThrow(() -> new PlatformException(404,"NOT_FOUND",kind + " record not found"));
        }
        /** Serialize reference creation/retirement against this parent without changing its evidence version. */
        public Entity lock(String tenant,String kind,String id) {
            try(PreparedStatement query=connection.prepareStatement("SELECT * FROM platform_records WHERE tenant_id=? AND kind=? AND record_id=? FOR UPDATE")) {
                query.setString(1,tenant);query.setString(2,kind);query.setString(3,id);
                try(ResultSet rows=query.executeQuery()){if(!rows.next())throw new PlatformException(404,"NOT_FOUND",kind+" record not found");return entity(rows);}
            }catch(SQLException e){throw databaseFailure(e);}
        }
        public List<Entity> list(String tenant, String kind) {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM platform_records WHERE tenant_id=? AND kind=? ORDER BY created_at, record_id")) {
                query.setString(1,tenant); query.setString(2,kind);
                try (ResultSet rows = query.executeQuery()) {
                    List<Entity> result = new ArrayList<>();
                    while (rows.next()) result.add(entity(rows));
                    return List.copyOf(result);
                }
            } catch (SQLException e) { throw databaseFailure(e); }
        }
        public List<Entity> page(String tenant,String kind,String cursor,int limit) {
            try (PreparedStatement query=connection.prepareStatement("SELECT * FROM platform_records WHERE tenant_id=? AND kind=? AND record_id>? ORDER BY record_id LIMIT ?")) {
                query.setString(1,tenant);query.setString(2,kind);query.setString(3,cursor==null?"":cursor);query.setInt(4,limit);
                try(ResultSet rows=query.executeQuery()){List<Entity> result=new ArrayList<>();while(rows.next())result.add(entity(rows));return result;}
            }catch(SQLException e){throw databaseFailure(e);}
        }
        public Entity create(String tenant, String kind, String id, Map<String,Object> data, String actor, String action) {
            String now = Instant.now().toString();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO platform_records (tenant_id,kind,record_id,record_version,created_at,updated_at,payload,scope_city,scope_station,queue_priority,queue_due_at) VALUES (?,?,?,1,?,?,?,?,?,?,?)")) {
                statement.setString(1,tenant); statement.setString(2,kind); statement.setString(3,id);
                statement.setString(4,now); statement.setString(5,now); statement.setString(6,JsonUtil.toJson(data));
                projection(statement,7,data);
                statement.executeUpdate();
                audit(tenant,actor,action,id,Map.of("kind",kind,"after_version",1,"after_hash",hash(data)));
                return require(tenant,kind,id);
            } catch (SQLException e) { throw databaseFailure(e); }
        }
        public Entity update(String tenant, String kind, String id, long expectedVersion, Map<String,Object> data, String actor, String action) {
            if(Set.of("decision","order_snapshot","review_action").contains(kind))throw PlatformException.conflict("Completed snapshots are immutable; create a new version");
            Entity before = require(tenant,kind,id);
            if("runtime_config".equals(kind)) {
                Map<String,Object> original=new LinkedHashMap<>(before.data()),updated=new LinkedHashMap<>(data);
                for(String metadata:List.of("state","evaluation_run_id","published_by","published_at")){original.remove(metadata);updated.remove(metadata);}
                if(!"CANDIDATE".equals(before.data().get("state")) || !"PUBLISHED".equals(data.get("state")) || data.get("evaluation_run_id")==null || !hash(original).equals(hash(updated)))throw PlatformException.conflict("Runtime executable contents are immutable; only evaluated candidate publication is allowed");
            }
            if (before.version() != expectedVersion) throw PlatformException.conflict("Record changed; refresh the current version");
            try (PreparedStatement statement = connection.prepareStatement("UPDATE platform_records SET record_version=record_version+1,updated_at=?,payload=?,scope_city=?,scope_station=?,queue_priority=?,queue_due_at=? WHERE tenant_id=? AND kind=? AND record_id=? AND record_version=?")) {
                statement.setString(1,Instant.now().toString()); statement.setString(2,JsonUtil.toJson(data));
                projection(statement,3,data);
                statement.setString(7,tenant); statement.setString(8,kind); statement.setString(9,id); statement.setLong(10,expectedVersion);
                if (statement.executeUpdate() != 1) throw PlatformException.conflict("Concurrent update; refresh the current version");
                audit(tenant,actor,action,id,Map.of("kind",kind,"before_version",expectedVersion,"after_version",expectedVersion+1,"before_hash",hash(before.data()),"after_hash",hash(data)));
                return require(tenant,kind,id);
            } catch (SQLException e) { throw databaseFailure(e); }
        }
        /** Privileged retention services validate relationships and independent approval before invoking this CAS deletion. */
        public void delete(String tenant,String kind,String id,long expectedVersion,String actor,String reason) {
            if(reason==null || reason.isBlank())throw PlatformException.invalid("Deletion reason is required");
            Entity before=require(tenant,kind,id);if(before.version()!=expectedVersion)throw PlatformException.conflict("Record changed before deletion");
            try(PreparedStatement statement=connection.prepareStatement("DELETE FROM platform_records WHERE tenant_id=? AND kind=? AND record_id=? AND record_version=?")) {
                statement.setString(1,tenant);statement.setString(2,kind);statement.setString(3,id);statement.setLong(4,expectedVersion);
                if(statement.executeUpdate()!=1)throw PlatformException.conflict("Concurrent record change prevented deletion");
                audit(tenant,actor,"RETENTION_RECORD_DELETED",id,Map.of("kind",kind,"before_version",expectedVersion,"before_hash",hash(before.data()),"reason",reason));
            }catch(SQLException e){throw databaseFailure(e);}
        }
        public void audit(String tenant, String actor, String action, String resourceId, Map<String,Object> details) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO platform_audit_events (event_id,tenant_id,actor_id,action,resource_id,created_at,payload) VALUES (?,?,?,?,?,?,?)")) {
                statement.setString(1,UUID.randomUUID().toString()); statement.setString(2,tenant);
                statement.setString(3,actor); statement.setString(4,action); statement.setString(5,resourceId);
                statement.setString(6,Instant.now().toString());Map<String,Object> auditDetails=new LinkedHashMap<>(details);
                String trace=com.delivery.harness.common.util.TraceUtil.getTraceId();if(trace!=null)auditDetails.put("trace_id",trace);
                statement.setString(7,JsonUtil.toJson(auditDetails));
                statement.executeUpdate();
            } catch (SQLException e) { throw databaseFailure(e); }
        }
        public List<Map<String,Object>> auditEvents(String tenant) {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM platform_audit_events WHERE tenant_id=? ORDER BY created_at,event_id")) {
                query.setString(1,tenant);
                try (ResultSet rows = query.executeQuery()) {
                    List<Map<String,Object>> result = new ArrayList<>();
                    while (rows.next()) {
                        Map<String,Object> item = new LinkedHashMap<>();
                        item.put("event_id",rows.getString("event_id")); item.put("tenant_id",tenant);
                        item.put("actor",rows.getString("actor_id")); item.put("action",rows.getString("action"));
                        item.put("target",rows.getString("resource_id")); item.put("created_at",rows.getString("created_at"));
                        item.put("details",JsonUtil.fromJson(rows.getString("payload"),MAP)); result.add(item);
                    }
                    return result;
                }
            } catch (SQLException e) { throw databaseFailure(e); }
        }
    }
    private static Entity entity(ResultSet rows) throws SQLException {
        return new Entity(rows.getString("record_id"),rows.getString("tenant_id"),rows.getString("kind"),rows.getLong("record_version"),
                rows.getString("created_at"),rows.getString("updated_at"),JsonUtil.fromJson(rows.getString("payload"),MAP));
    }
    public static String hash(Object value) {
        try {
            Object canonical = canonical(value);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JsonUtil.toJson(canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) {
            SortedMap<String,Object> ordered = new TreeMap<>(); map.forEach((k,v) -> ordered.put(String.valueOf(k),canonical(v))); return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(PlatformStore::canonical).toList();
        if(value instanceof Number number)return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
    private static PlatformException databaseFailure(SQLException e) {
        if ("23505".equals(e.getSQLState())) return PlatformException.conflict("Record already exists");
        return new PlatformException(503,"STORAGE_UNAVAILABLE","Durable storage operation failed");
    }
}
