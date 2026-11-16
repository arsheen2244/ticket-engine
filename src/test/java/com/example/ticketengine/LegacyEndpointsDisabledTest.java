package com.example.ticketengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With LEGACY_ENDPOINTS=false the original public API is closed, even to signed-in users. */
@SpringBootTest(properties = {"ticket.legacy-endpoints.enabled=false", "ticket.admin-token="})
@AutoConfigureMockMvc
class LegacyEndpointsDisabledTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReservationService service;

    @Test
    void original_endpoints_are_blocked() throws Exception {
        long id = service.createEvent("legacy-off", 10);
        var user = TestAuth.signup(mvc);
        mvc.perform(post("/api/events/{id}/reserve", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"qty\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/events/{id}/reserve", id).header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"u1\",\"qty\":1}"))
                .andExpect(status().isUnauthorized()); // tokens are ignored on legacy paths, so still anonymous
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"totalTickets\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reservations/by-key/abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void public_reads_still_work() throws Exception {
        long id = service.createEvent("legacy-off-read", 10);
        mvc.perform(get("/api/events/{id}/stats", id)).andExpect(status().isOk());
    }
}
