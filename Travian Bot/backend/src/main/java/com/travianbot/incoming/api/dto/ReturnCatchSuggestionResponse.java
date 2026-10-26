package com.travianbot.incoming.api.dto;

import com.travianbot.incoming.service.ReturnCatchPlanner.ReturnCatchSuggestion;

import java.time.Instant;

/**
 * Wire shape for {@link ReturnCatchSuggestion}. Identical schema — this
 * exists so the planner's domain record can change without breaking the JSON
 * contract.
 */
public record ReturnCatchSuggestionResponse(
        Long attackId,
        Long fromVillageId,
        String fromVillageName,
        Long toX,
        Long toY,
        Instant attackerArrivesHomeAt,
        Instant launchAt,
        double travelHours,
        double distance,
        String reason
) {

    public static ReturnCatchSuggestionResponse from(ReturnCatchSuggestion s) {
        return new ReturnCatchSuggestionResponse(
                s.attackId(),
                s.fromVillageId(),
                s.fromVillageName(),
                s.toX(),
                s.toY(),
                s.attackerArrivesHomeAt(),
                s.launchAt(),
                s.travelHours(),
                s.distance(),
                s.reason()
        );
    }
}
