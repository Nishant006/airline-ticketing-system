package com.airline.reservation.validation;

public final class InputFormats {
    public static final String AIRPORT_CODE = "^[A-Z]{3}$";
    public static final String FLIGHT_NUMBER = "^[A-Z0-9]{2,8}$";
    public static final String SEAT_LABEL = "^[1-9][0-9]?[A-Z]$";

    private InputFormats() {
    }
}
