package com.airline.reservation.model;

import com.airline.reservation.validation.InputFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record SeatLayout(int rowsCount, String seatLetters) {
    private static final Pattern LABEL_PATTERN = Pattern.compile(InputFormats.SEAT_LABEL);

    public SeatLayout {
        Objects.requireNonNull(seatLetters);
        if (rowsCount < 1
                || rowsCount > 99
                || !seatLetters.matches("[A-Z]{1,10}")
                || seatLetters.chars().distinct().count() != seatLetters.length()) {
            throw new IllegalArgumentException("Invalid fixed aircraft seat configuration");
        }
    }

    public int capacity() {
        return rowsCount * seatLetters.length();
    }

    public boolean containsSeat(String label) {
        if (label == null || !LABEL_PATTERN.matcher(label).matches()) {
            return false;
        }
        int row = Integer.parseInt(label.substring(0, label.length() - 1));
        return row <= rowsCount && seatLetters.indexOf(label.charAt(label.length() - 1)) >= 0;
    }

    public List<String> seatLabels() {
        List<String> labels = new ArrayList<>(capacity());
        for (int row = 1; row <= rowsCount; row++) {
            for (char letter : seatLetters.toCharArray()) {
                labels.add(row + String.valueOf(letter));
            }
        }
        return List.copyOf(labels);
    }
}
