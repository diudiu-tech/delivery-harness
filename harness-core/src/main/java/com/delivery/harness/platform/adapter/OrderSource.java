package com.delivery.harness.platform.adapter;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.tool.order.OrderFixtures;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** A read-only adapter. A failed live query never consults the demo fixture. */
@Service
public class OrderSource {
    private final String mode;
    private final String endpoint;
    private final String token;
    private final Set<String> allowedHosts;
    private final HttpClient http;
    public record Snapshot(OrderInfo order, Map<String,Object> metadata) {}
    public OrderSource(@Value("${harness.mode:demo}") String mode,
                       @Value("${harness.adapter.order-endpoint:}") String endpoint,
                       @Value("${harness.adapter.allowed-hosts:}") String allowedHosts,
                       @Value("${harness.adapter.token:}") String token) {
        this.mode=mode; this.endpoint=endpoint; this.token=token;
        this.allowedHosts=new HashSet<>(Arrays.asList(allowedHosts.split(",")));
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        if ("pilot".equals(mode)) validateUri(uriFor("startup-probe"));
    }
    public Snapshot query(AuthContext.Principal principal,String orderId) {
        if (orderId == null || !orderId.matches("[A-Za-z0-9_.:-]{1,120}")) throw PlatformException.invalid("order_id must be a safe identifier");
        if (!"pilot".equals(mode)) {
            OrderInfo order=OrderFixtures.build(orderId,LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            principal.authorizeScope(order.getCityCode(),order.getStationId());
            return new Snapshot(order,Map.of("schema_version","1","source_version","fixture-v1","source_type","SYNTHETIC","source_timezone","Asia/Shanghai","collected_at",Instant.now().toString(),"historical_context_available",false,"currency","CNY"));
        }
        URI uri=uriFor(orderId); validateUri(uri);
        HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept","application/json").header("X-Tenant-ID",principal.tenantId()).GET();
        if (!token.isBlank()) request.header("Authorization","Bearer " + token);
        try {
            HttpResponse<String> response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) {
                String code=switch(response.statusCode()) {case 404 -> "NOT_FOUND";case 401,403 -> "UNAUTHORIZED";case 429 -> "RATE_LIMITED";default -> "UNAVAILABLE";};
                int status=response.statusCode()==404?404:response.statusCode()==429?429:502;
                throw new PlatformException(status,code,"Read-only order source returned " + response.statusCode());
            }
            if(response.body().length()>1_000_000) throw invalidData("Order source response exceeds 1 MB");
            Map<String,Object> envelope;
            try { envelope=JsonUtil.fromJson(response.body(),new TypeReference<>(){}); } catch(RuntimeException e) {throw invalidData("Order source returned invalid JSON");}
            for(String field:List.of("schema_version","source_version","collected_at","source_timezone","currency","order")) if(envelope.get(field)==null) throw invalidData("Missing adapter field: " + field);
            if(envelope.get("source_version").toString().isBlank())throw invalidData("Source version must be nonempty");
            if(!"1".equals(String.valueOf(envelope.get("schema_version")))) throw invalidData("Unsupported source schema_version");
            if(!"CNY".equals(envelope.get("currency"))) throw invalidData("Unsupported currency");
            try { Instant.parse(String.valueOf(envelope.get("collected_at"))); ZoneId.of(String.valueOf(envelope.get("source_timezone"))); } catch(RuntimeException e) {throw invalidData("Invalid adapter temporal metadata");}
            OrderInfo order;
            try { order=JsonUtil.fromMap(normalizeTimes(JsonUtil.toMap(envelope.get("order")),ZoneId.of(envelope.get("source_timezone").toString())),OrderInfo.class); } catch(RuntimeException e) {throw invalidData("Invalid order schema");}
            if(!orderId.equals(order.getOrderId()) || order.getStatus()==null || order.getCityCode()==null || order.getStationId()==null
                    || order.getStatus().isBlank() || order.getCityCode().isBlank() || order.getStationId().isBlank() || order.getOrderAmount()==null || order.getOrderAmount().signum()<0 || order.getOrderAmount().stripTrailingZeros().scale()>2) throw invalidData("Missing or invalid order identifier, status, scope, or amount");
            principal.authorizeScope(order.getCityCode(),order.getStationId());
            Map<String,Object> metadata=new LinkedHashMap<>();for(String field:List.of("schema_version","source_version","collected_at","source_timezone","currency","historical_context_available","occurred_at"))if(envelope.containsKey(field))metadata.put(field,envelope.get(field)); metadata.put("source_type","LIVE_READ_ONLY");metadata.put("normalized_timezone","Asia/Shanghai");metadata.put("time_normalization","OFFSET_OR_SOURCE_ZONE_TO_SHANGHAI");
            metadata.putIfAbsent("historical_context_available",false);
            Instant collected=Instant.parse(envelope.get("collected_at").toString());
            if(collected.isAfter(Instant.now().plusSeconds(300)))throw invalidData("Collection timestamp is in the future");
            for(LocalDateTime actual:Arrays.asList(order.getCreateTime(),order.getAcceptTime(),order.getArriveShopTime(),order.getPickupTime(),order.getDeliveryTime()))if(actual!=null && actual.atZone(ZoneId.of("Asia/Shanghai")).toInstant().isAfter(collected.plusSeconds(300)))throw invalidData("Order event timestamp is after the collection time");
            metadata.put("quality_status",collected.isBefore(Instant.now().minusSeconds(3600))?"STALE":"VALID");
            Map<String,Object> extras=new LinkedHashMap<>();if(order.getExtraInfo()!=null && order.getExtraInfo().get("weather") instanceof String weather && Set.of("晴","雨","大雨","暴雨","雪","大雪","阴","晴天","小雨","中雨","雨天","SUNNY","RAIN","SNOW","CLEAR","CLOUDY").contains(weather))extras.put("weather",weather);
            extras.put("source_type","LIVE_READ_ONLY");extras.put("historical_context_available",Boolean.TRUE.equals(metadata.get("historical_context_available")));
            extras.remove("scenario_key");order.setExtraInfo(extras);
            return new Snapshot(order,metadata);
        } catch(HttpTimeoutException e) {throw new PlatformException(504,"TIMEOUT","Order source deadline exceeded");}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new PlatformException(503,"CANCELLED","Order query interrupted");}
        catch(java.io.IOException e) {throw new PlatformException(502,"UNAVAILABLE","Order source is unavailable");}
    }
    private static Map<String,Object> normalizeTimes(Map<String,Object> raw,ZoneId sourceZone) {
        Map<String,Object> result=new LinkedHashMap<>(raw);
        for(String field:List.of("createTime","acceptTime","arriveShopTime","pickupTime","deliveryTime","expectedDeliveryTime")) {
            if(result.get(field)!=null)result.put(field,normalizeTime(result.get(field).toString(),sourceZone));
        }
        if(result.get("events") instanceof List<?> events) {
            List<Map<String,Object>> normalized=new ArrayList<>();
            for(Object event:events){Map<String,Object> map=new LinkedHashMap<>(JsonUtil.toMap(event));if(map.get("eventTime")!=null)map.put("eventTime",normalizeTime(map.get("eventTime").toString(),sourceZone));normalized.add(map);}result.put("events",normalized);
        }
        return result;
    }
    private static String normalizeTime(String value,ZoneId sourceZone) {
        Instant instant;
        try{instant=OffsetDateTime.parse(value).toInstant();}catch(java.time.format.DateTimeParseException offsetAbsent){LocalDateTime local=LocalDateTime.parse(value);if(sourceZone.getRules().getValidOffsets(local).size()!=1)throw new IllegalArgumentException("Local timestamp is ambiguous or invalid in the declared source zone");instant=local.atZone(sourceZone).toInstant();}
        return LocalDateTime.ofInstant(instant,ZoneId.of("Asia/Shanghai")).toString();
    }
    public Map<String,Object> status() {
        return Map.of("mode",mode,"source_type","pilot".equals(mode)?"LIVE_READ_ONLY":"SYNTHETIC","available_fields",List.of("order_id","status","city_code","station_id","timestamps","order_amount","currency"),"historical_context_available",false,"contract_schema_version","1");
    }
    private URI uriFor(String orderId) {
        return URI.create(endpoint.replace("{order_id}",URLEncoder.encode(orderId,StandardCharsets.UTF_8)));
    }
    private void validateUri(URI uri) {
        if(uri.getHost()==null || !allowedHosts.contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","::1").contains(uri.getHost()))) {
            throw new IllegalStateException("Live order endpoint must use HTTPS on an explicitly allowed host (HTTP allowed only on loopback)");
        }
    }
    private static PlatformException invalidData(String message) {return new PlatformException(502,"INVALID_DATA",message);}
}
