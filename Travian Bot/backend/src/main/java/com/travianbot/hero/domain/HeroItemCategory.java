package com.travianbot.hero.domain;

/**
 * High-level grouping for hero items. The detailed item name (e.g. "Natarian
 * helmet of awareness") lives on {@link HeroItem}, this enum just determines
 * which inventory slot a piece of equipment belongs in.
 */
public enum HeroItemCategory {
    HELMET,
    WEAPON,
    ARMOR,
    LEFT_HAND,   // shield / bag / scroll / map
    HORSE,
    BOOTS,
    BANDAGE,
    OINTMENT,
    SCROLL,
    BUCKET,
    CAGE,
    ARTWORK,
    BOOK_OF_WISDOM,
    TABLET_OF_LAW,
    SMALL_BANDAGE,
    RESOURCE_BONUS,
    CROP_BONUS,
    EXP_BONUS,
    CONSUMABLE,
    OTHER
}
