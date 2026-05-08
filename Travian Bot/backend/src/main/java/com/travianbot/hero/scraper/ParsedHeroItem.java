package com.travianbot.hero.scraper;

import com.travianbot.hero.domain.HeroItemCategory;

/**
 * One item row extracted from the hero/inventory page. {@code equipped} rows have
 * {@code slot} filled in; inventory rows leave {@code slot} null.
 */
public record ParsedHeroItem(
        Long travianItemId,
        String name,
        HeroItemCategory category,
        int amount,
        boolean equipped,
        Integer slot,
        String imageUrl
) {
}
