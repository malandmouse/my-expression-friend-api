package com.planb.emotionfriend.api.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Apply only to DTOs whose contract actually defines schemaVersion. Null is not version 0.3.0. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SchemaVersionValidator.class)
public @interface SchemaVersion {
    String message() default "Unsupported schema version";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
