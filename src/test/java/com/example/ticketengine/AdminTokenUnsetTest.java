package com.example.ticketengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADMIN_TOKEN is empty: the admin endpoints stay open (local dev behaviour). The property is set
 * explicitly so the test also passes when ADMIN_TOKEN happens to be set in the shell.
 */
@SpringBootTest(properties = "ticket.admin-token=")
@AutoConfigureMockMvc
class AdminTokenUnsetTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReservationService service;

    @Test
    void create_and_reset_are_open_without_a_token() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"open-test\",\"totalTickets\":10}"))
                .andExpect(status().isOk());

        long id = service.createEvent("open-reset", 10);
        mvc.perform(post("/api/events/{id}/reset", id)).andExpect(status().isOk());
    }
}
