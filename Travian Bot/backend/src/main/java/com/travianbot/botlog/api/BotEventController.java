package com.travianbot.botlog.api;

import com.travianbot.botlog.api.dto.BotEventPageResponse;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Read-only endpoint for the Log screen.
 * <p>
 * Writes happen inline from the services that perform the underlying action
 * (ScrapingService, VillageService, FarmlistSendService, LoginService, …)
 * via {@link BotEventService#record}. There's no POST here: nothing in the
 * UI should be able to insert arbitrary log rows.
 */
@RestController
@RequestMapping("/api/bot-log")
public class BotEventController {

    private static final int DEFAULT_SIZE = 50;

    private final BotEventService service;

    public BotEventController(BotEventService service) {
        this.service = service;
    }

    @GetMapping
    public BotEventPageResponse list(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) BotEventOutcome outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        return BotEventPageResponse.from(service.list(
                accountId, outcome, parseInstant(from, "from"), parseInstant(to, "to"), q, page, size));
    }

    /**
     * Parsed by hand instead of {@code @RequestParam Instant} so a malformed
     * timestamp surfaces as a clean 400 through the {@code
     * IllegalArgumentException} handler rather than falling into the generic
     * 500 catch-all.
     */
    private static Instant parseInstant(String value, String param) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Query param '" + param + "' must be an ISO-8601 instant (e.g. 2026-07-05T12:00:00Z), got: " + value);
        }
    }
}
