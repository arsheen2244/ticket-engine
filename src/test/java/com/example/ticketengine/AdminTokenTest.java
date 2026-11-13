package com.example.ticketengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADMIN_TOKEN is set: the admin endpoints need the right X-Admin-Token header. Needs Postgres and Redis. */
@SpringBootTest(properties = "ticket.admin-token=" + AdminTokenTest.TOKEN)
@AutoConfigureMockMvc
class AdminTokenTest {

    static final String TOKEN = "test-admin-token";
    private static final String EVENT_JSON = "{\"name\":\"admin-test\",\"totalTickets\":10}";

    @Autowired
    MockMvc mvc;

    @Autowired
    ReservationService service;

    @Test
    void create_without_token_is_401() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(EVENT_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_with_wrong_token_is_401() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(EVENT_JSON)
                        .header(AdminTokenInterceptor.HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_with_right_token_succeeds() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(EVENT_JSON)
                        .header(AdminTokenInterceptor.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void reset_without_token_is_401() throws Exception {
        long id = service.createEvent("admin-reset", 10);
        mvc.perform(post("/api/events/{id}/reset", id)).andExpect(status().isUnauthorized());
    }

    @Test
    void reset_with_wrong_token_is_401() throws Exception {
        long id = service.createEvent("admin-reset", 10);
        mvc.perform(post("/api/events/{id}/reset", id).header(AdminTokenInterceptor.HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reset_with_right_token_succeeds() throws Exception {
        long id = service.createEvent("admin-reset", 10);
        mvc.perform(post("/api/events/{id}/reset", id).header(AdminTokenInterceptor.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reset"));
    }

    @Test
    void public_endpoints_need_no_token() throws Exception {
        long id = service.createEvent("admin-public", 10);
        mvc.perform(post("/api/events/{id}/reserve", id).param("strategy", "redis")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"u1\",\"qty\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/events/{id}/stats", id)).andExpect(status().isOk());
    }
}
