package com.appointment.booking.service;

import com.appointment.booking.domain.Appointment;
import com.appointment.booking.domain.AppointmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingServiceTest {

    private static final String PROVIDER_1 = "provider-1";
    private static final String PROVIDER_2 = "provider-2";
    private static final String CUSTOMER_1 = "customer-1";
    private static final String CUSTOMER_2 = "customer-2";

    private static final Instant TEN = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-01-01T11:00:00Z");
    private static final Instant TWELVE = Instant.parse("2026-01-01T12:00:00Z");

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService();
    }

    @Test
    void book_withValidInput_returnsActiveAppointmentWithGeneratedId() {
        Appointment created = service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

        assertNotNull(created.id());
        assertEquals(PROVIDER_1, created.providerId());
        assertEquals(CUSTOMER_1, created.customerId());
        assertEquals(TEN, created.startTime());
        assertEquals(ELEVEN, created.endTime());
        assertEquals(AppointmentStatus.ACTIVE, created.status());
    }

    @Test
    void book_overlappingSameProvider_rejectedWithConflict() {
        service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

        Instant overlappingStart = TEN.plusSeconds(1800);
        Instant overlappingEnd = ELEVEN.plusSeconds(1800);

        assertThrows(AppointmentConflictException.class,
                () -> service.book(PROVIDER_1, CUSTOMER_2, overlappingStart, overlappingEnd));

        assertEquals(1, service.findByProvider(PROVIDER_1).size());
    }

    @Test
    void book_adjacentSameProvider_bothSucceed() {
        Appointment first = service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);
        Appointment second = service.book(PROVIDER_1, CUSTOMER_2, ELEVEN, TWELVE);

        assertEquals(AppointmentStatus.ACTIVE, first.status());
        assertEquals(AppointmentStatus.ACTIVE, second.status());
        assertEquals(2, service.findByProvider(PROVIDER_1).size());
    }

    @Test
    void book_overlappingDifferentProviders_bothSucceed() {
        Appointment first = service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);
        Appointment second = service.book(PROVIDER_2, CUSTOMER_2, TEN, ELEVEN);

        assertEquals(AppointmentStatus.ACTIVE, first.status());
        assertEquals(AppointmentStatus.ACTIVE, second.status());
        assertEquals(1, service.findByProvider(PROVIDER_1).size());
        assertEquals(1, service.findByProvider(PROVIDER_2).size());
    }

    @Test
    void cancel_freesSlot_allowsRebookingSameSlot() {
        Appointment original = service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

        CancellationResult cancellation = service.cancel(original.id());
        assertEquals(CancellationOutcome.CANCELLED, cancellation.outcome());

        Appointment rebooked = service.book(PROVIDER_1, CUSTOMER_2, TEN, ELEVEN);

        assertEquals(AppointmentStatus.ACTIVE, rebooked.status());
        assertNotEquals(original.id(), rebooked.id());
        assertEquals(2, service.findByProvider(PROVIDER_1).size());
    }

    @Test
    void cancel_missingId_returnsNotFound_doesNotMutateState() {
        Appointment unrelated = service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

        CancellationResult result = service.cancel(UUID.randomUUID());

        assertEquals(CancellationOutcome.NOT_FOUND, result.outcome());
        assertNull(result.appointment());

        List<Appointment> providerAppointments = service.findByProvider(PROVIDER_1);
        assertEquals(1, providerAppointments.size());
        assertEquals(unrelated, providerAppointments.get(0));
    }

    @Test
    void cancel_alreadyCancelled_returnsAlreadyCancelled_distinctFromOtherOutcomes() {
        Appointment appointment = service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

        CancellationResult firstCancellation = service.cancel(appointment.id());
        CancellationResult secondCancellation = service.cancel(appointment.id());

        assertEquals(CancellationOutcome.CANCELLED, firstCancellation.outcome());
        assertEquals(CancellationOutcome.ALREADY_CANCELLED, secondCancellation.outcome());
        assertNotEquals(firstCancellation.outcome(), secondCancellation.outcome());

        assertNotNull(secondCancellation.appointment());
        assertEquals(AppointmentStatus.CANCELLED, secondCancellation.appointment().status());

        CancellationResult notFound = service.cancel(UUID.randomUUID());
        assertNotEquals(secondCancellation.outcome(), notFound.outcome());
    }

    // BookingService.book() takes Instant, not a raw timestamp string, so there is no
    // "malformed timestamp string" to pass at this layer - that parsing lives in the
    // controller. These two tests cover the service's actual timestamp-validity boundary.
    @Test
    void book_nullStartTime_rejectedAsInvalid_noStateMutated() {
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book(PROVIDER_1, CUSTOMER_1, null, ELEVEN));

        assertTrue(service.findByProvider(PROVIDER_1).isEmpty());
    }

    @Test
    void book_nullEndTime_rejectedAsInvalid_noStateMutated() {
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book(PROVIDER_1, CUSTOMER_1, TEN, null));

        assertTrue(service.findByProvider(PROVIDER_1).isEmpty());
    }

    @Test
    void book_endBeforeStart_rejectedAsNonPositiveDuration_noStateMutated() {
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book(PROVIDER_1, CUSTOMER_1, ELEVEN, TEN));

        assertTrue(service.findByProvider(PROVIDER_1).isEmpty());
    }

    @Test
    void book_blankProviderId_rejectedAsInvalid_noStateMutated() {
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book("   ", CUSTOMER_1, TEN, ELEVEN));

        assertTrue(service.findByProvider("   ").isEmpty());
    }

    // customerId is also a required identifier on BookingService.book(), parallel to
    // providerId, but wasn't in the original list - adding it for the same coverage.
    @Test
    void book_blankCustomerId_rejectedAsInvalid_noStateMutated() {
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book(PROVIDER_1, "  ", TEN, ELEVEN));

        assertTrue(service.findByProvider(PROVIDER_1).isEmpty());
    }

    @Test
    void book_exactDuplicateSlot_rejectedAsOverlap() {
        service.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

        assertThrows(AppointmentConflictException.class,
                () -> service.book(PROVIDER_1, CUSTOMER_2, TEN, ELEVEN));

        assertEquals(1, service.findByProvider(PROVIDER_1).size());
    }

    @Test
    void book_zeroDuration_rejectedAsNonPositiveDuration_noStateMutated() {
        assertThrows(InvalidAppointmentRequestException.class,
                () -> service.book(PROVIDER_1, CUSTOMER_1, TEN, TEN));

        assertTrue(service.findByProvider(PROVIDER_1).isEmpty());
    }

    // Original acceptance criteria called for a focused concurrency test that wasn't in
    // the list for this pass - adding it back since it's a core service-level guarantee.
    @Test
    void book_concurrentAttemptsSameSlot_onlyOneSucceeds() throws Exception {
        int attempts = 20;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            String customerId = "customer-" + i;
            futures.add(executor.submit(() -> {
                startGate.await();
                try {
                    service.book(PROVIDER_1, customerId, TEN, ELEVEN);
                    return true;
                } catch (AppointmentConflictException e) {
                    return false;
                }
            }));
        }

        startGate.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successCount++;
            }
        }
        executor.shutdown();

        assertEquals(1, successCount, "exactly one concurrent booking attempt should succeed for the same slot");
        assertEquals(1, service.findByProvider(PROVIDER_1).size());
    }

    // book() and cancel() share the same per-provider lock, so only two orderings are legal:
    // cancel-wins (appointment is CANCELLED before book's overlap scan runs, so book succeeds)
    // or book-wins (book sees the still-ACTIVE appointment and is rejected, then cancel runs).
    // Looped with a fresh service each iteration to surface any ordering that isn't one of these two.
    @Test
    void cancelBookRace_repeatedly_alwaysReachesValidEndState() throws Exception {
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            for (int i = 0; i < iterations; i++) {
                BookingService raceService = new BookingService();
                Appointment original = raceService.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);

                CountDownLatch startGate = new CountDownLatch(1);

                Future<CancellationResult> cancelFuture = executor.submit(() -> {
                    startGate.await();
                    return raceService.cancel(original.id());
                });
                Future<Boolean> bookFuture = executor.submit(() -> {
                    startGate.await();
                    try {
                        raceService.book(PROVIDER_1, CUSTOMER_2, TEN, ELEVEN);
                        return true;
                    } catch (AppointmentConflictException e) {
                        return false;
                    }
                });

                startGate.countDown();

                boolean bookSucceeded = bookFuture.get();
                CancellationResult cancelResult = cancelFuture.get();

                assertEquals(CancellationOutcome.CANCELLED, cancelResult.outcome(),
                        "the single cancel() call on a known-active appointment must always report CANCELLED, iteration " + i);

                List<Appointment> providerAppointments = raceService.findByProvider(PROVIDER_1);
                long activeCount = providerAppointments.stream()
                        .filter(a -> a.status() == AppointmentStatus.ACTIVE)
                        .count();

                assertTrue(activeCount <= 1,
                        "never more than one active appointment may occupy the slot, iteration " + i);

                if (bookSucceeded) {
                    assertEquals(2, providerAppointments.size(), "iteration " + i);
                    assertEquals(1, activeCount, "iteration " + i);
                } else {
                    assertEquals(1, providerAppointments.size(), "iteration " + i);
                    assertEquals(0, activeCount, "iteration " + i);
                }
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrentBookingSameNewSlot_repeatedly_exactlyOneSucceedsEveryIteration() throws Exception {
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            for (int i = 0; i < iterations; i++) {
                BookingService raceService = new BookingService();
                CountDownLatch startGate = new CountDownLatch(1);

                Future<Boolean> firstAttempt = executor.submit(() -> {
                    startGate.await();
                    try {
                        raceService.book(PROVIDER_1, CUSTOMER_1, TEN, ELEVEN);
                        return true;
                    } catch (AppointmentConflictException e) {
                        return false;
                    }
                });
                Future<Boolean> secondAttempt = executor.submit(() -> {
                    startGate.await();
                    try {
                        raceService.book(PROVIDER_1, CUSTOMER_2, TEN, ELEVEN);
                        return true;
                    } catch (AppointmentConflictException e) {
                        return false;
                    }
                });

                startGate.countDown();

                boolean firstSucceeded = firstAttempt.get();
                boolean secondSucceeded = secondAttempt.get();

                assertNotEquals(firstSucceeded, secondSucceeded,
                        "exactly one of the two concurrent booking attempts must succeed, iteration " + i);

                List<Appointment> providerAppointments = raceService.findByProvider(PROVIDER_1);
                assertEquals(1, providerAppointments.size(), "iteration " + i);
                assertEquals(AppointmentStatus.ACTIVE, providerAppointments.get(0).status(), "iteration " + i);
            }
        } finally {
            executor.shutdown();
        }
    }
}
