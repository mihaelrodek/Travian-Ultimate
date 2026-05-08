package com.travianbot.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.DayOfWeek;

/**
 * A contiguous time window during which the bot is allowed to act on an account.
 * Minutes-since-midnight (0..1440). {@code start < end} — overnight windows must be
 * split into two (e.g. 22:00-24:00 + 00:00-03:00).
 */
@Entity
@Table(name = "active_window")
public class ActiveWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_settings_id", nullable = false)
    private AccountSettings accountSettings;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 9)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_minute", nullable = false)
    private int startMinute;

    @Column(name = "end_minute", nullable = false)
    private int endMinute;

    protected ActiveWindow() {
    }

    public ActiveWindow(AccountSettings accountSettings, DayOfWeek dayOfWeek, int startMinute, int endMinute) {
        this.accountSettings = accountSettings;
        this.dayOfWeek = dayOfWeek;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    public Long getId() { return id; }
    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }
}
