package com.travianbot.notification.discord.api;

import com.travianbot.notification.discord.DiscordNotifier;
import com.travianbot.notification.discord.DiscordNotifier.TestResult;
import com.travianbot.notification.discord.api.dto.DiscordSettingsRequest;
import com.travianbot.notification.discord.api.dto.DiscordSettingsResponse;
import com.travianbot.notification.discord.service.DiscordSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the Discord integration page.
 * <ul>
 *   <li>{@code GET  /api/discord/settings} — current config.</li>
 *   <li>{@code PUT  /api/discord/settings} — replace the whole row.</li>
 *   <li>{@code POST /api/discord/test} — synchronously post a test message
 *       to whatever webhook is currently saved. Returns the result so the
 *       UI can render a success / failure banner immediately.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/discord")
public class DiscordSettingsController {

    private final DiscordSettingsService settingsService;
    private final DiscordNotifier notifier;

    public DiscordSettingsController(DiscordSettingsService settingsService,
                                     DiscordNotifier notifier) {
        this.settingsService = settingsService;
        this.notifier = notifier;
    }

    @GetMapping("/settings")
    public DiscordSettingsResponse get() {
        return DiscordSettingsResponse.from(settingsService.get());
    }

    @PutMapping("/settings")
    public DiscordSettingsResponse update(@Valid @RequestBody DiscordSettingsRequest req) {
        return DiscordSettingsResponse.from(settingsService.update(req));
    }

    @PostMapping("/test")
    public TestResult test() {
        return notifier.sendTest();
    }
}
