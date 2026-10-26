package com.travianbot.notification.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.service.ReturnCatchPlanner.ReturnCatchSuggestion;
import com.travianbot.notification.EventNotifier;
import com.travianbot.notification.discord.domain.DiscordSettings;
import com.travianbot.notification.discord.service.DiscordSettingsService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts plain Discord webhook messages — no bot account, no OAuth, just a
 * fire-and-forget HTTPS POST to the URL the user pasted in the dedicated
 * Discord settings page.
 * <p>
 * Reads its config from {@link DiscordSettings} (singleton). The notifier
 * itself is dumb: every public {@code notifyXxx(...)} method runs the same
 * three-step guard — master switch on? per-event toggle on? webhook URL
 * configured? — then builds an embed and posts. Per-event toggles let the
 * user mute the things they don't want to be paged for (e.g. raids) without
 * disabling the whole integration.
 */
@Service
public class DiscordNotifier implements EventNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    /** Red — full attack alert. */
    private static final int COLOR_ATTACK = 0xE74C3C;
    /** Amber — smaller-threat raid. */
    private static final int COLOR_RAID = 0xF1C40F;
    /** Blue — informational / test message. */
    private static final int COLOR_INFO = 0x3498DB;
    /** Dark red — bot stopped working (session lost / browser died). */
    private static final int COLOR_SESSION_LOST = 0x992D22;
    /** Orange — bot needs human attention (captcha challenge). */
    private static final int COLOR_CAPTCHA = 0xE67E22;
    /** Green — opportunity to launch a counter-attack. */
    private static final int COLOR_RETURN_CATCH = 0x2ECC71;
    /** Purple — resources about to overflow (economic loss, not danger). */
    private static final int COLOR_RESOURCE_OVERFLOW = 0x9B59B6;

    /** Connect + body timeout. 4s — long enough for slow networks, short enough not to back-pressure callers. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);

    /**
     * Per-trigger debounce window. A flapping signal (browser dies → relogs
     * → dies again every 30s) would otherwise spam the channel. Five minutes
     * is short enough for "oh, the session went bad" to feel near-live, long
     * enough that a known-flappy account doesn't burn channel quota.
     */
    private static final Duration DEBOUNCE = Duration.ofMinutes(5);

    private final DiscordSettingsService settingsService;
    private final VillageRepository villageRepository;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * Last-fired timestamp per ({@code triggerKind|accountId|key}) — used to
     * suppress repeats within {@link #DEBOUNCE}. In-memory only: a restart
     * wipes the dedup table, which is the right behaviour because the user
     * probably wants to hear about anything that's still going on after a
     * restart.
     */
    private final Map<String, Instant> lastFiredByKey = new ConcurrentHashMap<>();

    public DiscordNotifier(DiscordSettingsService settingsService,
                           VillageRepository villageRepository,
                           AccountRepository accountRepository,
                           ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.villageRepository = villageRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    /**
     * Fire a "new incoming attack" embed. No-op when:
     * <ul>
     *   <li>Master {@code enabled} is off</li>
     *   <li>The per-event toggle for this attack-type is off
     *       ({@code notifyIncomingAttack} or {@code notifyIncomingRaid})</li>
     *   <li>The webhook URL is null/blank</li>
     *   <li>The attack row is null or missing its arrival timestamp</li>
     * </ul>
     */
    @Async
    public void notifyIncomingAttack(IncomingAttack attack) {
        if (attack == null || attack.getArrivalAt() == null) return;
        DiscordSettings s = safeReadSettings();
        if (s == null || !s.isEnabled()) return;
        boolean isAttack = Boolean.TRUE.equals(attack.getIsAttack());
        if (isAttack && !s.isNotifyIncomingAttack()) return;
        if (!isAttack && !s.isNotifyIncomingRaid()) return;
        if (s.getWebhookUrl() == null) return;

        try {
            String json = buildIncomingAttackPayload(attack, s);
            postToWebhook(s.getWebhookUrl(), json);
        } catch (RuntimeException e) {
            log.warn("Discord notify failed for attack {}: {}", attack.getId(), e.toString());
        }
    }

    /**
     * Fire a "browser session lost" alert. Use when the bot was working and
     * then unexpectedly couldn't reach an ingame page — login failure,
     * Playwright crash, browser hung, etc. Debounced per-account for
     * {@link #DEBOUNCE} so a flapping login loop doesn't drown the channel.
     */
    @Async
    public void notifySessionLost(Long accountId, String reason) {
        DiscordSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifySessionLost()) return;
        if (s.getWebhookUrl() == null) return;
        if (!shouldFire("session-lost", accountId, null)) return;

        try {
            String json = buildSessionLostPayload(accountId, reason, s);
            postToWebhook(s.getWebhookUrl(), json);
        } catch (RuntimeException e) {
            log.warn("Discord notify (session-lost) failed for account {}: {}", accountId, e.toString());
        }
    }

    /**
     * Fire a "captcha challenge detected" alert. Use whenever the bot lands
     * on a captcha page that needs a human to solve. Debounced per-account
     * so a repeated detection from successive scrapes doesn't pile up.
     */
    @Async
    public void notifyCaptcha(Long accountId, String reason) {
        DiscordSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifyCaptcha()) return;
        if (s.getWebhookUrl() == null) return;
        if (!shouldFire("captcha", accountId, null)) return;

        try {
            String json = buildCaptchaPayload(accountId, reason, s);
            postToWebhook(s.getWebhookUrl(), json);
        } catch (RuntimeException e) {
            log.warn("Discord notify (captcha) failed for account {}: {}", accountId, e.toString());
        }
    }

    /**
     * Fire a "return-catch window is open" alert. Posted once per attack id —
     * the dedup key includes the attack id so re-running the planner against
     * the same attack a minute later doesn't repost. Debounce window still
     * applies as a belt-and-braces backstop.
     */
    @Async
    public void notifyReturnCatch(ReturnCatchSuggestion suggestion) {
        if (suggestion == null) return;
        DiscordSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifyReturnCatch()) return;
        if (s.getWebhookUrl() == null) return;
        // Key on attackId so each distinct attack triggers at most one post
        // for the configured debounce window — the user only needs to see
        // "you can snipe this one" once.
        if (!shouldFire("return-catch", null, String.valueOf(suggestion.attackId()))) return;

        try {
            String json = buildReturnCatchPayload(suggestion, s);
            postToWebhook(s.getWebhookUrl(), json);
        } catch (RuntimeException e) {
            log.warn("Discord notify (return-catch) failed for attack {}: {}",
                    suggestion.attackId(), e.toString());
        }
    }

    /**
     * Fire a "warehouse/granary nearly full" alert. {@code store} is the
     * human label ("Warehouse" / "Granary"), {@code detail} the pre-formatted
     * fill summary from the overflow monitor. Debounced per village + store —
     * the monitor has its own longer cooldown, this is just the backstop.
     */
    @Async
    public void notifyResourceOverflow(Long accountId, Village village, String store, String detail) {
        if (village == null) return;
        DiscordSettings s = safeReadSettings();
        if (s == null || !s.isEnabled() || !s.isNotifyResourceOverflow()) return;
        if (s.getWebhookUrl() == null) return;
        if (!shouldFire("resource-overflow", accountId, village.getId() + "|" + store)) return;

        try {
            String json = buildResourceOverflowPayload(accountId, village, store, detail, s);
            postToWebhook(s.getWebhookUrl(), json);
        } catch (RuntimeException e) {
            log.warn("Discord notify (resource-overflow) failed for village {}: {}",
                    village.getId(), e.toString());
        }
    }

    /**
     * Synchronously post a "this is just a test" message to whatever
     * webhook is currently configured. Returns the result so the UI can
     * surface a green-tick / red-X without polling.
     * <p>
     * Bypasses the {@code enabled} master switch and the per-event toggles —
     * the whole point is to verify the URL works while everything else is
     * still off.
     */
    public TestResult sendTest() {
        DiscordSettings s;
        try {
            s = settingsService.get();
        } catch (RuntimeException e) {
            return TestResult.failure("Failed to load Discord settings: " + e.getMessage());
        }
        if (s.getWebhookUrl() == null) {
            return TestResult.failure("Webhook URL is empty — paste it before testing.");
        }
        try {
            String json = buildTestPayload(s);
            return postToWebhookSync(s.getWebhookUrl(), json);
        } catch (RuntimeException e) {
            return TestResult.failure("Failed to build test payload: " + e.getMessage());
        }
    }

    // --- payload builders --------------------------------------------------

    String buildIncomingAttackPayload(IncomingAttack attack, DiscordSettings s) {
        boolean isAttack = Boolean.TRUE.equals(attack.getIsAttack());
        String title = isAttack ? "Incoming attack" : "Incoming raid";
        int color = isAttack ? COLOR_ATTACK : COLOR_RAID;

        List<Map<String, Object>> fields = new ArrayList<>();
        addField(fields, "Attacker",
                emptyToNull(attack.getAttackerName()) != null ? attack.getAttackerName() : "Unknown",
                true);
        if (notBlank(attack.getAttackerAlliance())) {
            addField(fields, "Alliance", attack.getAttackerAlliance(), true);
        }
        if (attack.getSourceX() != null && attack.getSourceY() != null) {
            String label = notBlank(attack.getSourceVillageName())
                    ? attack.getSourceVillageName() + " (" + attack.getSourceX() + "|" + attack.getSourceY() + ")"
                    : "(" + attack.getSourceX() + "|" + attack.getSourceY() + ")";
            addField(fields, "Source", label, true);
        }
        Village target = villageRepository.findById(attack.getTargetVillageId()).orElse(null);
        if (target != null) {
            addField(fields, "Target",
                    target.getName() + " (" + target.getCoordinateX() + "|" + target.getCoordinateY() + ")",
                    true);
        }
        Duration untilArrival = Duration.between(Instant.now(), attack.getArrivalAt());
        if (!untilArrival.isNegative()) {
            addField(fields, "Lands in", formatDuration(untilArrival), true);
        }
        addField(fields, "Arrival", attack.getArrivalAt().toString(), true);
        if (attack.getDistanceFields() != null) {
            addField(fields, "Distance",
                    String.format(java.util.Locale.ROOT, "%.1f fields", attack.getDistanceFields()), true);
        }
        if (attack.getMostLikelyUnit() != null) {
            String unit = attack.getMostLikelyUnit();
            if (attack.getMostLikelyTsLevel() != null) {
                unit = "TS " + attack.getMostLikelyTsLevel() + " · " + unit;
            }
            addField(fields, "Likely unit", unit, true);
        }
        if (attack.getWaveCount() != null && attack.getWaveCount() > 1) {
            addField(fields, "Waves", attack.getWaveCount().toString(), true);
        }

        return buildEnvelope(s, title, color, fields, true);
    }

    private String buildTestPayload(DiscordSettings s) {
        List<Map<String, Object>> fields = new ArrayList<>();
        addField(fields, "Status", "Webhook reachable ✅", false);
        addField(fields, "Sent at", Instant.now().toString(), true);
        return buildEnvelope(s, "Travian Bot — test message", COLOR_INFO, fields, false);
    }

    String buildSessionLostPayload(Long accountId, String reason, DiscordSettings s) {
        List<Map<String, Object>> fields = new ArrayList<>();
        addField(fields, "Account", describeAccount(accountId), true);
        if (notBlank(reason)) addField(fields, "Reason", abbreviate(reason, 800), false);
        addField(fields, "Detected at", Instant.now().toString(), true);
        return buildEnvelope(s, "Browser session lost", COLOR_SESSION_LOST, fields, true);
    }

    String buildCaptchaPayload(Long accountId, String reason, DiscordSettings s) {
        List<Map<String, Object>> fields = new ArrayList<>();
        addField(fields, "Account", describeAccount(accountId), true);
        addField(fields, "Action needed", "Solve the captcha in the browser, then re-login.", false);
        if (notBlank(reason)) addField(fields, "Context", abbreviate(reason, 800), false);
        addField(fields, "Detected at", Instant.now().toString(), true);
        return buildEnvelope(s, "Captcha challenge detected", COLOR_CAPTCHA, fields, true);
    }

    String buildReturnCatchPayload(ReturnCatchSuggestion sug, DiscordSettings s) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (notBlank(sug.fromVillageName())) {
            addField(fields, "From", sug.fromVillageName(), true);
        }
        addField(fields, "Target",
                "(" + sug.toX() + "|" + sug.toY() + ")",
                true);
        addField(fields, "Distance",
                String.format(java.util.Locale.ROOT, "%.1f fields", sug.distance()),
                true);
        if (sug.launchAt() != null) {
            Duration untilLaunch = Duration.between(Instant.now(), sug.launchAt());
            if (!untilLaunch.isNegative()) {
                addField(fields, "Launch in", formatDuration(untilLaunch), true);
            }
            addField(fields, "Launch at", sug.launchAt().toString(), true);
        }
        if (sug.attackerArrivesHomeAt() != null) {
            addField(fields, "Lands ~at", sug.attackerArrivesHomeAt().toString(), true);
        }
        return buildEnvelope(s, "Return-catch window open", COLOR_RETURN_CATCH, fields, true);
    }

    String buildResourceOverflowPayload(Long accountId, Village village, String store,
                                        String detail, DiscordSettings s) {
        List<Map<String, Object>> fields = new ArrayList<>();
        addField(fields, "Village",
                village.getName() + " (" + village.getCoordinateX() + "|" + village.getCoordinateY() + ")",
                true);
        addField(fields, "Store", store, true);
        addField(fields, "Account", describeAccount(accountId), true);
        if (notBlank(detail)) addField(fields, "Fill level", detail, false);
        addField(fields, "Detected at", Instant.now().toString(), true);
        return buildEnvelope(s, store + " nearly full", COLOR_RESOURCE_OVERFLOW, fields, true);
    }

    /**
     * Look up the account by id and produce a human-readable label for the
     * embed. Falls back to "Account #N" when the lookup fails — the
     * notification still goes out even if the DB read briefly hiccups.
     */
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
            // Lookup is best-effort — fall through to the bare-id label.
        }
        return "Account #" + accountId;
    }

    /**
     * Per-trigger debouncer. Returns true the first time {@code (kind,
     * accountId, key)} fires within {@link #DEBOUNCE}, false on every repeat
     * inside the window.
     * <p>
     * Either {@code accountId} or {@code key} may be null — the dedup key
     * just substitutes "_" so the lookup stays deterministic.
     */
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

    /**
     * Common envelope assembly. The {@code includeMentions} flag toggles
     * whether the configured user / role pings ride along — true for
     * real alerts, false for test pings (we don't want a test message
     * to wake anyone up).
     */
    private String buildEnvelope(DiscordSettings s, String title, int color,
                                 List<Map<String, Object>> fields, boolean includeMentions) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        embed.put("color", color);
        embed.put("fields", fields);
        embed.put("timestamp", Instant.now().toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        if (notBlank(s.getBotUsername())) payload.put("username", s.getBotUsername());
        if (notBlank(s.getBotAvatarUrl())) payload.put("avatar_url", s.getBotAvatarUrl());
        if (includeMentions) {
            String content = buildMentionContent(s);
            if (content != null) payload.put("content", content);
        }
        payload.put("embeds", List.of(embed));

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise Discord payload", e);
        }
    }

    /**
     * Combine user + role mentions into a single content string. Discord
     * requires the literal {@code <@id>} / {@code <@&id>} format — we
     * store whatever the user typed and pass it through verbatim so they
     * can also paste a raw {@code @everyone} or {@code @here} if they want.
     */
    private static String buildMentionContent(DiscordSettings s) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(s.getMentionUserId())) sb.append(s.getMentionUserId()).append(' ');
        if (notBlank(s.getMentionRoleId())) sb.append(s.getMentionRoleId()).append(' ');
        String trimmed = sb.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // --- HTTP --------------------------------------------------------------

    private void postToWebhook(String url, String json) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) {
                log.warn("Discord webhook returned {} — body: {}", sc, abbreviate(resp.body(), 200));
            } else {
                log.debug("Discord notify OK ({}): {} chars", sc, json.length());
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Discord webhook POST failed: {}", e.toString());
        }
    }

    /** Synchronous variant — used by the "Send test" button so the UI gets a verdict. */
    private TestResult postToWebhookSync(String url, String json) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) {
                return TestResult.failure("Webhook returned HTTP " + sc + ": " + abbreviate(resp.body(), 200));
            }
            return TestResult.success("Test message posted (HTTP " + sc + ").");
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return TestResult.failure("Connection timed out — check the URL or your network.");
        } catch (java.io.IOException e) {
            return TestResult.failure("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestResult.failure("Interrupted while posting.");
        }
    }

    // --- small helpers -----------------------------------------------------

    private DiscordSettings safeReadSettings() {
        try {
            return settingsService.get();
        } catch (RuntimeException e) {
            log.debug("Discord notifier: settings read failed — skipping ({})", e.toString());
            return null;
        }
    }

    private static void addField(List<Map<String, Object>> sink, String name, String value, boolean inline) {
        if (value == null) return;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", value);
        f.put("inline", inline);
        sink.add(f);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "<empty>";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** "1h 23m" / "47s" — compact enough for an inline embed field. */
    private static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds <= 0) return "now";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /** Result of a synchronous test post. {@link #ok} drives the UI badge colour. */
    public record TestResult(boolean ok, String message) {
        public static TestResult success(String msg) { return new TestResult(true, msg); }
        public static TestResult failure(String msg) { return new TestResult(false, msg); }
    }
}
