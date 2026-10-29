package com.appointment.booking.service;

import com.appointment.booking.domain.Appointment;
import com.appointment.booking.domain.AppointmentStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BookingService {

    private final Map<String, Object> providerLocks = new ConcurrentHashMap<>();
    private final Map<String, List<Appointment>> appointmentsByProvider = new ConcurrentHashMap<>();
    private final Map<UUID, Appointment> appointmentsById = new ConcurrentHashMap<>();

    public Appointment book(String providerId, String customerId, Instant startTime, Instant endTime) {
        validateBookingRequest(providerId, customerId, startTime, endTime);

        Object lock = lockFor(providerId);
        synchronized (lock) {
            List<Appointment> providerAppointments = appointmentsByProvider.computeIfAbsent(providerId, id -> new ArrayList<>());

            for (Appointment existing : providerAppointments) {
                if (existing.status() == AppointmentStatus.ACTIVE && overlaps(startTime, endTime, existing)) {
                    throw new AppointmentConflictException(providerId, existing);
                }
            }

            Appointment created = new Appointment(UUID.randomUUID(), providerId, customerId, startTime, endTime, AppointmentStatus.ACTIVE);
            providerAppointments.add(created);
            appointmentsById.put(created.id(), created);
            return created;
        }
    }

    public CancellationResult cancel(UUID appointmentId) {
        Objects.requireNonNull(appointmentId, "appointmentId must not be null");

        // providerId is final on an already-published, immutable Appointment, so this read is safe without the lock
        Appointment existing = appointmentsById.get(appointmentId);
        if (existing == null) {
            return new CancellationResult(CancellationOutcome.NOT_FOUND, null);
        }

        Object lock = lockFor(existing.providerId());
        synchronized (lock) {
            // re-read inside the lock in case a concurrent cancel() already transitioned this appointment's status
            Appointment current = appointmentsById.get(appointmentId);
            if (current.status() == AppointmentStatus.CANCELLED) {
                return new CancellationResult(CancellationOutcome.ALREADY_CANCELLED, current);
            }

            Appointment cancelled = current.withStatus(AppointmentStatus.CANCELLED);
            appointmentsById.put(appointmentId, cancelled);
            replaceInProviderList(cancelled);
            return new CancellationResult(CancellationOutcome.CANCELLED, cancelled);
        }
    }

    public List<Appointment> findByProvider(String providerId) {
        List<Appointment> providerAppointments = appointmentsByProvider.get(providerId);
        if (providerAppointments == null) {
            return List.of();
        }

        Object lock = lockFor(providerId);
        synchronized (lock) {
            return List.copyOf(providerAppointments);
        }
    }

    private Object lockFor(String providerId) {
        return providerLocks.computeIfAbsent(providerId, id -> new Object());
    }

    private boolean overlaps(Instant startTime, Instant endTime, Appointment other) {
        return startTime.isBefore(other.endTime()) && other.startTime().isBefore(endTime);
    }

    // keeps the provider's overlap-scan list in sync with the id-indexed map after a status change
    private void replaceInProviderList(Appointment updated) {
        List<Appointment> providerAppointments = appointmentsByProvider.get(updated.providerId());
        if (providerAppointments == null) {
            return;
        }
        for (int i = 0; i < providerAppointments.size(); i++) {
            if (providerAppointments.get(i).id().equals(updated.id())) {
                providerAppointments.set(i, updated);
                break;
            }
        }
    }

    private void validateBookingRequest(String providerId, String customerId, Instant startTime, Instant endTime) {
        if (isBlank(providerId)) {
            throw new InvalidAppointmentRequestException("providerId is required");
        }
        if (isBlank(customerId)) {
            throw new InvalidAppointmentRequestException("customerId is required");
        }
        if (startTime == null) {
            throw new InvalidAppointmentRequestException("startTime is required");
        }
        if (endTime == null) {
            throw new InvalidAppointmentRequestException("endTime is required");
        }
        if (!endTime.isAfter(startTime)) {
            throw new InvalidAppointmentRequestException("endTime must be after startTime");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
