package com.example.ticketengine.live;

import com.example.ticketengine.api.EventCatalog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Public SSE stream of an event's remaining tickets. The browser opens it with {@code new EventSource(url)}. */
@RestController
public class LiveStockController {

    private final LiveStockHub hub;
    private final EventCatalog catalog;

    public LiveStockController(LiveStockHub hub, EventCatalog catalog) {
        this.hub = hub;
        this.catalog = catalog;
    }

    @GetMapping(path = "/api/events/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id, HttpServletResponse response) {
        long remaining = catalog.get(id).remaining(); // 404 for an unknown event
        // no-transform stops proxies (including Next.js's gzip) from buffering the stream.
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe(id, remaining);
    }
}
