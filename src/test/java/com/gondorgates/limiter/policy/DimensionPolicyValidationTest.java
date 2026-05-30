package com.gondorgates.limiter.policy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionPolicyValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void zeroCapacityFailsValidation() {
        DimensionPolicy dp = dimPolicy(0, 1);
        Set<ConstraintViolation<DimensionPolicy>> violations = validator.validate(dp);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("capacity"));
    }

    @Test
    void negativeCapacityFailsValidation() {
        DimensionPolicy dp = dimPolicy(-5, 1);
        assertThat(validator.validate(dp)).isNotEmpty();
    }

    @Test
    void zeroRefillRateFailsValidation() {
        DimensionPolicy dp = dimPolicy(10, 0);
        Set<ConstraintViolation<DimensionPolicy>> violations = validator.validate(dp);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("refillRate"));
    }

    @Test
    void negativeRefillRateFailsValidation() {
        DimensionPolicy dp = dimPolicy(10, -1);
        assertThat(validator.validate(dp)).isNotEmpty();
    }

    @Test
    void validPolicyPassesValidation() {
        DimensionPolicy dp = dimPolicy(10, 2);
        assertThat(validator.validate(dp)).isEmpty();
    }

    private DimensionPolicy dimPolicy(int capacity, int refillRate) {
        DimensionPolicy dp = new DimensionPolicy();
        dp.setType(RateLimitDimension.GLOBAL);
        dp.setCapacity(capacity);
        dp.setRefillRate(refillRate);
        return dp;
    }
}
