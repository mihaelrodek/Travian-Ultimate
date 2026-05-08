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

import java.time.Instant;

/**
 * One row in the hero/adventures list. Lives in its own table so we can later
 * schedule hero-adventure tasks against specific adventures (e.g. "send hero to
 * the nearest normal adventure when health &gt; 50%").
 */
@Entity
@Table(name = "hero_adventure")
public class HeroAdventure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hero_id", nullable = false)
    private Hero hero;

    @Column(name = "target_x", nullable = false)
    private int targetX;

    @Column(name = "target_y", nullable = false)
    private int targetY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdventureDifficulty difficulty = AdventureDifficulty.UNKNOWN;

    /** When the adventure expires (from the list) — null for "no expiry" adventures. */
    @Column(name = "end_at")
    private Instant endAt;

    /** The MAP cell id Travian uses for the adventure so we can resolve the "Start" link. Nullable. */
    @Column(name = "map_id")
    private Long mapId;

    protected HeroAdventure() {
    }

    public HeroAdventure(Hero hero, int targetX, int targetY, AdventureDifficulty difficulty,
                         Instant endAt, Long mapId) {
        this.hero = hero;
        this.targetX = targetX;
        this.targetY = targetY;
        this.difficulty = difficulty;
        this.endAt = endAt;
        this.mapId = mapId;
    }

    void bind(Hero hero) { this.hero = hero; }

    public Long getId() { return id; }
    public Hero getHero() { return hero; }
    public int getTargetX() { return targetX; }
    public int getTargetY() { return targetY; }
    public AdventureDifficulty getDifficulty() { return difficulty; }
    public Instant getEndAt() { return endAt; }
    public Long getMapId() { return mapId; }
}
