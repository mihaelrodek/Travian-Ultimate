package com.travianbot.incoming.api.dto;

import com.travianbot.incoming.domain.IncomingAttack;

import java.time.Duration;
import java.time.Instant;

/**
 * Wire shape for {@link IncomingAttack}. Adds a derived
 * {@code secondsUntilArrival} so the frontend can prime its countdown clock
 * without a second round-trip to compute server-vs-client clock skew.
 * <p>
 * The {@code mostLikelyUnit} / {@code mostLikelyTsLevel} /
 * {@code observedFieldsPerHour} / {@code analysisJson} fields come from the
 * Tournament-Square inverse solver
 * ({@link com.travianbot.incoming.analyzer.TournamentSquareAnalyzer}). The
 * JSON blob is sent as a raw string so the frontend can lazily parse only
 * when the user clicks "show breakdown" — keeps the list payload small.
 */
public record IncomingAttackResponse(
        Long id,
        Long accountId,
        Long targetVillageId,
        Integer sourceX,
        Integer sourceY,
        String sourceVillageName,
        String attackerName,
        String attackerAlliance,
        Instant arrivalAt,
        Instant detectedAt,
        Double distanceFields,
        Double estimatedSpeedFieldsPerHour,
        String estimatedTroopClass,
        String mostLikelyUnit,
        Integer mostLikelyTsLevel,
        Double observedFieldsPerHour,
        String analysisJson,
        Double manualArtifactMultiplier,
        Integer waveCount,
        Boolean isAttack,
        Boolean acknowledged,
        long secondsUntilArrival,
        Instant createdAt,
        Instant updatedAt
) {

    public static IncomingAttackResponse from(IncomingAttack a) {
        Instant now = Instant.now();
        long until = a.getArrivalAt() == null ? 0L
                : Duration.between(now, a.getArrivalAt()).getSeconds();
        return new IncomingAttackResponse(
                a.getId(),
                a.getAccountId(),
                a.getTargetVillageId(),
                a.getSourceX(),
                a.getSourceY(),
                a.getSourceVillageName(),
                a.getAttackerName(),
                a.getAttackerAlliance(),
                a.getArrivalAt(),
                a.getDetectedAt(),
                a.getDistanceFields(),
                a.getEstimatedSpeedFieldsPerHour(),
                a.getEstimatedTroopClass(),
                a.getMostLikelyUnit(),
                a.getMostLikelyTsLevel(),
                a.getObservedFieldsPerHour(),
                a.getAnalysisJson(),
                a.getManualArtifactMultiplier(),
                a.getWaveCount(),
                a.getIsAttack(),
                a.getAcknowledged(),
                until,
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
