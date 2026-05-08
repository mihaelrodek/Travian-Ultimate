package com.travianbot.statistics.domain;

import com.travianbot.account.domain.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One capture of a statistics ranking page for a single account.
 * <p>
 * Each snapshot is immutable after insert — diffs for the UI are computed by
 * comparing a snapshot against the previous one for the same account/category.
 * We index on {@code (account_id, category, captured_at)} so "latest N snapshots
 * for this account+category" and history-range queries stay fast as history
 * grows.
 * <p>
 * The {@link #category} column was added in Phase F1 and redefined in Phase H
 * (PVP/PVE/DEFENSE/RAIDERS — see {@link StatCategory}). Hibernate
 * {@code ddl-auto=update} won't rewrite a column's existing DEFAULT, so the
 * stored default may still read {@code 'POPULATION'} on older installs; a
 * startup cleanup bean ({@code StatCategoryLegacyCleanup}) deletes any rows
 * carrying a legacy enum value so the read path doesn't blow up.
 */
@Entity
@Table(
        name = "stat_snapshot",
        indexes = {
                @Index(name = "idx_stat_snapshot_account_cat_captured",
                        columnList = "account_id, category, captured_at DESC")
        }
)
public class StatSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * Copied from the account's {@code serverUrl} at capture time so
     * historical snapshots stay interpretable even if the account row is
     * later edited to point at a different world.
     */
    @Column(name = "server_url", nullable = false, length = 255)
    private String serverUrl;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /**
     * Which of the four top-10 page tables this snapshot came from. The
     * {@code DEFAULT} in {@code columnDefinition} is only relevant when the
     * column is first created — Hibernate's {@code ddl-auto=update} won't
     * modify an existing column's default, so legacy databases may still
     * carry {@code 'POPULATION'} as the stored default; the legacy cleanup
     * bean deletes any such rows on startup.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16,
            columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'PVP'")
    private StatCategory category = StatCategory.PVP;

    @OneToMany(
            mappedBy = "snapshot",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("rank ASC")
    private List<Top10Entry> entries = new ArrayList<>();

    protected StatSnapshot() {
    }

    public StatSnapshot(Account account, String serverUrl, Instant capturedAt, StatCategory category) {
        this.account = account;
        this.serverUrl = serverUrl;
        this.capturedAt = capturedAt;
        this.category = category;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getServerUrl() { return serverUrl; }
    public Instant getCapturedAt() { return capturedAt; }
    public StatCategory getCategory() { return category; }
    public List<Top10Entry> getEntries() { return entries; }

    public void addEntry(Top10Entry entry) {
        entries.add(entry);
    }
}
