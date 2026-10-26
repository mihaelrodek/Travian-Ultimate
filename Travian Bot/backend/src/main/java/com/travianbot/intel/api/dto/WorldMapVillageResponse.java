package com.travianbot.intel.api.dto;

/**
 * One village as rendered on the interactive world map (roadmap MAP1). Flattens
 * the {@code MapVillage} geometry together with the owning {@code MapPlayer}'s
 * attributes (name / alliance / tribe / inactive) so the canvas can colour and
 * label a dot from a single array element — no client-side join.
 * <p>
 * Kept deliberately lean: this DTO ships once per village and a busy server has
 * tens of thousands of them, so every field here is multiplied by that count.
 * Player fields are nullable when a village's {@code playerUid} has no matching
 * player row (a stale/rare map.sql edge case).
 */
public record WorldMapVillageResponse(
        int x,
        int y,
        int population,
        String name,
        boolean capital,
        String playerName,
        String allianceName,
        Integer tribeId,
        boolean inactive,
        String cropperClassification
) {
}
