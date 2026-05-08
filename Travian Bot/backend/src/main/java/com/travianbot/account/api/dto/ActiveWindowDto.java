package com.travianbot.account.api.dto;

import com.travianbot.account.domain.ActiveWindow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;

/**
 * Wire format for a single active window. Minutes since midnight; {@code start < end}.
 */
public record ActiveWindowDto(
        @NotNull DayOfWeek dayOfWeek,
        @Min(0) @Max(1440) int startMinute,
        @Min(0) @Max(1440) int endMinute
) {
    public static ActiveWindowDto from(ActiveWindow w) {
        return new ActiveWindowDto(w.getDayOfWeek(), w.getStartMinute(), w.getEndMinute());
    }
}
