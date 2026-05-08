package com.travianbot.hero.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A single stack of items in the hero's inventory (or an item equipped in a
 * slot). We keep these in their own table rather than a JSON blob because the
 * UI wants to filter by category and by equipped/not-equipped, and because
 * future auto-equip logic will need to resolve items by id.
 */
@Entity
@Table(name = "hero_item")
public class HeroItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hero_id", nullable = false)
    private Hero hero;

    /** Travian's item id (tgid) — stable across servers for the same item. Nullable if we can't parse it. */
    @Column(name = "travian_item_id")
    private Long travianItemId;

    /** Display name as shown on the page. */
    @Column(length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private HeroItemCategory category = HeroItemCategory.OTHER;

    /** 1 for most gear, stacks (bandages, ointments) can have hundreds. */
    @Column(nullable = false)
    private int amount = 1;

    /** True for equipped gear, false for items in the inventory grid. */
    @Column(nullable = false)
    private boolean equipped = false;

    /** Equipment slot index for equipped items (0..N). {@code null} for unequipped stacks. */
    @Column(name = "slot")
    private Integer slot;

    /** Optional URL of the icon Travian renders for the item — handy for the UI grid. */
    @Column(name = "image_url", length = 300)
    private String imageUrl;

    protected HeroItem() {
    }

    public HeroItem(Hero hero, Long travianItemId, String name, HeroItemCategory category,
                    int amount, boolean equipped, Integer slot, String imageUrl) {
        this.hero = hero;
        this.travianItemId = travianItemId;
        this.name = name;
        this.category = category;
        this.amount = amount;
        this.equipped = equipped;
        this.slot = slot;
        this.imageUrl = imageUrl;
    }

    /** Used by {@link Hero#replaceItems} so the service can build items without knowing the Hero up front. */
    void bind(Hero hero) { this.hero = hero; }

    public Long getId() { return id; }
    public Hero getHero() { return hero; }
    public Long getTravianItemId() { return travianItemId; }
    public String getName() { return name; }
    public HeroItemCategory getCategory() { return category; }
    public int getAmount() { return amount; }
    public boolean isEquipped() { return equipped; }
    public Integer getSlot() { return slot; }
    public String getImageUrl() { return imageUrl; }
}
