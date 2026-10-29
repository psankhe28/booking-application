package com.appointment.booking.web;

import com.appointment.booking.domain.Appointment;
import com.appointment.booking.service.AppointmentConflictException;
import com.appointment.booking.service.BookingService;
import com.appointment.booking.service.CancellationOutcome;
import com.appointment.booking.service.CancellationResult;
import com.appointment.booking.service.InvalidAppointmentRequestException;
import com.appointment.booking.web.dto.AppointmentResponse;
import com.appointment.booking.web.dto.BookingRequest;
import com.appointment.booking.web.dto.CancellationResponse;
import com.appointment.booking.web.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class AppointmentController {

    private final BookingService bookingService;

    public AppointmentController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "Book an appointment",
            description = "Books a provider for a UTC time interval. Rejected with 409 if it overlaps an existing "
                    + "active appointment for the same provider; adjacent intervals (one ends exactly when the next "
                    + "begins) are not an overlap.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Appointment booked",
                    content = @Content(schema = @Schema(implementation = AppointmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request (blank providerId/customerId, "
                    + "missing/malformed/non-\"Z\" timestamp, non-positive duration, or malformed JSON body)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conflicts with an existing active appointment for the same provider",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/appointments")
    public ResponseEntity<AppointmentResponse> book(@RequestBody BookingRequest request) {
        validateShape(request);

        Instant startTime = parseInstant("startTime", request.startTime());
        Instant endTime = parseInstant("endTime", request.endTime());
        if (!endTime.isAfter(startTime)) {
            throw new InvalidAppointmentRequestException("endTime must be after startTime");
        }

        Appointment created = bookingService.book(request.providerId(), request.customerId(), startTime, endTime);
        return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(created));
    }

    @Operation(summary = "Cancel an appointment",
            description = "Cancelling an already-cancelled appointment is not an error: it returns 200 with "
                    + "result=ALREADY_CANCELLED, distinguishable from a fresh result=CANCELLED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled, or already cancelled (see the result field)",
                    content = @Content(schema = @Schema(implementation = CancellationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No appointment exists with this id",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "{id} is not a syntactically valid appointment id",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/appointments/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("id") String id) {
        UUID appointmentId = parseAppointmentId(id);

        CancellationResult result = bookingService.cancel(appointmentId);
        if (result.outcome() == CancellationOutcome.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("NOT_FOUND", "No appointment exists with id " + id));
        }

        return ResponseEntity.ok(new CancellationResponse(result.outcome(), AppointmentResponse.from(result.appointment())));
    }

    @Operation(summary = "List a provider's appointments",
            description = "Manual-verification convenience endpoint; returns active and cancelled appointments "
                    + "for the given provider, in booking order.")
    @ApiResponse(responseCode = "200", description = "The provider's appointments (possibly empty)",
            content = @Content(schema = @Schema(implementation = AppointmentResponse.class)))
    @GetMapping("/appointments/{providerId}")
    public List<AppointmentResponse> getByProvider(@PathVariable String providerId) {
        return bookingService.findByProvider(providerId).stream()
                .map(AppointmentResponse::from)
                .collect(Collectors.toList());
    }

    private void validateShape(BookingRequest request) {
        if (request == null) {
            throw new InvalidAppointmentRequestException("request body is required");
        }
        if (isBlank(request.providerId())) {
            throw new InvalidAppointmentRequestException("providerId is required");
        }
        if (isBlank(request.customerId())) {
            throw new InvalidAppointmentRequestException("customerId is required");
        }
        if (isBlank(request.startTime())) {
            throw new InvalidAppointmentRequestException("startTime is required");
        }
        if (isBlank(request.endTime())) {
            throw new InvalidAppointmentRequestException("endTime is required");
        }
    }

    private Instant parseInstant(String fieldName, String rawValue) {
        if (!rawValue.endsWith("Z")) {
            throw new InvalidAppointmentRequestException(fieldName + " must be an ISO-8601 UTC instant ending in 'Z'");
        }
        try {
            return Instant.parse(rawValue);
        } catch (DateTimeParseException e) {
            throw new InvalidAppointmentRequestException(fieldName + " is not a valid ISO-8601 instant");
        }
    }

    private UUID parseAppointmentId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidAppointmentRequestException("id is not a valid appointment id");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @ExceptionHandler(InvalidAppointmentRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidAppointmentRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(AppointmentConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(AppointmentConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", "request body is not valid JSON"));
    }
}
