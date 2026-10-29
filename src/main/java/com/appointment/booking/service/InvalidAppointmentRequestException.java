package com.appointment.booking.service;

public class InvalidAppointmentRequestException extends RuntimeException {

    public InvalidAppointmentRequestException(String message) {
        super(message);
    }
}
