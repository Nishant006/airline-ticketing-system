package com.airline.reservation.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingPropertiesTest {
    @Test
    void configurationCannotShrinkOrExpandTheAgreed365DateWindow() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new BookingProperties(365))).isEmpty();
            assertThat(validator.validate(new BookingProperties(364))).isNotEmpty();
            assertThat(validator.validate(new BookingProperties(366))).isNotEmpty();
        }
    }
}
