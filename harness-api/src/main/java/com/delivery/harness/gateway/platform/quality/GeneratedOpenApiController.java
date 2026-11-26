package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The contract comes from registered routes, Jackson DTO metadata and validation annotations at
 * runtime.
 */
@RestController
public class GeneratedOpenApiController {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ObjectMapper mapper;

    public GeneratedOpenApiController(
            @Qualifier("requestMappingHandlerMapping")
                    ObjectProvider<RequestMappingHandlerMapping> mappings,
            ObjectMapper mapper) {
        this.mappings = mappings;
        this.mapper = mapper;
    }

    @GetMapping("/api/v2/openapi")
    public Map<String, Object> contract(HttpServletRequest request) {
        PrincipalAccess.get(request);
        return generate();
    }

    public Map<String, Object> generate() {
        Builder builder = new Builder(mapper);
        for (var route : mappings.getObject().getHandlerMethods().entrySet()) {
            for (String pattern : route.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/v2/")) {
                    continue;
                }
                for (String path : expandPath(pattern)) {
                    builder.add(path, route.getKey(), route.getValue());
                }
            }
        }
        return Map.of(
                "openapi",
                "3.1.0",
                "info",
                Map.of("title", "Delivery Harness v2", "version", "2.0"),
                "paths",
                builder.paths,
                "components",
                Map.of(
                        "schemas",
                        builder.schemas,
                        "securitySchemes",
                        Map.of("bearer", Map.of("type", "http", "scheme", "bearer"))),
                "security",
                List.of(Map.of("bearer", List.of())));
    }

    public static List<String> expandPath(String pattern) {
        var matcher = java.util.regex.Pattern.compile("\\{([^}:]+):([^}]+)}").matcher(pattern);
        if (!matcher.find()) {
            return List.of(pattern);
        }
        String alternatives = matcher.group(2);
        if (!alternatives.matches("[a-zA-Z0-9_-]+(?:\\|[a-zA-Z0-9_-]+)+")) {
            return List.of(
                    pattern.substring(0, matcher.start())
                            + "{"
                            + matcher.group(1)
                            + "}"
                            + pattern.substring(matcher.end()));
        }
        List<String> paths = new ArrayList<>();
        for (String alternative : alternatives.split("\\|")) {
            paths.add(
                    pattern.substring(0, matcher.start())
                            + alternative
                            + pattern.substring(matcher.end()));
        }
        return paths;
    }

    private static final class Builder {
        private final ObjectMapper mapper;
        private final Map<String, Object> paths = new TreeMap<>();
        private final Map<String, Object> schemas = new TreeMap<>();

        private Builder(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @SuppressWarnings("unchecked")
        private void add(String path, RequestMappingInfo route, HandlerMethod method) {
            Map<String, Object> pathItem =
                    (Map<String, Object>)
                            paths.computeIfAbsent(path, ignored -> new TreeMap<String, Object>());
            for (var verb : route.getMethodsCondition().getMethods()) {
                Map<String, Object> operation = new LinkedHashMap<>();
                operation.put(
                        "operationId",
                        method.getBeanType().getSimpleName()
                                + "_"
                                + method.getMethod().getName()
                                + "_"
                                + verb.name()
                                + "_"
                                + Integer.toUnsignedString(path.hashCode()));
                operation.put(
                        "responses",
                        Map.of(
                                "2XX",
                                Map.of(
                                        "description",
                                        "Successful response",
                                        "content",
                                        Map.of(
                                                "application/json",
                                                Map.of(
                                                        "schema",
                                                        schema(
                                                                method.getMethod()
                                                                        .getGenericReturnType(),
                                                                null)))),
                                "400",
                                Map.of("description", "Invalid request"),
                                "401",
                                Map.of("description", "Explicit bearer authentication required"),
                                "403",
                                Map.of("description", "Role or browser boundary refused"),
                                "404",
                                Map.of("description", "Unknown or inaccessible scoped resource"),
                                "422",
                                Map.of("description", "Unsafe personal-data content refused")));
                List<Map<String, Object>> parameters = new ArrayList<>();
                for (var parameter : method.getMethod().getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)) {
                        operation.put(
                                "requestBody",
                                Map.of(
                                        "required",
                                        parameter.getAnnotation(RequestBody.class).required(),
                                        "content",
                                        Map.of(
                                                "application/json",
                                                Map.of(
                                                        "schema",
                                                        schema(
                                                                parameter.getParameterizedType(),
                                                                parameter)))));
                    }
                    RequestParam query = parameter.getAnnotation(RequestParam.class);
                    PathVariable variable = parameter.getAnnotation(PathVariable.class);
                    if (query == null && variable == null) {
                        continue;
                    }
                    String name =
                            query != null
                                    ? first(query.name(), query.value(), parameter.getName())
                                    : first(variable.name(), variable.value(), parameter.getName());
                    if (variable != null && !path.contains("{" + name + "}")) {
                        continue;
                    }
                    parameters.add(
                            Map.of(
                                    "name",
                                    name,
                                    "in",
                                    query == null ? "path" : "query",
                                    "required",
                                    query == null
                                            || query.required()
                                                    && query.defaultValue()
                                                            .equals(
                                                                    org.springframework.web.bind
                                                                            .annotation
                                                                            .ValueConstants
                                                                            .DEFAULT_NONE),
                                    "schema",
                                    schema(parameter.getParameterizedType(), parameter)));
                }
                if (!parameters.isEmpty()) {
                    operation.put("parameters", parameters);
                }
                pathItem.put(verb.name().toLowerCase(Locale.ROOT), operation);
            }
        }

        private Map<String, Object> schema(Type type, AnnotatedElement annotations) {
            var javaType = mapper.constructType(type);
            Class<?> raw = javaType.getRawClass();
            Map<String, Object> schema = new LinkedHashMap<>();
            if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) {
                return schema;
            }
            if (raw == byte[].class) {
                schema.put("type", "string");
                schema.put("format", "binary");
            } else if (raw.isEnum()) {
                schema.put("type", "string");
                schema.put(
                        "enum",
                        Arrays.stream(raw.getEnumConstants()).map(Object::toString).toList());
            } else if (raw == void.class || raw == Void.class || Map.class.isAssignableFrom(raw)) {
                schema.put("type", "object");
                schema.put("additionalProperties", true);
            } else if (javaType.isCollectionLikeType() || javaType.isArrayType()) {
                schema.put("type", "array");
                schema.put(
                        "items",
                        schema(javaType.getContentType(), contentAnnotations(annotations)));
            } else if (raw == String.class || raw == Instant.class || raw == LocalDateTime.class) {
                schema.put("type", "string");
                if (raw != String.class) {
                    schema.put("format", "date-time");
                }
            } else if (raw == Boolean.class || raw == boolean.class) {
                schema.put("type", "boolean");
            } else if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) {
                schema.put(
                        "type",
                        raw == BigDecimal.class || raw == double.class || raw == Double.class
                                ? "number"
                                : "integer");
            } else if (ResponseEntity.class.isAssignableFrom(raw)) {
                return schema(javaType.containedType(0), null);
            } else {
                String name = raw.getSimpleName();
                if (!schemas.containsKey(name)) {
                    schemas.put(name, Map.of("type", "object"));
                    Map<String, Object> properties = new LinkedHashMap<>();
                    List<String> required = new ArrayList<>();
                    for (var property :
                            mapper.getSerializationConfig().introspect(javaType).findProperties()) {
                        var member = property.getPrimaryMember();
                        if (member == null) {
                            continue;
                        }
                        AnnotatedElement field = member.getAnnotated();
                        if (raw.isRecord()) {
                            try {
                                field = raw.getDeclaredField(property.getInternalName());
                            } catch (NoSuchFieldException ignored) {
                                // Jackson custom properties still contribute their actual declared
                                // JSON name and type.
                            }
                        }
                        properties.put(property.getName(), schema(member.getType(), field));
                        if (required(field)) {
                            required.add(property.getName());
                        }
                    }
                    Map<String, Object> definition =
                            new LinkedHashMap<>(Map.of("type", "object", "properties", properties));
                    if (!required.isEmpty()) {
                        definition.put("required", required);
                    }
                    schemas.put(name, definition);
                }
                schema.put("$ref", "#/components/schemas/" + name);
            }
            constraints(schema, annotations);
            return schema;
        }

        private static AnnotatedElement contentAnnotations(AnnotatedElement annotations) {
            AnnotatedType annotated = null;
            if (annotations instanceof Field field) {
                annotated = field.getAnnotatedType();
            } else if (annotations instanceof Parameter parameter) {
                annotated = parameter.getAnnotatedType();
            } else if (annotations instanceof AnnotatedType type) {
                annotated = type;
            }
            if (annotated instanceof AnnotatedParameterizedType parameterized) {
                return parameterized.getAnnotatedActualTypeArguments()[0];
            }
            if (annotated instanceof AnnotatedArrayType array) {
                return array.getAnnotatedGenericComponentType();
            }
            return null;
        }

        private static boolean required(AnnotatedElement annotations) {
            return annotations != null
                    && (annotations.isAnnotationPresent(NotBlank.class)
                            || annotations.isAnnotationPresent(NotNull.class)
                            || annotations.isAnnotationPresent(NotEmpty.class));
        }

        private static void constraints(Map<String, Object> schema, AnnotatedElement annotations) {
            if (annotations == null) {
                return;
            }
            Size size = annotations.getAnnotation(Size.class);
            if (size != null) {
                String suffix =
                        "array".equals(schema.get("type"))
                                ? "Items"
                                : "object".equals(schema.get("type")) ? "Properties" : "Length";
                schema.put("min" + suffix, size.min());
                schema.put("max" + suffix, size.max());
            }
            Min min = annotations.getAnnotation(Min.class);
            Max max = annotations.getAnnotation(Max.class);
            Pattern pattern = annotations.getAnnotation(Pattern.class);
            if (min != null) {
                schema.put("minimum", min.value());
            }
            if (max != null) {
                schema.put("maximum", max.value());
            }
            DecimalMin decimalMin = annotations.getAnnotation(DecimalMin.class);
            DecimalMax decimalMax = annotations.getAnnotation(DecimalMax.class);
            if (decimalMin != null) {
                schema.put(
                        decimalMin.inclusive() ? "minimum" : "exclusiveMinimum",
                        new BigDecimal(decimalMin.value()));
            }
            if (decimalMax != null) {
                schema.put(
                        decimalMax.inclusive() ? "maximum" : "exclusiveMaximum",
                        new BigDecimal(decimalMax.value()));
            }
            if (annotations.isAnnotationPresent(NotBlank.class)
                    || annotations.isAnnotationPresent(NotEmpty.class)) {
                String suffix =
                        "string".equals(schema.get("type"))
                                ? "Length"
                                : "object".equals(schema.get("type")) ? "Properties" : "Items";
                schema.put("min" + suffix, 1);
            }
            BoundedJson bounded = annotations.getAnnotation(BoundedJson.class);
            if (bounded != null) {
                schema.put("x-max-json-bytes", bounded.maxBytes());
                schema.put("x-max-json-depth", bounded.maxDepth());
                schema.put("x-max-json-entries", bounded.maxEntries());
            }
            if (pattern != null) {
                schema.put("pattern", pattern.regexp());
            }
        }

        private static String first(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            throw new IllegalStateException("Mapped parameter lacks a name");
        }
    }
}
