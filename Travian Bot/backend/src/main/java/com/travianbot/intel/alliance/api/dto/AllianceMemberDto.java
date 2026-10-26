package com.travianbot.intel.alliance.api.dto;

import com.travianbot.intel.alliance.domain.MapAllianceMember;

/**
 * One member row inside an {@link AllianceDetailResponse}. The {@code role}
 * field is the raw label from the alliance page (e.g. "Leader", "Side leader",
 * "Member", "Diplomat") — the UI decides how to localise it.
 */
public record AllianceMemberDto(
        Long uid,
        String name,
        Integer population,
        String role
) {
    public static AllianceMemberDto from(MapAllianceMember m) {
        return new AllianceMemberDto(
                m.getTravianUid(),
                m.getPlayerName(),
                m.getPopulation(),
                m.getRole()
        );
    }
}
