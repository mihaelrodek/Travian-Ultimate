package com.travianbot.village.domain;

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
 * A single queued building upgrade in a {@link Village}.
 * <p>
 * {@code buildingType} is deliberately a free-form string at this stage — the T4.6 building set
 * is large (>30 entries per tribe) and keeping the enum out of the DB lets us evolve the
 * catalogue in code without migrations.
 */
@Entity
@Table(name = "building_queue_item")
public class BuildingQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(nullable = false, length = 64)
    private String buildingType;

    /** Village building slot (1..40). Optional — some bots pick the slot dynamically. */
    @Column
    private Integer slot;

    @Column(nullable = false)
    private int targetLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QueueStatus status;

    @Column(nullable = false)
    private int orderIndex;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant completedAt;

    /**
     * Expected in-game finish time for a RUNNING upgrade, in UTC. Populated by
     * the scraper when we can match this queue row to a {@code #buildingList}
     * entry on the villages page (the sidebar where Travian shows each queued
     * build with a countdown). Combined with the account's scraped server UTC
     * offset, the UI can render it as either wall-clock local time or the
     * in-game server clock.
     * <p>
     * Nullable because we only know it after a scrape that sees the countdown —
     * the Start click doesn't return a precise finish timestamp.
     */
    @Column
    private Instant finishesAt;

    @Column(length = 1024)
    private String resultMessage;

    protected BuildingQueueItem() {
    }

    public BuildingQueueItem(Village village, String buildingType, Integer slot, int targetLevel, int orderIndex) {
        this.village = village;
        this.buildingType = buildingType;
        this.slot = slot;
        this.targetLevel = targetLevel;
        this.orderIndex = orderIndex;
        this.status = QueueStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public String getBuildingType() { return buildingType; }
    public Integer getSlot() { return slot; }
    public int getTargetLevel() { return targetLevel; }
    public QueueStatus getStatus() { return status; }
    public int getOrderIndex() { return orderIndex; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFinishesAt() { return finishesAt; }
    public String getResultMessage() { return resultMessage; }

    /**
     * Record an updated expected finish time. Called by the scraper whenever a
     * fresh {@code #buildingList} countdown matches this row. Setting to null
     * clears a stale timestamp (e.g. when the build completes and the sidebar
     * no longer lists it).
     */
    public void setFinishesAt(Instant finishesAt) {
        this.finishesAt = finishesAt;
    }

    public void update(String buildingType, Integer slot, int targetLevel) {
        this.buildingType = buildingType;
        this.slot = slot;
        this.targetLevel = targetLevel;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public void markStatus(QueueStatus status, String message) {
        this.status = status;
        this.resultMessage = message;
        if (status == QueueStatus.DONE || status == QueueStatus.FAILED || status == QueueStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }

    /**
     * Returns a RUNNING item back to the PENDING pool. Used by queue reconciliation
     * when a scrape shows the slot is not actually under construction — the game
     * may have rejected our click, the build may have been cancelled in-game, or
     * we may have marked it RUNNING optimistically and the server disagreed.
     * <p>
     * Clears {@link #completedAt} because "pending" is not a terminal state, and
     * leaving a stale completion timestamp there would confuse downstream reports
     * that treat {@code completedAt != null} as "finished".
     */
    public void resetToPending(String reason) {
        this.status = QueueStatus.PENDING;
        this.resultMessage = reason;
        this.completedAt = null;
    }
}
