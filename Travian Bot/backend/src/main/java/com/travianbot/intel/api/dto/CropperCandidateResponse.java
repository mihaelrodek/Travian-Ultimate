package com.travianbot.intel.api.dto;

import com.travianbot.intel.service.CropperFinderService.CropperCandidate;

/**
 * Wire shape for one cropper-candidate row. Mirrors
 * {@link CropperCandidate} field-for-field — the service-layer record is kept
 * decoupled from the HTTP layer so renaming a JSON property doesn't force a
 * change inside the service.
 */
public record CropperCandidateResponse(
        Long mapVillageId,
        Long playerUid,
        String playerName,
        String allianceName,
        Integer tribeId,
        Integer x,
        Integer y,
        Integer population,
        Boolean isCapital,
        String cropperClassification,
        Double distanceFields,
        Integer playerVillageCount,
        Boolean playerLooksActive
) {
    public static CropperCandidateResponse from(CropperCandidate c) {
        return new CropperCandidateResponse(
                c.mapVillageId(),
                c.playerUid(),
                c.playerName(),
                c.allianceName(),
                c.tribeId(),
                c.x(),
                c.y(),
                c.population(),
                c.isCapital(),
                c.cropperClassification(),
                c.distanceFields(),
                c.playerVillageCount(),
                c.playerLooksActive()
        );
    }
}
