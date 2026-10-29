package com.appointment.booking.web;

import com.appointment.booking.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full Spring context + the real BookingService bean (no Mockito mocking here - Mockito's
// inline class mocking is incompatible with this JDK's bytecode format, so "the service was
// never invoked" is proven by checking no appointment was stored, not by verifying a mock).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingService bookingService;

    @Test
    void book_malformedTimestamp_returns400AndNeverStoresAppointment() throws Exception {
        String providerId = "provider-malformed-timestamp";
        String body = objectMapper.writeValueAsString(Map.of(
                "providerId", providerId,
                "customerId", "customer-1",
                "startTime", "not-a-timestamp",
                "endTime", "2026-01-01T11:00:00Z"
        ));

        mockMvc.perform(post("/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("startTime")));

        assertTrue(bookingService.findByProvider(providerId).isEmpty());
    }

    @Test
    void book_timestampEndingInZButUnparseable_returns400AndNeverStoresAppointment() throws Exception {
        String providerId = "provider-unparseable-timestamp";
        String body = objectMapper.writeValueAsString(Map.of(
                "providerId", providerId,
                "customerId", "customer-1",
                "startTime", "2026-13-45T99:99:00Z",
                "endTime", "2026-01-01T11:00:00Z"
        ));

        mockMvc.perform(post("/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("startTime")));

        assertTrue(bookingService.findByProvider(providerId).isEmpty());
    }

    @Test
    void book_nonZOffsetTimestamp_returns400AndNeverStoresAppointment() throws Exception {
        String providerId = "provider-offset-timestamp";
        String body = objectMapper.writeValueAsString(Map.of(
                "providerId", providerId,
                "customerId", "customer-1",
                "startTime", "2026-01-01T10:00:00+05:00",
                "endTime", "2026-01-01T11:00:00Z"
        ));

        mockMvc.perform(post("/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("startTime")));

        assertTrue(bookingService.findByProvider(providerId).isEmpty());
    }
}
