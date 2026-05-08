package com.travianbot.account.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * All per-account configuration. One row per Account (unique FK). Fields are grouped
 * by domain (tribe/hero/alerts/npc/proxy) — kept as a flat entity rather than
 * {@code @Embeddable}s to make migrations trivial.
 * <p>
 * Tribe is auto-detected by the {@code ProfileScraper} on first successful login
 * (see {@code LoginService#tryAutoScrape}); it's not user-editable from the UI.
 * <p>
 * WARNING — {@code proxyPassword} is stored in plaintext, same caveat as
 * {@link Account#getPassword()}. Replace with symmetric encryption before sharing.
 */
@Entity
@Table(name = "account_settings")
public class AccountSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    // ---------- Tribe (auto-detected) ----------
    /**
     * Auto-populated from the in-game {@code /profile} page's Details section on the
     * first successful login — {@code ProfileScraper} writes it here via
     * {@code AccountSettingsService#autoDetectTribe}. Never set by the UI.
     * Stays nullable because accounts that have never been logged in don't have one.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Tribe tribe;

    // ---------- Hero ----------
    @Enumerated(EnumType.STRING)
    @Column(name = "hero_auto_level", length = 24, nullable = false)
    private HeroAttribute heroAutoLevel = HeroAttribute.OFF;

    @Column(name = "hero_heal_threshold_pct", nullable = false)
    private int heroHealThresholdPct = 40;

    @Column(name = "hero_auto_adventure", nullable = false)
    private boolean heroAutoAdventure = true;

    @Column(name = "hero_min_health_for_adventure", nullable = false)
    private int heroMinHealthForAdventure = 40;

    // ---------- Attack alerts ----------
    @Column(name = "alert_on_attack", nullable = false)
    private boolean alertOnAttack = true;

    @Column(name = "alert_include_troop_counts", nullable = false)
    private boolean alertIncludeTroopCounts = false;

    /** Comma-separated channels: "desktop,email,webhook". Null = no alerts even if flag is on. */
    @Column(name = "alert_channels", length = 200)
    private String alertChannels;

    // NPC / resource balancing has moved to VillageSettings (per-village). Old
    // DB columns (npc_enabled, npc_trigger_pct, npc_ratio_*, gold_reserve) are
    // dropped by AccountSchemaUpgrade on boot.

    // ---------- Proxy (optional) ----------
    @Column(name = "proxy_url", length = 512)
    private String proxyUrl;

    @Column(name = "proxy_username", length = 200)
    private String proxyUsername;

    @Column(name = "proxy_password", length = 512)
    private String proxyPassword;

    // ---------- Schedule ----------
    @OneToMany(mappedBy = "accountSettings", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ActiveWindow> activeWindows = new ArrayList<>();

    protected AccountSettings() {
    }

    public AccountSettings(Account account) {
        this.account = account;
    }

    // ---------- Accessors ----------
    public Long getId() { return id; }
    public Account getAccount() { return account; }

    public Tribe getTribe() { return tribe; }
    public void setTribe(Tribe tribe) { this.tribe = tribe; }

    public HeroAttribute getHeroAutoLevel() { return heroAutoLevel; }
    public void setHeroAutoLevel(HeroAttribute heroAutoLevel) { this.heroAutoLevel = heroAutoLevel; }
    public int getHeroHealThresholdPct() { return heroHealThresholdPct; }
    public void setHeroHealThresholdPct(int heroHealThresholdPct) { this.heroHealThresholdPct = heroHealThresholdPct; }
    public boolean isHeroAutoAdventure() { return heroAutoAdventure; }
    public void setHeroAutoAdventure(boolean heroAutoAdventure) { this.heroAutoAdventure = heroAutoAdventure; }
    public int getHeroMinHealthForAdventure() { return heroMinHealthForAdventure; }
    public void setHeroMinHealthForAdventure(int heroMinHealthForAdventure) { this.heroMinHealthForAdventure = heroMinHealthForAdventure; }

    public boolean isAlertOnAttack() { return alertOnAttack; }
    public void setAlertOnAttack(boolean alertOnAttack) { this.alertOnAttack = alertOnAttack; }
    public boolean isAlertIncludeTroopCounts() { return alertIncludeTroopCounts; }
    public void setAlertIncludeTroopCounts(boolean alertIncludeTroopCounts) { this.alertIncludeTroopCounts = alertIncludeTroopCounts; }
    public String getAlertChannels() { return alertChannels; }
    public void setAlertChannels(String alertChannels) { this.alertChannels = alertChannels; }

    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String proxyUrl) { this.proxyUrl = proxyUrl; }
    public String getProxyUsername() { return proxyUsername; }
    public void setProxyUsername(String proxyUsername) { this.proxyUsername = proxyUsername; }
    public String getProxyPassword() { return proxyPassword; }
    public void setProxyPassword(String proxyPassword) { this.proxyPassword = proxyPassword; }

    public List<ActiveWindow> getActiveWindows() { return activeWindows; }

    public void replaceActiveWindows(List<ActiveWindow> windows) {
        this.activeWindows.clear();
        this.activeWindows.addAll(windows);
    }
}
