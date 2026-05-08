package com.travianbot.hero.api;

import com.travianbot.hero.api.dto.HeroResponse;
import com.travianbot.hero.scraper.HeroScrapingService;
import com.travianbot.hero.service.HeroService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the hero feature.
 * <ul>
 *   <li>{@code GET /api/accounts/{accountId}/hero} — the latest persisted
 *       snapshot. Returns 404 if the hero has never been scraped.</li>
 *   <li>{@code POST /api/accounts/{accountId}/hero/scrape} — forces a fresh
 *       scrape of attributes / inventory / adventures and returns the merged
 *       snapshot. Also runs the auto-level policy if the account settings
 *       have one configured.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/hero")
public class HeroController {

    private final HeroService heroService;
    private final HeroScrapingService scrapingService;

    public HeroController(HeroService heroService, HeroScrapingService scrapingService) {
        this.heroService = heroService;
        this.scrapingService = scrapingService;
    }

    @GetMapping
    public HeroResponse get(@PathVariable Long accountId) {
        return heroService.getHero(accountId);
    }

    @PostMapping("/scrape")
    public HeroResponse scrape(@PathVariable Long accountId) {
        scrapingService.scrape(accountId);
        // Re-read through HeroService so DTO flatten runs inside a read-tx
        // with the lazy item/adventure collections reliably initialized.
        return heroService.getHero(accountId);
    }
}
