package com.travianbot.statistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A single row of a top-10 statistics ranking, attached to one
 * {@link StatSnapshot}. Each {@link StatCategory} table on the source page
 * exposes exactly three pieces of per-row information we care about: the rank,
 * the player name, and one numeric "points" value — PvP attack points, PvE
 * attack points, defense points, or raided resources.
 * <p>
 * We therefore keep the row intentionally thin: {@link #rank},
 * {@link #playerName}, and {@link #score}. That last field replaces the old
 * attack/defense point columns and is interpreted per category by the caller
 * (the UI knows a "PVP snapshot's score = attack points", "RAIDERS snapshot's
 * score = booty resources", etc.).
 * <p>
 * The legacy columns {@code alliance_name}, {@code population},
 * {@code villages_count}, {@code attack_points}, {@code defense_points} still
 * exist in the DB (Hibernate {@code ddl-auto=update} never drops columns) but
 * are no longer mapped — they're nullable and get quietly ignored. Clean-slate
 * databases never see them.
 */
@Entity
@Table(name = "stat_top10_entry")
public class Top10Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private StatSnapshot snapshot;

    @Column(nullable = false)
    private int rank;

    @Column(name = "player_name", nullable = false, length = 100)
    private String playerName;

    /**
     * The one numeric value that ranks this row. Semantics depend on the
     * enclosing snapshot's {@link StatCategory}: attack points for PVP/PVE,
     * defense points for DEFENSE, raided resources for RAIDERS. Nullable so
     * the parser can still insert a row even if a server skin renders an
     * unparseable value.
     */
    @Column(name = "score")
    private Long score;

    protected Top10Entry() {
    }

    public Top10Entry(StatSnapshot snapshot, int rank, String playerName, Long score) {
        this.snapshot = snapshot;
        this.rank = rank;
        this.playerName = playerName;
        this.score = score;
    }

    public Long getId() { return id; }
    public StatSnapshot getSnapshot() { return snapshot; }
    public int getRank() { return rank; }
    public String getPlayerName() { return playerName; }
    public Long getScore() { return score; }
}
