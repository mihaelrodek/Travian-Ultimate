package com.travianbot.farmlist.api.dto;

import com.travianbot.farmlist.domain.FarmlistType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create/update body for a farmlist. {@code accountId} required on create, ignored on update.
 * {@code targets} is the full replacement list — targets not present are deleted.
 * <p>
 * {@code sendIntervalMinutes == null} means "never auto-send". Putting a
 * list into a group is strictly additive — the interval on the individual
 * list still wins unless the group's most recent send is more recent, in
 * which case the scheduler uses that as the baseline to avoid duplicates.
 */
public record FarmlistRequest(
        Long accountId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        boolean enabled,
        FarmlistType type,
        @Min(1) @Max(24 * 60) Integer sendIntervalMinutes,
        @Size(max = 60) String groupTag,
        @Valid List<FarmlistTargetDto> targets
) {
}
