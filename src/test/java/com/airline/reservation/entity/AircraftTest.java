package com.airline.reservation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AircraftTest {
    @Test
    void derivesCompleteRowMajorLayoutFromConfiguration() {
        Aircraft aircraft = new Aircraft("A320", "Airbus A320", 30, "ABCDEF");
        assertThat(aircraft.capacity()).isEqualTo(180);
        assertThat(aircraft.seatLabels()).hasSize(180).startsWith("1A", "1B", "1C").endsWith("30F");
        assertThat(aircraft.seatLabels().get(6)).isEqualTo("2A");
    }

    @Test
    void alternateLettersAreNotHardcodedToSixAbreast() {
        Aircraft aircraft = new Aircraft("ATR", "ATR 72", 18, "ABCD");
        assertThat(aircraft.capacity()).isEqualTo(72);
        assertThat(aircraft.containsSeat("18D")).isTrue();
        assertThat(aircraft.containsSeat("18F")).isFalse();
        assertThat(aircraft.containsSeat("19A")).isFalse();
    }

    @Test
    void acceptsOnlyCanonicalLabelsAndValidFixedConfiguration() {
        Aircraft aircraft = new Aircraft("A320", "Airbus", 30, "ABCDEF");
        assertThat(aircraft.containsSeat("01A")).isFalse();
        assertThat(aircraft.containsSeat("1a")).isFalse();
        assertThat(aircraft.containsSeat(null)).isFalse();
        assertThatThrownBy(() -> new Aircraft("BAD", "Bad", 30, "AABC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Aircraft("BAD", "Bad", 100, "ABC"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
