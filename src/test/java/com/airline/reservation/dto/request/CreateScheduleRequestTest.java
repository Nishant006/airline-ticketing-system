package com.airline.reservation.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreateScheduleRequestTest {
    @Test
    void usesLibraryValidationForMissingAndDuplicateWeekdays() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(request(List.of()))).isNotEmpty();
            assertThat(validator.validate(request(List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY))))
                    .isNotEmpty();
            assertThat(validator.validate(request(List.of(DayOfWeek.MONDAY)))).isEmpty();
        }
    }

    @Test
    void overnightTimesRemainValid() {
        CreateScheduleRequest request = request(List.of(DayOfWeek.MONDAY));
        assertThat(request.arrivalTime()).isBefore(request.departureTime());
    }

    private CreateScheduleRequest request(List<DayOfWeek> days) {
        return new CreateScheduleRequest(
                "XY101", "DXB", "LHR", LocalTime.of(23, 0), LocalTime.of(1, 0), "A320-001", days);
    }
}
