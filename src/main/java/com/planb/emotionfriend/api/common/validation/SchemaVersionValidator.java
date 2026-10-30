package com.planb.emotionfriend.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SchemaVersionValidator implements ConstraintValidator<SchemaVersion, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) { return "0.3.0".equals(value); }
}
