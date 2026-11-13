package com.example.ticketengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Opens the real SSE stream over HTTP and checks that bookings and resets show up in it. Needs Postgres and Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ticket.live.interval-ms=100")
class LiveStockTest {

    @LocalServerPort
    int port;

    @Autowired
    ReservationService service;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Reads "data:" lines of the stream on a background thread. */
    private BlockingQueue<String> openStream(long eventId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + eventId + "/stream"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        BlockingQueue<String> data = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("data:")) data.add(line.substring(5));
                }
            } catch (Exception ignored) {
                // stream closed at the end of the test
            }
        });
        reader.setDaemon(true);
        reader.start();
        return data;
    }

    private static String next(BlockingQueue<String> q) throws InterruptedException {
        String v = q.poll(5, TimeUnit.SECONDS);
        assertThat(v).as("an SSE update within 5 s").isNotNull();
        return v;
    }

    @Test
    void stream_sends_the_current_count_then_every_change() throws Exception {
        long id = service.createEvent("live", 3);
        BlockingQueue<String> updates = openStream(id);
        assertThat(next(updates)).contains("\"remaining\":3").contains("\"soldOut\":false");

        service.reserve(id, "live-a", 2, UUID.randomUUID().toString(), Strategy.REDIS);
        assertThat(next(updates)).contains("\"remaining\":1");

        service.reserve(id, "live-b", 1, UUID.randomUUID().toString(), Strategy.REDIS);
        assertThat(next(updates)).contains("\"remaining\":0").contains("\"soldOut\":true");

        service.reset(id);
        assertThat(next(updates)).contains("\"remaining\":3");
    }

    @Test
    void two_viewers_both_receive_updates() throws Exception {
        long id = service.createEvent("live-two", 5);
        BlockingQueue<String> a = openStream(id);
        BlockingQueue<String> b = openStream(id);
        next(a);
        next(b);
        service.reserve(id, "live-c", 1, UUID.randomUUID().toString(), Strategy.REDIS);
        assertThat(next(a)).contains("\"remaining\":4");
        assertThat(next(b)).contains("\"remaining\":4");
    }

    @Test
    void unknown_event_is_404() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + Long.MAX_VALUE + "/stream"))
                .GET().build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }
}
