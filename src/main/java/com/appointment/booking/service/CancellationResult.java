package com.appointment.booking.service;

import com.appointment.booking.domain.Appointment;

// appointment is null only when outcome == NOT_FOUND
public record CancellationResult(CancellationOutcome outcome, Appointment appointment) {
}
