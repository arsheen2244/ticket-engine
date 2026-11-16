package com.example.ticketengine;

import com.example.ticketengine.auth.AuthCookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Event list/detail, bookings, my orders and admin endpoints in the default (synchronous redis) mode.
 * Same properties as AuthControllerTest so both share one Spring context. Needs Postgres and Redis.
 */
@SpringBootTest(properties = {"ticket.admin.email=boss@example.com", "ticket.admin.password=admin-pass-123"})
@AutoConfigureMockMvc
class WebAppApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReservationService service;

    private final ObjectMapper json = new ObjectMapper();

    private static String newKey() { return UUID.randomUUID().toString(); }

    private ResultActions book(TestAuth.Account user, long eventId, int qty, String key) throws Exception {
        return mvc.perform(post("/api/bookings").header(HttpHeaders.AUTHORIZATION, user.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":" + eventId + ",\"qty\":" + qty + "}"));
    }

    private TestAuth.Account admin() throws Exception {
        return TestAuth.login(mvc, "boss@example.com", "admin-pass-123");
    }

    // ---- events ----

    @Test
    void event_list_and_detail_are_public_and_show_live_remaining() throws Exception {
        long id = service.createEvent("list-test", 7);
        mvc.perform(get("/api/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem((int) id)));
        mvc.perform(get("/api/events/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("list-test"))
                .andExpect(jsonPath("$.totalTickets").value(7))
                .andExpect(jsonPath("$.remaining").value(7))
                .andExpect(jsonPath("$.soldOut").value(false));
    }

    @Test
    void unknown_event_is_404_with_a_message() throws Exception {
        mvc.perform(get("/api/events/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Event not found"));
    }

    @Test
    void a_stale_login_cookie_does_not_break_public_pages() throws Exception {
        mvc.perform(get("/api/events").cookie(new Cookie(AuthCookie.NAME, "expired.or.garbage")))
                .andExpect(status().isOk());
    }

    // ---- bookings ----

    @Test
    void booking_needs_a_login() throws Exception {
        long id = service.createEvent("book-anon", 5);
        mvc.perform(post("/api/bookings").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":" + id + ",\"qty\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void booking_needs_a_well_formed_idempotency_key() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("book-key", 5);
        mvc.perform(post("/api/bookings").header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":" + id + ",\"qty\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing header Idempotency-Key"));
        book(user, id, 1, "bad key!").andExpect(status().isBadRequest());
    }

    @Test
    void booking_validates_quantity_and_event() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("book-qty", 10);
        book(user, id, 5, newKey()).andExpect(status().isBadRequest());
        book(user, Long.MAX_VALUE, 1, newKey()).andExpect(status().isNotFound());
    }

    @Test
    void booking_reserves_tickets_for_the_signed_in_user() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("book-ok", 5);
        String key = newKey();
        book(user, id, 2, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.bookingKey").value(key))
                .andExpect(jsonPath("$.order.qty").value(2))
                .andExpect(jsonPath("$.order.eventName").value("book-ok"));
        mvc.perform(get("/api/events/{id}", id)).andExpect(jsonPath("$.remaining").value(3));
        mvc.perform(get("/api/bookings/{key}", key).param("eventId", String.valueOf(id))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void repeating_the_key_returns_the_same_booking() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("book-repeat", 5);
        String key = newKey();
        String first = book(user, id, 1, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = book(user, id, 1, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second).at("/order/id")).isEqualTo(json.readTree(first).at("/order/id"));
        mvc.perform(get("/api/events/{id}", id)).andExpect(jsonPath("$.remaining").value(4));
    }

    @Test
    void twenty_simultaneous_clicks_with_one_key_book_once() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("book-double-click", 10);
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> statuses = ConcurrentHashMap.newKeySet();
        var futures = new HashSet<Future<?>>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                statuses.add(book(user, id, 1, key).andReturn().getResponse().getStatus());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(statuses).isSubsetOf(201, 202); // created, or "already in progress"
        var stats = service.stats(id);
        assertThat(((Number) stats.get("reservations")).longValue()).isEqualTo(1);
        assertThat(((Number) stats.get("db_available")).longValue()).isEqualTo(9);
    }

    @Test
    void sold_out_is_409_and_unknown_booking_is_failed() throws Exception {
        var a = TestAuth.signup(mvc);
        var b = TestAuth.signup(mvc);
        long id = service.createEvent("book-soldout", 1);
        book(a, id, 1, newKey()).andExpect(status().isCreated());
        String lost = newKey();
        book(b, id, 1, lost).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Sold out"));
        mvc.perform(get("/api/events/{id}", id)).andExpect(jsonPath("$.soldOut").value(true));
        mvc.perform(get("/api/bookings/{key}", lost).param("eventId", String.valueOf(id))
                        .header(HttpHeaders.AUTHORIZATION, b.bearer()))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void per_user_limit_is_429() throws Exception {
        var user = TestAuth.signup(mvc);
        long id = service.createEvent("book-limit", 20);
        book(user, id, 4, newKey()).andExpect(status().isCreated());
        book(user, id, 1, newKey()).andExpect(status().isTooManyRequests());
    }

    // ---- my orders ----

    @Test
    void users_see_and_pay_only_their_own_orders() throws Exception {
        var alice = TestAuth.signup(mvc);
        var bob = TestAuth.signup(mvc);
        long id = service.createEvent("orders", 10);
        String body = book(alice, id, 1, newKey()).andReturn().getResponse().getContentAsString();
        String orderId = json.readTree(body).at("/order/id").asText();

        mvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, alice.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(orderId));
        mvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, bob.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/me/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, bob.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/me/orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, bob.bearer()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/me/orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, alice.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/me/orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, alice.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("This order is already paid"));
    }

    @Test
    void my_orders_need_a_login() throws Exception {
        mvc.perform(get("/api/me/orders")).andExpect(status().isUnauthorized());
    }

    // ---- admin ----

    @Test
    void admin_endpoints_reject_anonymous_and_plain_users() throws Exception {
        var user = TestAuth.signup(mvc);
        mvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stats").header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("You do not have access to this"));
        mvc.perform(post("/api/admin/events").header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"totalTickets\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_creates_a_sale_and_sees_its_stats() throws Exception {
        var admin = admin();
        String body = mvc.perform(post("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Admin Sale\",\"totalTickets\":50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remaining").value(50))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();

        var buyer = TestAuth.signup(mvc);
        book(buyer, id, 3, newKey()).andExpect(status().isCreated());

        mvc.perform(get("/api/admin/stats").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.events").isNumber())
                .andExpect(jsonPath("$.sales[?(@.id == " + id + ")].ticketsSold").value(hasItem(3)))
                .andExpect(jsonPath("$.sales[?(@.id == " + id + ")].remaining").value(hasItem(47)))
                .andExpect(jsonPath("$.sales[?(@.id == " + id + ")].oversold").value(hasItem(false)));

        mvc.perform(post("/api/admin/events/{id}/reset", id).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(50));
    }

    @Test
    void admin_create_validates_input() throws Exception {
        mvc.perform(post("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"totalTickets\":0}"))
                .andExpect(status().isBadRequest());
    }
}
