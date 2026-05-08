package com.travianbot.hero.api.dto;

import com.travianbot.hero.domain.HeroItem;
import com.travianbot.hero.domain.HeroItemCategory;

/**
 * UI-facing view of a single {@link HeroItem}. Exposes only the fields the
 * React grid needs — the Hero reference is implicit in the enclosing response.
 */
public record HeroItemResponse(
        Long id,
        Long travianItemId,
        String name,
        HeroItemCategory category,
        int amount,
        boolean equipped,
        Integer slot,
        String imageUrl
) {
    public static HeroItemResponse from(HeroItem item) {
        return new HeroItemResponse(
                item.getId(),
                item.getTravianItemId(),
                item.getName(),
                item.getCategory(),
                item.getAmount(),
                item.isEquipped(),
                item.getSlot(),
                item.getImageUrl()
        );
    }
}
