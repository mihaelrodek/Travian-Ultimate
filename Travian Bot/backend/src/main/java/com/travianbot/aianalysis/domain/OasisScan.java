package com.travianbot.aianalysis.domain;

import com.travianbot.account.domain.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One "scan the map around a center coordinate" run. Holds every
 * {@link OasisTile} the bot found inside the configured radius, plus the
 * center + radius the user picked so subsequent scans can compare like-for-like.
 * <p>
 * Scoped to an account because every account plays on its own server and
 * map. {@link #centerX} / {@link #centerY} are the coordinate the user was
 * standing on (typically their capital) when the scan ran. Multiple scans
 * for the same account are kept so the user can diff "before / after" — old
 * scans aren't auto-pruned, but the AnalysisService surfaces the most recent
 * one by default.
 */
@Entity
@Table(
        name = "oasis_scan",
        indexes = @Index(name = "idx_oasis_scan_account_started",
                columnList = "account_id, started_at DESC")
)
public class OasisScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "center_x", nullable = false)
    private int centerX;

    @Column(name = "center_y", nullable = false)
    private int centerY;

    /** Tile radius the scan covered. The map fetch widens to cover this many tiles around centerX,Y. */
    @Column(name = "radius", nullable = false)
    private int radius;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Tally fields cached on the scan row so the dashboard doesn't have to
     * GROUP BY a few thousand tile rows on every read. Kept nullable until
     * the scan finishes — a half-baked scan shouldn't show summary numbers.
     */
    @Column(name = "wood_count")
    private Integer woodCount;
    @Column(name = "clay_count")
    private Integer clayCount;
    @Column(name = "iron_count")
    private Integer ironCount;
    @Column(name = "crop_count")
    private Integer cropCount;
    @Column(name = "mixed_count")
    private Integer mixedCount;

    @OneToMany(
            mappedBy = "scan",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("distanceTiles ASC, id ASC")
    private List<OasisTile> tiles = new ArrayList<>();

    protected OasisScan() {
    }

    public OasisScan(Account account, int centerX, int centerY, int radius) {
        this.account = account;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.startedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public int getCenterX() { return centerX; }
    public int getCenterY() { return centerY; }
    public int getRadius() { return radius; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Integer getWoodCount() { return woodCount; }
    public Integer getClayCount() { return clayCount; }
    public Integer getIronCount() { return ironCount; }
    public Integer getCropCount() { return cropCount; }
    public Integer getMixedCount() { return mixedCount; }
    public List<OasisTile> getTiles() { return tiles; }

    public void addTile(OasisTile tile) {
        tiles.add(tile);
    }

    public void markComplete(int wood, int clay, int iron, int crop, int mixed) {
        this.completedAt = Instant.now();
        this.woodCount = wood;
        this.clayCount = clay;
        this.ironCount = iron;
        this.cropCount = crop;
        this.mixedCount = mixed;
    }
}
