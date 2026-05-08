package com.travianbot.aianalysis.domain;

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

/**
 * One oasis tile observed during an {@link OasisScan}. Stored as a flat row
 * (rather than a JSON blob on the parent scan) so the dashboard can run
 * simple WHERE / GROUP BY queries — "how many crop oases within 7 tiles",
 * etc. — without parsing every blob.
 * <p>
 * {@code bonusPct} is the resource bonus the oasis contributes when occupied
 * (Travian's own visual is "+25%" / "+50%" — we store the integer percent
 * so the UI can render it however it likes).
 * <p>
 * {@code occupiedByPlayer} captures whether someone has already conquered
 * the oasis (then the field is the player's name) so the user can see at a
 * glance which oases are still up for grabs.
 */
@Entity
@Table(name = "oasis_tile")
public class OasisTile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private OasisScan scan;

    @Column(name = "x", nullable = false)
    private int x;

    @Column(name = "y", nullable = false)
    private int y;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private OasisType type;

    /**
     * Primary resource bonus, as a positive percent (25, 50). The
     * "secondary" bonus on a {@link OasisType#MIXED} tile is implied: it's
     * always the same percent on the second resource (game-imposed).
     */
    @Column(name = "bonus_pct", nullable = false)
    private int bonusPct;

    /**
     * Tile-distance from the scan's center coordinate (Chebyshev distance,
     * matching how Travian counts oasis range for a village). Cached so the
     * dashboard can sort + filter without recomputing every read.
     */
    @Column(name = "distance_tiles", nullable = false)
    private int distanceTiles;

    /**
     * Player name if the oasis is already conquered, null otherwise.
     * Conquered oases are useful "intel" rows — the user can see who
     * controls the resource economy nearby — even though the bot can't
     * take them.
     */
    @Column(name = "occupied_by_player", length = 100)
    private String occupiedByPlayer;

    protected OasisTile() {
    }

    public OasisTile(OasisScan scan, int x, int y, OasisType type, int bonusPct, int distanceTiles,
                     String occupiedByPlayer) {
        this.scan = scan;
        this.x = x;
        this.y = y;
        this.type = type;
        this.bonusPct = bonusPct;
        this.distanceTiles = distanceTiles;
        this.occupiedByPlayer = occupiedByPlayer;
    }

    public Long getId() { return id; }
    public OasisScan getScan() { return scan; }
    public int getX() { return x; }
    public int getY() { return y; }
    public OasisType getType() { return type; }
    public int getBonusPct() { return bonusPct; }
    public int getDistanceTiles() { return distanceTiles; }
    public String getOccupiedByPlayer() { return occupiedByPlayer; }
}
