package com.travianbot.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One proxy configuration row for an {@link Account}. Accounts can have many —
 * the user may rotate between a residential proxy, a datacenter proxy, and a
 * direct connection ("no proxy" is modeled as "no active row") depending on
 * what Travian's anti-bot is accepting that day.
 * <p>
 * Exactly one row per account can be marked {@code active = true}; the browser
 * session manager reads the active row when spinning up a Playwright context
 * and falls back to "no proxy" if none is active. Enforcing that invariant is
 * the service layer's job — there is no DB-level partial unique index because
 * SQLite (tests) doesn't support them cleanly and the service is the only
 * writer anyway.
 * <p>
 * The URL field uses the same {@code scheme://host:port} format Playwright's
 * {@code proxy.server} option expects (e.g. {@code http://proxy.example.com:8080}
 * or {@code socks5://user-rotator.example.net:1080}). Credentials live in
 * their own columns so we can blank them for auth-less proxies without
 * mangling the URL.
 * <p>
 * WARNING — {@code password} is stored plaintext, same caveat as
 * {@link Account#getPassword()}. Acceptable for single-user local bot; replace
 * with symmetric encryption before sharing.
 */
@Entity
@Table(name = "account_proxy")
public class AccountProxy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Many-to-one rather than back-referencing through AccountSettings, so the
     * proxy list survives a settings-row rebuild and is a straightforward
     * {@code findByAccountId} lookup from the browser session manager.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * Human label shown in the proxies list, e.g. "Residential EU" or
     * "Datacenter DE". Optional — if blank the UI falls back to the URL's
     * host:port.
     */
    @Column(length = 100)
    private String label;

    /** Full proxy URL including scheme and port, e.g. http://host:8080. */
    @Column(nullable = false, length = 512)
    private String url;

    @Column(length = 200)
    private String username;

    @Column(length = 512)
    private String password;

    /**
     * True if this is the proxy the browser session should use for this
     * account. At most one row per account should have this flag set — the
     * service layer clears it on siblings when a row is activated.
     */
    @Column(nullable = false)
    private boolean active = false;

    @Column(nullable = false)
    private Instant createdAt;

    protected AccountProxy() {
    }

    public AccountProxy(Account account, String label, String url, String username, String password, boolean active) {
        this.account = account;
        this.label = label;
        this.url = url;
        this.username = username;
        this.password = password;
        this.active = active;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
