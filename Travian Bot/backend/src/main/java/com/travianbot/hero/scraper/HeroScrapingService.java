package com.travianbot.hero.scraper;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.scraper.NotLoggedInException;
import com.travianbot.hero.domain.Hero;
import com.travianbot.hero.domain.HeroAdventure;
import com.travianbot.hero.domain.HeroItem;
import com.travianbot.hero.persistence.HeroRepository;
import com.travianbot.hero.service.HeroAutoLevelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the three hero sub-page scrapes (attributes / inventory / adventures)
 * and writes the merged snapshot onto the per-account {@link Hero} entity.
 * <p>
 * Each sub-page is parsed independently — if the attributes page loads but the
 * inventory page 404s we still persist the attributes update and leave the
 * inventory list untouched. The service's philosophy: fresher-than-stale is
 * better than all-or-nothing.
 */
@Service
@Transactional
public class HeroScrapingService {

    private static final Logger log = LoggerFactory.getLogger(HeroScrapingService.class);

    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final HeroRepository heroRepository;
    private final HeroAutoLevelService autoLevelService;

    public HeroScrapingService(
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            HeroRepository heroRepository,
            HeroAutoLevelService autoLevelService
    ) {
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.heroRepository = heroRepository;
        this.autoLevelService = autoLevelService;
    }

    public Hero scrape(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new NotLoggedInException(accountId);
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        Hero hero = heroRepository.findByAccountId(accountId)
                .orElseGet(() -> heroRepository.save(new Hero(account)));

        scrapeAttributes(client, account, hero);
        scrapeInventory(client, account, hero);
        scrapeAdventures(client, account, hero);

        // Auto-level runs AFTER the attributes scrape so the policy sees the
        // fresh freePoints number. It is a best-effort side-effect — failures
        // should not abort the overall scrape.
        try {
            autoLevelService.applyIfConfigured(account, hero);
        } catch (Exception e) {
            log.warn("Hero auto-level step failed for account {}: {}", account.getId(), e.getMessage());
        }

        hero.setLastScrapedAt(Instant.now());
        return heroRepository.save(hero);
    }

    private void scrapeAttributes(BrowserTravianClient client, Account account, Hero hero) {
        String url = account.getServerUrl() + "/hero/attributes";
        try {
            String html = client.get(url);
            ParsedHeroAttributes a = HeroAttributesParser.parse(html);
            if (a.name() != null) hero.setName(a.name());
            if (a.level() != null) hero.setLevel(a.level());
            if (a.experience() != null) hero.setExperience(a.experience());
            if (a.experienceForNext() != null) hero.setExperienceForNext(a.experienceForNext());
            if (a.health() != null) hero.setHealth(a.health());
            if (a.regenPerHour() != null) hero.setRegenPerHour(a.regenPerHour());
            if (a.freePoints() != null) hero.setFreePoints(a.freePoints());
            if (a.pointsFightingStrength() != null) hero.setPointsFightingStrength(a.pointsFightingStrength());
            if (a.pointsOffBonus() != null) hero.setPointsOffBonus(a.pointsOffBonus());
            if (a.pointsDefBonus() != null) hero.setPointsDefBonus(a.pointsDefBonus());
            if (a.pointsResources() != null) hero.setPointsResources(a.pointsResources());
            if (a.status() != null) hero.setStatus(a.status());
            if (a.productionType() != null) hero.setProductionType(a.productionType());
            if (a.homeVillageDid() != null) hero.setHomeVillageDid(a.homeVillageDid());
            if (a.currentVillageDid() != null) hero.setCurrentVillageDid(a.currentVillageDid());
            hero.setReviveAt(a.reviveAt()); // nullable — overwrite either way
        } catch (Exception e) {
            log.warn("Hero attributes scrape failed for account {}: {}", account.getId(), e.getMessage());
        }
    }

    private void scrapeInventory(BrowserTravianClient client, Account account, Hero hero) {
        String url = account.getServerUrl() + "/hero/inventory";
        try {
            String html = client.get(url);
            List<ParsedHeroItem> parsed = HeroInventoryParser.parse(html);
            List<HeroItem> items = new ArrayList<>();
            for (ParsedHeroItem p : parsed) {
                items.add(new HeroItem(
                        hero, p.travianItemId(), p.name(), p.category(),
                        p.amount(), p.equipped(), p.slot(), p.imageUrl()
                ));
            }
            hero.replaceItems(items);
        } catch (Exception e) {
            log.warn("Hero inventory scrape failed for account {}: {}", account.getId(), e.getMessage());
        }
    }

    private void scrapeAdventures(BrowserTravianClient client, Account account, Hero hero) {
        String url = account.getServerUrl() + "/hero/adventures";
        try {
            String html = client.get(url);
            List<ParsedHeroAdventure> parsed = HeroAdventuresParser.parse(html);
            List<HeroAdventure> advs = new ArrayList<>();
            for (ParsedHeroAdventure p : parsed) {
                advs.add(new HeroAdventure(
                        hero, p.targetX(), p.targetY(), p.difficulty(), p.endAt(), p.mapId()
                ));
            }
            hero.replaceAdventures(advs);
            hero.setAdventurePoints(advs.size());
        } catch (Exception e) {
            log.warn("Hero adventures scrape failed for account {}: {}", account.getId(), e.getMessage());
        }
    }
}
