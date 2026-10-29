package com.appointment.booking.service;

import com.appointment.booking.domain.Appointment;

public class AppointmentConflictException extends RuntimeException {

    public AppointmentConflictException(String providerId, Appointment conflictingAppointment) {
        super("Requested time slot conflicts with an existing active appointment for provider " + providerId
                + " (existing appointment: " + conflictingAppointment.startTime() + " to " + conflictingAppointment.endTime() + ")");
    }
}
