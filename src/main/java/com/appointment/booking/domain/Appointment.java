package com.appointment.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Appointment(
        UUID id,
        String providerId,
        String customerId,
        Instant startTime,
        Instant endTime,
        AppointmentStatus status) {

    public Appointment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public Appointment withStatus(AppointmentStatus newStatus) {
        return new Appointment(id, providerId, customerId, startTime, endTime, newStatus);
    }
}
