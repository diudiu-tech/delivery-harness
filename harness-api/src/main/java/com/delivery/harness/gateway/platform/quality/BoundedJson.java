package com.delivery.harness.gateway.platform.quality;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT,
    ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BoundedJsonValidator.class)
public @interface BoundedJson {
    String message() default "JSON exceeds the permitted size or nesting bounds";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int maxBytes() default 65_536;

    int maxDepth() default 12;

    int maxEntries() default 200;
}
