package com.travianbot.notification.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.service.ReturnCatchPlanner.ReturnCatchSuggestion;
import com.travianbot.notification.EventNotifier;
import com.travianbot.notification.telegram.domain.TelegramSettings;
import com.travianbot.notification.telegram.service.TelegramSettingsService;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram Bot API push channel (roadmap C2) — the second {@link EventNotifier}
 * alongside {@code DiscordNotifier}, wired through {@code NotificationDispatcher}.
 * <p>
 * Delivery is a single {@code POST https://api.telegram.org/bot<token>/sendMessage}
 * with an HTML-formatted text body. Telegram has no Discord-style embeds, so each
 * event renders as a compact bold-titled message. Every public {@code notifyXxx}
 * runs the same guard — master {@code enabled}? per-event toggle? token AND chat
 * id set? — then a per-trigger debounce, mirroring the Discord notifier so the two
 * channels behave identically.
 */
@Service
public class TelegramNotifier implements EventNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);
    /** Per-trigger debounce, same rationale + window as the Discord notifier. */
    private static final Duration DEBOUNCE = Duration.ofMinutes(5);

    private final TelegramSettingsService settingsService;
    private final VillageRepository villageRepository;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final Map<String, Instant> lastFiredByKey = new ConcurrentHashMap<>();

    public TelegramNotifier(TelegramSettingsService settingsService,
                            VillageRepository villageRepository,
                            AccountRepository accountRepository,
                            ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.villageRepository = villageRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    @Override
    @Async
    public void notifyIncomingAttack(IncomingAttack attack) {
        if (attack == null || attack.getArrivalAt() == null) return;
        TelegramSettings s = safeReadSettings();
        if (s == null || !s.isEnabled()) return;
        boolean isAttack = Boolean.TRUE.equals(attack.getIsAttack());
        if (isAttack && !s.isNotifyIncomingAttack()) return;
        if (!isAttack && !s.isNotifyIncomingRaid()) return;
        if (!configured(s)) return;

        try {
            send(s, buildIncomingAttackText(attack, isAttack));
        } catch (RuntimeException e) {
            log.warn("Telegram notify failed for attack {}: {}", attack.getId(), e.toString());
        }
    }

    @Override
    @Async
    public void notifySessionLost(Long accountId, String reason) {
        TelegramSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifySessionLost() || !configured(s)) return;
        if (!shouldFire("session-lost", accountId, null)) return;
        try {
            send(s, "⚠️ <b>Browser session lost</b>\n"
                    + "Account: " + esc(describeAccount(accountId))
                    + (notBlank(reason) ? "\nReason: " + esc(abbreviate(reason, 500)) : ""));
        } catch (RuntimeException e) {
            log.warn("Telegram notify (session-lost) failed for account {}: {}", accountId, e.toString());
        }
    }

    @Override
    @Async
    public void notifyCaptcha(Long accountId, String reason) {
        TelegramSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifyCaptcha() || !configured(s)) return;
        if (!shouldFire("captcha", accountId, null)) return;
        try {
            send(s, "🧩 <b>Captcha challenge detected</b>\n"
                    + "Account: " + esc(describeAccount(accountId))
                    + "\nSolve it in the browser, then re-login."
                    + (notBlank(reason) ? "\nContext: " + esc(abbreviate(reason, 400)) : ""));
        } catch (RuntimeException e) {
            log.warn("Telegram notify (captcha) failed for account {}: {}", accountId, e.toString());
        }
    }

    @Override
    @Async
    public void notifyReturnCatch(ReturnCatchSuggestion sug) {
        if (sug == null) return;
        TelegramSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifyReturnCatch() || !configured(s)) return;
        if (!shouldFire("return-catch", null, String.valueOf(sug.attackId()))) return;
        try {
            StringBuilder sb = new StringBuilder("🎯 <b>Return-catch window open</b>\n");
            if (notBlank(sug.fromVillageName())) sb.append("From: ").append(esc(sug.fromVillageName())).append('\n');
            sb.append("Target: (").append(sug.toX()).append('|').append(sug.toY()).append(")\n");
            sb.append(String.format(java.util.Locale.ROOT, "Distance: %.1f fields", sug.distance()));
            if (sug.launchAt() != null) sb.append("\nLaunch at: ").append(esc(sug.launchAt().toString()));
            send(s, sb.toString());
        } catch (RuntimeException e) {
            log.warn("Telegram notify (return-catch) failed for attack {}: {}", sug.attackId(), e.toString());
        }
    }

    @Override
    @Async
    public void notifyResourceOverflow(Long accountId, Village village, String store, String detail) {
        if (village == null) return;
        TelegramSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifyResourceOverflow() || !configured(s)) return;
        if (!shouldFire("resource-overflow", accountId, village.getId() + "|" + store)) return;
        try {
            send(s, "📦 <b>" + esc(store) + " nearly full</b>\n"
                    + "Village: " + esc(village.getName()
                        + " (" + village.getCoordinateX() + "|" + village.getCoordinateY() + ")")
                    + "\nAccount: " + esc(describeAccount(accountId))
                    + (notBlank(detail) ? "\n" + esc(detail) : ""));
        } catch (RuntimeException e) {
            log.warn("Telegram notify (resource-overflow) failed for village {}: {}",
                    village.getId(), e.toString());
        }
    }

    /**
     * Synchronously post a test message to the configured chat. Bypasses the
     * master switch and per-event toggles — the point is to verify the token +
     * chat id work while everything else is still off. Returns the outcome so
     * the settings UI can show a green tick / red X without polling.
     */
    public TestResult sendTest() {
        TelegramSettings s;
        try {
            s = settingsService.get();
        } catch (RuntimeException e) {
            return TestResult.failure("Failed to load Telegram settings: " + e.getMessage());
        }
        if (!configured(s)) {
            return TestResult.failure("Bot token and chat id must both be set before testing.");
        }
        try {
            return postSync(s, "✅ <b>Travian Bot — test message</b>\nSent at " + esc(Instant.now().toString()));
        } catch (RuntimeException e) {
            return TestResult.failure("Failed to build/send test message: " + e.getMessage());
        }
    }

    // --- message builders --------------------------------------------------

    private String buildIncomingAttackText(IncomingAttack attack, boolean isAttack) {
        StringBuilder sb = new StringBuilder();
        sb.append(isAttack ? "🔴 <b>Incoming attack</b>\n" : "🟡 <b>Incoming raid</b>\n");
        sb.append("Attacker: ")
          .append(esc(notBlank(attack.getAttackerName()) ? attack.getAttackerName() : "Unknown"));
        if (notBlank(attack.getAttackerAlliance())) {
            sb.append(" [").append(esc(attack.getAttackerAlliance())).append(']');
        }
        if (attack.getSourceX() != null && attack.getSourceY() != null) {
            sb.append("\nFrom: ");
            if (notBlank(attack.getSourceVillageName())) sb.append(esc(attack.getSourceVillageName())).append(' ');
            sb.append('(').append(attack.getSourceX()).append('|').append(attack.getSourceY()).append(')');
        }
        Village target = villageRepository.findById(attack.getTargetVillageId()).orElse(null);
        if (target != null) {
            sb.append("\nTarget: ").append(esc(target.getName()
                    + " (" + target.getCoordinateX() + "|" + target.getCoordinateY() + ")"));
        }
        Duration until = Duration.between(Instant.now(), attack.getArrivalAt());
        if (!until.isNegative()) sb.append("\nLands in: ").append(formatDuration(until));
        sb.append("\nArrival: ").append(esc(attack.getArrivalAt().toString()));
        if (attack.getMostLikelyUnit() != null) {
            sb.append("\nLikely unit: ");
            if (attack.getMostLikelyTsLevel() != null) sb.append("TS ").append(attack.getMostLikelyTsLevel()).append(" · ");
            sb.append(esc(attack.getMostLikelyUnit()));
        }
        if (attack.getWaveCount() != null && attack.getWaveCount() > 1) {
            sb.append("\nWaves: ").append(attack.getWaveCount());
        }
        return sb.toString();
    }

    // --- HTTP --------------------------------------------------------------

    private void send(TelegramSettings s, String text) {
        HttpRequest req = buildRequest(s, text);
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) {
                log.warn("Telegram sendMessage returned {} — body: {}", sc, abbreviate(resp.body(), 200));
            } else {
                log.debug("Telegram notify OK ({})", sc);
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Telegram sendMessage POST failed: {}", e.toString());
        }
    }

    private TestResult postSync(TelegramSettings s, String text) {
        HttpRequest req = buildRequest(s, text);
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) {
                return TestResult.success("Test message sent (HTTP " + sc + ").");
            }
            // Telegram returns a JSON {"ok":false,"description":"..."} on API errors.
            return TestResult.failure("Telegram returned HTTP " + sc + ": " + abbreviate(resp.body(), 200));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return TestResult.failure("Connection timed out — check your network.");
        } catch (java.io.IOException e) {
            return TestResult.failure("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestResult.failure("Interrupted while posting.");
        }
    }

    private HttpRequest buildRequest(TelegramSettings s, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", s.getChatId());
        payload.put("text", text);
        payload.put("parse_mode", "HTML");
        payload.put("disable_web_page_preview", true);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise Telegram payload", e);
        }
        String url = "https://api.telegram.org/bot" + s.getBotToken() + "/sendMessage";
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    // --- helpers -----------------------------------------------------------

    private TelegramSettings safeReadSettings() {
        try {
            return settingsService.get();
        } catch (RuntimeException e) {
            log.debug("Telegram notifier: settings read failed — skipping ({})", e.toString());
            return null;
        }
    }

    private static boolean configured(TelegramSettings s) {
        return notBlank(s.getBotToken()) && notBlank(s.getChatId());
    }

    boolean shouldFire(String kind, Long accountId, String key) {
        String dedupKey = kind + "|"
                + (accountId == null ? "_" : accountId.toString()) + "|"
                + (key == null ? "_" : key);
        Instant now = Instant.now();
        Instant prev = lastFiredByKey.get(dedupKey);
        if (prev != null && Duration.between(prev, now).compareTo(DEBOUNCE) < 0) return false;
        lastFiredByKey.put(dedupKey, now);
        return true;
    }

    private String describeAccount(Long accountId) {
        if (accountId == null) return "Unknown";
        try {
            Account a = accountRepository.findById(accountId).orElse(null);
            if (a != null) {
                String label = notBlank(a.getUsername()) ? a.getUsername() : ("#" + accountId);
                if (notBlank(a.getServerUrl())) label += " @ " + a.getServerUrl();
                return label;
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        return "Account #" + accountId;
    }

    /** Escape the five characters that matter for Telegram's HTML parse mode. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "<empty>";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String formatDuration(Duration d) {
        long total = d.getSeconds();
        if (total <= 0) return "now";
        long h = total / 3600, m = (total % 3600) / 60, sec = total % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    /** Result of a synchronous test post; {@link #ok} drives the UI badge. */
    public record TestResult(boolean ok, String message) {
        public static TestResult success(String msg) { return new TestResult(true, msg); }
        public static TestResult failure(String msg) { return new TestResult(false, msg); }
    }
}
