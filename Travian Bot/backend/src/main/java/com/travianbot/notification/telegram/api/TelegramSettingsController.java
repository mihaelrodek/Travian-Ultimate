package com.travianbot.notification.telegram.api;

import com.travianbot.notification.telegram.TelegramNotifier;
import com.travianbot.notification.telegram.TelegramNotifier.TestResult;
import com.travianbot.notification.telegram.api.dto.TelegramSettingsRequest;
import com.travianbot.notification.telegram.api.dto.TelegramSettingsResponse;
import com.travianbot.notification.telegram.service.TelegramSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the Telegram integration page — same shape as the Discord
 * controller: GET/PUT the singleton settings, POST a synchronous test message.
 */
@RestController
@RequestMapping("/api/telegram")
public class TelegramSettingsController {

    private final TelegramSettingsService settingsService;
    private final TelegramNotifier notifier;

    public TelegramSettingsController(TelegramSettingsService settingsService,
                                      TelegramNotifier notifier) {
        this.settingsService = settingsService;
        this.notifier = notifier;
    }

    @GetMapping("/settings")
    public TelegramSettingsResponse get() {
        return TelegramSettingsResponse.from(settingsService.get());
    }

    @PutMapping("/settings")
    public TelegramSettingsResponse update(@Valid @RequestBody TelegramSettingsRequest req) {
        return TelegramSettingsResponse.from(settingsService.update(req));
    }

    @PostMapping("/test")
    public TestResult test() {
        return notifier.sendTest();
    }
}
