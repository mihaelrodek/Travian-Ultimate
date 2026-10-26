package com.travianbot.bot.client.browser;

import com.microsoft.playwright.options.Proxy;
import com.travianbot.account.domain.AccountProxy;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountProxyRepository;
import com.travianbot.account.persistence.AccountSettingsRepository;
import com.travianbot.bot.client.TravianSession;
import com.travianbot.settings.service.GeneralSettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps one long-lived {@link BrowserTravianClient} per account.
 * <p>
 * Rationale: spinning up Chromium takes ~1-2s and loses cookies between runs. By reusing
 * a client, subsequent scrapes skip login and reuse the authenticated browser context.
 * <p>
 * Lifecycle:
 * <ul>
 *   <li>{@link #getOrCreate} — returns the existing client for an account, or creates one
 *       from the supplied {@link TravianSession} if none exists.</li>
 *   <li>{@link #existing} — returns the cached client, or null. Does not create.</li>
 *   <li>{@link #release} — closes and removes the client for an account. Safe to call if none.</li>
 *   <li>{@link #onAccountDeleting} — releases automatically when the owning account is deleted.</li>
 *   <li>{@link #shutdown} — closes every client on app shutdown so Playwright nodes exit cleanly.</li>
 * </ul>
 * <p>
 * Thread safety: the internal map is a {@link ConcurrentHashMap}; individual clients are
 * NOT thread-safe (Playwright's Page is single-threaded). The caller is expected to
 * serialize access per account — typically via the per-account task queue.
 */
@Component
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);

    private final Map<Long, BrowserTravianClient> clients = new ConcurrentHashMap<>();

    /**
     * Shared across every client — pulls the min/max click delay out of {@link GeneralSettingsService}
     * on every call, so a settings change is picked up by the next action without a restart.
     */
    private final HumanDelay humanDelay;
    private final ActionRateLimiter rateLimiter;
    private final AccountProxyRepository proxyRepository;
    private final AccountSettingsRepository accountSettingsRepository;

    public BrowserSessionManager(GeneralSettingsService generalSettingsService,
                                 AccountProxyRepository proxyRepository,
                                 AccountSettingsRepository accountSettingsRepository) {
        this.humanDelay = new HumanDelay(() -> {
            GeneralSettingsService.ClickDelayRange r = generalSettingsService.getClickDelayRange();
            return new HumanDelay.Range(r.minMs(), r.maxMs());
        });
        this.rateLimiter = new ActionRateLimiter(generalSettingsService::getMaxActionsPerMinute);
        this.proxyRepository = proxyRepository;
        this.accountSettingsRepository = accountSettingsRepository;
    }

    /**
     * Returns the cached client for {@code accountId}, creating one from
     * {@code sessionSupplier} if none exists. The supplier is only invoked on cache miss.
     */
    public BrowserTravianClient getOrCreate(Long accountId, Supplier<TravianSession> sessionSupplier) {
        Objects.requireNonNull(accountId, "accountId");
        return clients.computeIfAbsent(accountId, id -> {
            Proxy proxy = resolveProxy(id);
            boolean headless = resolveHeadless(id);
            log.info("Creating new browser session for account {} ({}){}", id,
                    headless ? "headless" : "visible",
                    proxy == null ? " · direct connection" : " · via proxy " + proxy.server);
            return new BrowserTravianClient(sessionSupplier.get(), headless, humanDelay, proxy, id, rateLimiter);
        });
    }

    /**
     * Per-account headless preference (P2.11). Best-effort: a lookup failure or
     * a missing settings row defaults to visible (false), which is the safe
     * choice — a visible browser lets the user notice and hand-solve a captcha.
     */
    private boolean resolveHeadless(Long accountId) {
        try {
            return accountSettingsRepository.findByAccountId(accountId)
                    .map(s -> s.isHeadless())
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve headless flag for account {} — defaulting to visible: {}",
                    accountId, e.getMessage());
            return false;
        }
    }

    /**
     * Maps the account's active {@link AccountProxy} row (if any) to Playwright
     * launch options. Chromium can't switch proxies after launch, so activating
     * a different proxy row takes effect on the next login/restart — which is
     * also when the user gets a fresh session anyway. Best-effort: a DB hiccup
     * degrades to a direct connection rather than blocking the login.
     */
    private Proxy resolveProxy(Long accountId) {
        try {
            AccountProxy row = proxyRepository.findByAccountIdAndActiveTrue(accountId).orElse(null);
            if (row == null || row.getUrl() == null || row.getUrl().isBlank()) return null;
            Proxy proxy = new Proxy(row.getUrl().trim());
            if (row.getUsername() != null && !row.getUsername().isBlank()) {
                proxy.setUsername(row.getUsername());
            }
            if (row.getPassword() != null && !row.getPassword().isBlank()) {
                proxy.setPassword(row.getPassword());
            }
            return proxy;
        } catch (RuntimeException e) {
            log.warn("Failed to resolve proxy for account {} — launching without proxy: {}",
                    accountId, e.getMessage());
            return null;
        }
    }

    /** Cached client for the account, or {@code null} if none is open. */
    public BrowserTravianClient existing(Long accountId) {
        return clients.get(accountId);
    }

    /** True if a browser is currently open for the account. */
    public boolean hasOpenSession(Long accountId) {
        return clients.containsKey(accountId);
    }

    /**
     * Closes and forgets the client for this account. Safe to call repeatedly.
     */
    public void release(Long accountId) {
        BrowserTravianClient client = clients.remove(accountId);
        if (client == null) return;
        log.info("Releasing browser session for account {}", accountId);
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Error closing browser for account {}: {}", accountId, e.getMessage());
        }
    }

    /**
     * Cleans up the browser when the account is deleted so we don't leak Chromium processes.
     * Runs synchronously inside the deletion transaction — see {@link AccountDeletingEvent}.
     */
    @EventListener
    public void onAccountDeleting(AccountDeletingEvent event) {
        release(event.accountId());
    }

    /** Closes every browser on app shutdown. */
    @PreDestroy
    public void shutdown() {
        if (clients.isEmpty()) return;
        log.info("Shutting down {} browser session(s)", clients.size());
        for (Long accountId : clients.keySet().toArray(new Long[0])) {
            release(accountId);
        }
    }
}
