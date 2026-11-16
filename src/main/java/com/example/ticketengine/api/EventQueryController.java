package com.example.ticketengine.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public reads for the event list and event detail pages. */
@RestController
@RequestMapping("/api/events")
public class EventQueryController {

    private final EventCatalog catalog;

    public EventQueryController(EventCatalog catalog) { this.catalog = catalog; }

    /** The 50 newest events, newest first. */
    @GetMapping
    public List<EventView> list() { return catalog.recent(); }

    @GetMapping("/{id}")
    public EventView get(@PathVariable long id) { return catalog.get(id); }
}
