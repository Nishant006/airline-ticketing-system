package com.airline.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateBookingRequestTest {
  @Test
  void validatesStructuralInputWithoutPassengerNameSemantics() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      for (String label : List.of("01A", "1a", " 1A", "0A", "100A")) {
        assertThat(validator.validate(new CreateBookingRequest.Passenger("", label))).isNotEmpty();
      }
      assertThat(validator.validate(new CreateBookingRequest.Passenger("", "1A"))).isEmpty();
      assertThat(
              validator.validate(
                  new CreateBookingRequest("XY101", LocalDate.of(2027, 3, 2), List.of())))
          .isNotEmpty();
    }
  }
}
