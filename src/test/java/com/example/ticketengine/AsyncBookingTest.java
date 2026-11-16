package com.example.ticketengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With TICKET_ASYNC=true a booking is queued (202) and the Kafka consumer stores it shortly after. */
@SpringBootTest(properties = "ticket.async=true")
@EmbeddedKafka(partitions = 3, topics = {Topics.REQUESTS, Topics.EVENTS},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@AutoConfigureMockMvc
class AsyncBookingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReservationService service;

    @Test
    void booking_goes_from_queued_to_pending_payment() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("async-book", 3);
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/bookings").header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":" + id + ",\"qty\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.order").doesNotExist());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/bookings/{key}", key).param("eventId", String.valueOf(id))
                                .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                        .andExpect(jsonPath("$.order.qty").value(1)));

        String orders = mvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(new ObjectMapper().readTree(orders).size()).isEqualTo(1);
    }

    @Test
    void sold_out_is_rejected_at_the_redis_gate_before_kafka() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("async-soldout", 1);
        service.setRedisStock(id, 0);
        mvc.perform(post("/api/bookings").header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":" + id + ",\"qty\":1}"))
                .andExpect(status().isConflict());
    }
}
