package com.travianbot.botlog.api;

import com.travianbot.botlog.api.dto.BotEventPageResponse;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        return BotEventPageResponse.from(service.list(accountId, outcome, page, size));
    }
}
