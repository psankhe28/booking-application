package com.appointment.booking.web.dto;

public record BookingRequest(String providerId, String customerId, String startTime, String endTime) {
}
