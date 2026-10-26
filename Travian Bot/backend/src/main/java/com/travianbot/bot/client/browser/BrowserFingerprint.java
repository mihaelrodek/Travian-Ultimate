package com.travianbot.bot.client.browser;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A matched {user-agent, viewport} pair applied per browser session (roadmap
 * P2.9). Rotating the fingerprint each time a session is (re)created means two
 * logins from the same machine don't present byte-identical browser metadata.
 * <p>
 * <strong>Why matched pairs, not independent random values.</strong> An
 * internally inconsistent fingerprint is a stronger bot signal than a static
 * one — a 4K viewport paired with an old mobile UA, or a Chrome UA whose major
 * version doesn't exist, stands out immediately. So each entry is a plausible,
 * self-consistent desktop profile and we pick whole entries, never mixing
 * fields across them.
 * <p>
 * <strong>Scope.</strong> Deliberately conservative: recent Chrome-on-desktop
 * UAs only (Windows/macOS), common 16:9/16:10 desktop viewports. We do NOT
 * fake a different browser family — Playwright drives Chromium, so a Firefox
 * or Safari UA would contradict the real JS engine, {@code navigator} object
 * and client-hint headers. Matching the bundled Chromium's actual major
 * version would be even better; that's a follow-up once we pin the Playwright
 * browser build.
 */
final class BrowserFingerprint {

    private final String userAgent;
    private final int width;
    private final int height;

    private BrowserFingerprint(String userAgent, int width, int height) {
        this.userAgent = userAgent;
        this.width = width;
        this.height = height;
    }

    String userAgent() { return userAgent; }
    int width() { return width; }
    int height() { return height; }

    private static final List<BrowserFingerprint> POOL = List.of(
            new BrowserFingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    1920, 1080),
            new BrowserFingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                    1536, 864),
            new BrowserFingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                    1600, 900),
            new BrowserFingerprint(
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                    1440, 900),
            new BrowserFingerprint(
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                    1680, 1050)
    );

    /** Pick a random profile for a new session. */
    static BrowserFingerprint random() {
        return POOL.get(ThreadLocalRandom.current().nextInt(POOL.size()));
    }
}
