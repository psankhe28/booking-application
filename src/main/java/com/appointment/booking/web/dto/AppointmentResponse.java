package com.appointment.booking.web.dto;

import com.appointment.booking.domain.Appointment;
import com.appointment.booking.domain.AppointmentStatus;

import java.time.Instant;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        String providerId,
        String customerId,
        Instant startTime,
        Instant endTime,
        AppointmentStatus status) {

    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.id(),
                appointment.providerId(),
                appointment.customerId(),
                appointment.startTime(),
                appointment.endTime(),
                appointment.status());
    }
}
