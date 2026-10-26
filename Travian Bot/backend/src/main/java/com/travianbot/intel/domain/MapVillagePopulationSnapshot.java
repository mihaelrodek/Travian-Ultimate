package com.travianbot.intel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only daily-ish snapshot of a single {@link MapVillage}'s population.
 * <p>
 * One row per population change per village. We do NOT write a snapshot if the
 * population is identical to the most recent one — that keeps the table sparse
 * (most villages don't grow every sync). The UI sparkline reads up to ~30 of
 * these rows newest-first and renders a small chart in the player detail modal.
 * <p>
 * Indexed on {@code mapVillageId} for the per-village fetch path.
 */
@Entity
@Table(
        name = "map_village_population_snapshot",
        indexes = @Index(name = "ix_mvps_village_captured",
                columnList = "map_village_id, captured_at DESC")
)
public class MapVillagePopulationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "map_village_id", nullable = false)
    private Long mapVillageId;

    @Column(name = "population", nullable = false)
    private int population;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected MapVillagePopulationSnapshot() {}

    public MapVillagePopulationSnapshot(Long mapVillageId, int population, Instant capturedAt) {
        this.mapVillageId = mapVillageId;
        this.population = population;
        this.capturedAt = capturedAt;
    }

    public Long getId() { return id; }
    public Long getMapVillageId() { return mapVillageId; }
    public int getPopulation() { return population; }
    public Instant getCapturedAt() { return capturedAt; }
}
