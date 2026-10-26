package com.travianbot.troops.api.dto;

import com.travianbot.troops.domain.VillageTroopCount;

import java.time.Instant;

/**
 * Wire shape for {@link VillageTroopCount}. Slots {@code t1..t10} are
 * positional within the tribe; the {@code tribeId} (nullable) lets the
 * frontend label them. We include {@code villageId} so the UI can join
 * against the Villages list for name + coords without an extra fetch.
 */
public record VillageTroopCountResponse(
        Long id,
        Long accountId,
        Long villageId,
        Integer tribeId,
        int t1,
        int t2,
        int t3,
        int t4,
        int t5,
        int t6,
        int t7,
        int t8,
        int t9,
        int t10,
        int hero,
        Instant updatedAt
) {
    public static VillageTroopCountResponse from(VillageTroopCount v) {
        return new VillageTroopCountResponse(
                v.getId(),
                v.getAccountId(),
                v.getVillageId(),
                v.getTribeId(),
                v.getT1(),
                v.getT2(),
                v.getT3(),
                v.getT4(),
                v.getT5(),
                v.getT6(),
                v.getT7(),
                v.getT8(),
                v.getT9(),
                v.getT10(),
                v.getHero(),
                v.getUpdatedAt()
        );
    }
}
