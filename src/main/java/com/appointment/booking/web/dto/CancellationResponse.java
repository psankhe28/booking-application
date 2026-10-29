package com.appointment.booking.web.dto;

import com.appointment.booking.service.CancellationOutcome;

public record CancellationResponse(CancellationOutcome result, AppointmentResponse appointment) {
}
