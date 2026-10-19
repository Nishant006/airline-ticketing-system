package com.airline.reservation.entity;

import com.airline.reservation.model.SeatLayout;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "aircraft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Aircraft {
    @Id
    @Column(length = 16)
    private String id;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(name = "rows_count", nullable = false)
    private short rowsCount;

    @Column(name = "seat_letters", nullable = false, length = 10)
    private String seatLetters;

    public Aircraft(String id, String type, int rowsCount, String seatLetters) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(seatLetters);
        new SeatLayout(rowsCount, seatLetters);
        this.id = id;
        this.type = type;
        this.rowsCount = (short) rowsCount;
        this.seatLetters = seatLetters;
    }

    public int capacity() {
        return seatLayout().capacity();
    }

    public boolean containsSeat(String label) {
        return seatLayout().containsSeat(label);
    }

    public List<String> seatLabels() {
        return seatLayout().seatLabels();
    }

    private SeatLayout seatLayout() {
        return new SeatLayout(rowsCount, seatLetters);
    }
}
