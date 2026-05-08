package com.travianbot.bot.client;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-account session state: credentials, server URL, cookies, and anti-forgery tokens.
 * <p>
 * Mutable by design — a login populates cookies/tokens, a logout clears them.
 * Not thread-safe; assume single-threaded access per account (enforced by the bot's per-account task queue).
 */
public final class TravianSession {

    private final String serverUrl;
    private final String username;
    private final String password;

    private final Map<String, String> cookies = new HashMap<>();
    private String csrfToken;
    private String userAgent;
    private Instant loggedInAt;

    public TravianSession(String serverUrl, String username, String password) {
        this.serverUrl = normalize(serverUrl);
        this.username = username;
        this.password = password;
    }

    public String serverUrl() { return serverUrl; }
    public String username() { return username; }
    public String password() { return password; }
    public Map<String, String> cookies() { return cookies; }
    public String csrfToken() { return csrfToken; }
    public void csrfToken(String csrfToken) { this.csrfToken = csrfToken; }
    public String userAgent() { return userAgent; }
    public void userAgent(String userAgent) { this.userAgent = userAgent; }
    public Instant loggedInAt() { return loggedInAt; }
    public void markLoggedIn() { this.loggedInAt = Instant.now(); }
    public boolean isLoggedIn() { return loggedInAt != null; }

    private static String normalize(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
