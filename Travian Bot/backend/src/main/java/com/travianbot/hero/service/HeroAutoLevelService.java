package com.travianbot.hero.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.HeroAttribute;
import com.travianbot.account.persistence.AccountSettingsRepository;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.hero.domain.Hero;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * When the hero has unspent skill points and the account is configured for
 * auto-leveling, this service allocates the free points to the configured
 * attribute (capped at 100 total in that category) by POSTing the attributes
 * form via the live Playwright session.
 * <p>
 * Called by {@link HeroScrapingService} after a successful attributes scrape.
 * The service is no-op if auto-level is {@link HeroAttribute#OFF}, if the
 * hero has no free points, or if the chosen attribute is already at its
 * 100-point ceiling.
 */
@Service
public class HeroAutoLevelService {

    /** Travian caps each attribute at 100 points (displayed as the 20th star). */
    private static final int ATTRIBUTE_CAP = 100;

    private static final Logger log = LoggerFactory.getLogger(HeroAutoLevelService.class);

    private final AccountSettingsRepository accountSettingsRepository;
    private final BrowserSessionManager sessionManager;

    public HeroAutoLevelService(
            AccountSettingsRepository accountSettingsRepository,
            BrowserSessionManager sessionManager
    ) {
        this.accountSettingsRepository = accountSettingsRepository;
        this.sessionManager = sessionManager;
    }

    /**
     * Tries to spend {@code hero.getFreePoints()} into the attribute selected
     * in {@link AccountSettings#getHeroAutoLevel()}. Returns the number of
     * points actually spent (0 if the policy is OFF, the hero has no points,
     * or the attribute is already at cap).
     * <p>
     * This method mutates the hero entity in-memory to reflect the spent points
     * so the caller's persist step reflects the post-spend state — but note
     * that the next scrape will overwrite these values with whatever the server
     * actually accepted.
     */
    public int applyIfConfigured(Account account, Hero hero) {
        AccountSettings settings = accountSettingsRepository.findByAccountId(account.getId())
                .orElse(null);
        if (settings == null) return 0;

        HeroAttribute target = settings.getHeroAutoLevel();
        if (target == null || target == HeroAttribute.OFF) return 0;

        int free = hero.getFreePoints();
        if (free <= 0) return 0;

        int current = currentPointsFor(hero, target);
        int headroom = ATTRIBUTE_CAP - current;
        if (headroom <= 0) {
            log.debug("Auto-level target {} already at {}/{} for account {} — nothing to do",
                    target, current, ATTRIBUTE_CAP, account.getId());
            return 0;
        }

        int toSpend = Math.min(free, headroom);
        String field = fieldNameFor(target);

        BrowserTravianClient client = sessionManager.existing(account.getId());
        if (client == null || !client.session().isLoggedIn()) {
            log.info("Skipping auto-level — no active browser session for account {}", account.getId());
            return 0;
        }

        int spent;
        try {
            spent = client.spendHeroPoints(account.getServerUrl(), field, toSpend);
        } catch (Exception e) {
            log.warn("Auto-level failed for account {}: {}", account.getId(), e.getMessage());
            return 0;
        }

        if (spent > 0) {
            applyLocalPoints(hero, target, spent);
            log.info("Auto-leveled {} points into {} for account {}", spent, target, account.getId());
        }
        return spent;
    }

    private static int currentPointsFor(Hero hero, HeroAttribute attr) {
        return switch (attr) {
            case FIGHTING_STRENGTH -> hero.getPointsFightingStrength();
            case OFF_BONUS -> hero.getPointsOffBonus();
            case DEF_BONUS -> hero.getPointsDefBonus();
            case RESOURCES -> hero.getPointsResources();
            case OFF -> 0;
        };
    }

    /** Name of the {@code <input name="...">} Travian expects on the attributes form. */
    private static String fieldNameFor(HeroAttribute attr) {
        return switch (attr) {
            case FIGHTING_STRENGTH -> "fightingStrength";
            case OFF_BONUS -> "offBonus";
            case DEF_BONUS -> "defBonus";
            case RESOURCES -> "resources";
            case OFF -> "";
        };
    }

    private static void applyLocalPoints(Hero hero, HeroAttribute attr, int spent) {
        switch (attr) {
            case FIGHTING_STRENGTH -> hero.setPointsFightingStrength(hero.getPointsFightingStrength() + spent);
            case OFF_BONUS -> hero.setPointsOffBonus(hero.getPointsOffBonus() + spent);
            case DEF_BONUS -> hero.setPointsDefBonus(hero.getPointsDefBonus() + spent);
            case RESOURCES -> hero.setPointsResources(hero.getPointsResources() + spent);
            case OFF -> { /* no-op */ }
        }
        hero.setFreePoints(Math.max(0, hero.getFreePoints() - spent));
    }
}
