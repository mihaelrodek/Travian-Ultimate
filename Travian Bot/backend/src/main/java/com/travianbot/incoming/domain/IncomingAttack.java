package com.travianbot.incoming.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;

/**
 * One wave of an enemy attack/raid landing on one of our villages.
 * <p>
 * Rows are inserted by the {@code IncomingAttackService} every time it scrapes
 * the rally-point incoming-attacks page. The schema is journal-shaped: the
 * unique key on (accountId, targetVillageId, arrivalAt, sourceX, sourceY)
 * dedupes re-scrapes so it's safe to call the scraper every 90 seconds. Loose
 * coupling to other modules is intentional — both {@code accountId} and
 * {@code targetVillageId} are raw {@code Long}s rather than JPA relations so
 * the incoming module can be deleted/rewritten without touching account or
 * village FKs. Village cleanup is handled implicitly: deleting an account
 * cascades through the listener wiring in {@code IncomingAttackService}.
 * <p>
 * The derived fields {@code distanceFields}, {@code estimatedSpeedFieldsPerHour}
 * and {@code estimatedTroopClass} are computed by {@link #recompute(int, int)}
 * once we know the defender coordinates. They stay null when the source
 * coordinates couldn't be parsed (Travian sometimes shows only a village name
 * with no map link).
 */
@Entity
@Table(
        name = "incoming_attack",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_incoming_attack_dedupe",
                columnNames = {
                        "account_id",
                        "target_village_id",
                        "arrival_at",
                        "source_x",
                        "source_y"
                }
        )
)
public class IncomingAttack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "target_village_id", nullable = false)
    private Long targetVillageId;

    @Column(name = "source_x")
    private Integer sourceX;

    @Column(name = "source_y")
    private Integer sourceY;

    @Column(name = "source_village_name", length = 100)
    private String sourceVillageName;

    @Column(name = "attacker_name", length = 100)
    private String attackerName;

    @Column(name = "attacker_alliance", length = 50)
    private String attackerAlliance;

    @Column(name = "arrival_at", nullable = false)
    private Instant arrivalAt;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "distance_fields")
    private Double distanceFields;

    @Column(name = "estimated_speed_fields_per_hour")
    private Double estimatedSpeedFieldsPerHour;

    @Column(name = "estimated_troop_class", length = 32)
    private String estimatedTroopClass;

    /**
     * Top candidate unit name from the {@code TournamentSquareAnalyzer}
     * inverse solver, e.g. {@code "Theutates Thunder"} or {@code "Phalanx"}.
     * Null when we don't have enough information (no source coords, no
     * arrival time, no solver match within tolerance).
     */
    @Column(name = "most_likely_unit", length = 60)
    private String mostLikelyUnit;

    /**
     * Top candidate's Tournament Square level, 0..20. Null when no candidate
     * landed within the solver's tolerance bands.
     */
    @Column(name = "most_likely_ts_level")
    private Integer mostLikelyTsLevel;

    /**
     * Raw observation: distance divided by travel hours. Stored on the row
     * so the UI can render the bar even when the JSON breakdown is missing.
     */
    @Column(name = "observed_fields_per_hour")
    private Double observedFieldsPerHour;

    /**
     * Full {@code AnalysisResult} serialised as JSON — top-3 candidates with
     * their per-modifier breakdown. The frontend parses this client-side to
     * render the expandable details cell. Null on bad input or serialisation
     * failure; null is a soft failure (the row still saves cleanly).
     * <p>
     * Schema (matches {@link com.travianbot.incoming.analyzer.AnalysisResult}):
     * <pre>
     *   {
     *     "observedFieldsPerHour": 7.0,
     *     "candidates": [
     *       { "tribeId":3, "tIndex":1, "unitName":"Phalanx", "role":"inf_def",
     *         "tsLevel":7, "bootsBonus":0.0, "artifactMultiplier":1.0,
     *         "theoreticalTime":36000.0, "deviation":0.0, "modifierCount":1 }
     *     ],
     *     "summary": "Phalanx, TS 7"
     *   }
     * </pre>
     */
    @Column(name = "analysis_json", length = 2000)
    private String analysisJson;

    /**
     * Manual override for the speed-artifact multiplier. {@code null} means
     * "no artifact assumed" (the analyzer treats it as 1.0). When set, must
     * be one of the seven legal in-game values
     * (0.33 / 0.5 / 0.67 / 1.0 / 1.5 / 2.0 / 3.0). The analyzer NEVER
     * brute-forces this dimension — it only uses the value the user picks
     * here. See {@link com.travianbot.incoming.analyzer.TournamentSquareAnalyzer}
     * javadoc for the rationale.
     */
    @Column(name = "manual_artifact_multiplier")
    private Double manualArtifactMultiplier;

    @Column(name = "wave_count", nullable = false)
    private Integer waveCount = 1;

    @Column(name = "is_attack", nullable = false)
    private Boolean isAttack = Boolean.TRUE;

    @Column(name = "acknowledged", nullable = false)
    private Boolean acknowledged = Boolean.FALSE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IncomingAttack() {
    }

    /**
     * Primary constructor — only the NOT NULL fields. Optional metadata
     * (coords, attacker name, alliance) goes through dedicated setters /
     * {@link #recompute(int, int)} after construction.
     */
    public IncomingAttack(Long accountId, Long targetVillageId, Instant arrivalAt, Instant detectedAt) {
        this.accountId = accountId;
        this.targetVillageId = targetVillageId;
        this.arrivalAt = arrivalAt;
        this.detectedAt = detectedAt;
        this.waveCount = 1;
        this.isAttack = Boolean.TRUE;
        this.acknowledged = Boolean.FALSE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getTargetVillageId() { return targetVillageId; }
    public Integer getSourceX() { return sourceX; }
    public Integer getSourceY() { return sourceY; }
    public String getSourceVillageName() { return sourceVillageName; }
    public String getAttackerName() { return attackerName; }
    public String getAttackerAlliance() { return attackerAlliance; }
    public Instant getArrivalAt() { return arrivalAt; }
    public Instant getDetectedAt() { return detectedAt; }
    public Double getDistanceFields() { return distanceFields; }
    public Double getEstimatedSpeedFieldsPerHour() { return estimatedSpeedFieldsPerHour; }
    public String getEstimatedTroopClass() { return estimatedTroopClass; }
    public String getMostLikelyUnit() { return mostLikelyUnit; }
    public Integer getMostLikelyTsLevel() { return mostLikelyTsLevel; }
    public Double getObservedFieldsPerHour() { return observedFieldsPerHour; }
    public String getAnalysisJson() { return analysisJson; }
    public Double getManualArtifactMultiplier() { return manualArtifactMultiplier; }

    /**
     * Set or clear the manual artifact multiplier. Pass {@code null} to clear
     * (the analyzer will then ignore artifacts on its next run). Caller is
     * responsible for re-running the analyzer afterwards — this setter is a
     * dumb writer.
     */
    public void setManualArtifactMultiplier(Double manualArtifactMultiplier) {
        this.manualArtifactMultiplier = manualArtifactMultiplier;
        touch();
    }
    public Integer getWaveCount() { return waveCount; }
    public Boolean getIsAttack() { return isAttack; }
    public Boolean getAcknowledged() { return acknowledged; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Flip the read flag. Only mutator the UI exposes (see controller). */
    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
        this.updatedAt = Instant.now();
    }

    // ---------- Population helpers used by the scraper / service ----------
    //
    // Kept package-private friendly (plain setters) rather than wired into a
    // bag of constructor parameters because the parser often only knows a
    // subset of optional fields per wave.

    public void setSourceX(Integer sourceX) { this.sourceX = sourceX; touch(); }
    public void setSourceY(Integer sourceY) { this.sourceY = sourceY; touch(); }
    public void setSourceVillageName(String sourceVillageName) { this.sourceVillageName = sourceVillageName; touch(); }
    public void setAttackerName(String attackerName) { this.attackerName = attackerName; touch(); }
    public void setAttackerAlliance(String attackerAlliance) { this.attackerAlliance = attackerAlliance; touch(); }
    public void setWaveCount(Integer waveCount) { this.waveCount = waveCount == null ? 1 : waveCount; touch(); }
    public void setIsAttack(Boolean isAttack) { this.isAttack = isAttack == null ? Boolean.TRUE : isAttack; touch(); }

    /**
     * Stamp the inverse-solver output onto the row. Any field may be null
     * (the solver returned no usable result) — the caller decides whether to
     * stamp partial data. Updates {@link #updatedAt}.
     */
    public void setAnalysis(String mostLikelyUnit, Integer mostLikelyTsLevel,
                            Double observedFieldsPerHour, String analysisJson) {
        this.mostLikelyUnit = mostLikelyUnit;
        this.mostLikelyTsLevel = mostLikelyTsLevel;
        this.observedFieldsPerHour = observedFieldsPerHour;
        this.analysisJson = analysisJson;
        touch();
    }

    /**
     * Recomputes {@link #distanceFields}, {@link #estimatedSpeedFieldsPerHour}
     * and {@link #estimatedTroopClass} from the supplied defender coordinates.
     * <p>
     * If either source coordinate is null we don't have enough information to
     * derive distance — the speed/class fields stay null and the class
     * defaults to {@code "unknown"}.
     */
    public void recompute(int defenderX, int defenderY) {
        if (sourceX == null || sourceY == null) {
            this.distanceFields = null;
            this.estimatedSpeedFieldsPerHour = null;
            this.estimatedTroopClass = "unknown";
            touch();
            return;
        }
        double dx = (double) defenderX - (double) sourceX;
        double dy = (double) defenderY - (double) sourceY;
        double d = Math.sqrt(dx * dx + dy * dy);
        this.distanceFields = d;

        long travelSeconds = Duration.between(detectedAt, arrivalAt).getSeconds();
        if (travelSeconds <= 0) {
            this.estimatedSpeedFieldsPerHour = null;
            this.estimatedTroopClass = "unknown";
            touch();
            return;
        }
        double hours = travelSeconds / 3600.0;
        double speed = d / hours;
        this.estimatedSpeedFieldsPerHour = speed;
        this.estimatedTroopClass = classifyByFieldsPerHour(speed);
        touch();
    }

    /**
     * Public so the analyzer service can share the same threshold table. The
     * bucket names match common Travian community vocabulary — scouts/cav for
     * very-fast (>=14 f/h, light cav / scouts), cavalry (>=10), raiders (the
     * fast infantry like Imperians / Theutates Thunder running 7-10), infantry
     * (>=5, generic phalanx-tier) and rams/cats below that.
     */
    public static String classifyByFieldsPerHour(double speed) {
        if (speed >= 14.0) return "scouts/cav";
        if (speed >= 10.0) return "cavalry";
        if (speed >= 8.0) return "raiders";
        if (speed >= 5.0) return "infantry";
        return "rams/cats";
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
