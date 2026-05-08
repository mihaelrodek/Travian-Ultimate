package com.travianbot.farmlist.domain;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A named collection of raid targets. Each {@link FarmlistTarget} is a single coordinate
 * with a canonical troop loadout — the bot sends that loadout on each tick.
 * <p>
 * Since farmlists are now scraped from the rally-point Farm-lists tab, we keep
 * the Travian-side list id ({@code travianListId}) so subsequent scrapes can
 * reconcile in-place without creating duplicates. The user can still mark a
 * list PVE vs PVP, tag it into a free-text group for bulk send operations,
 * and set a {@code sendIntervalMinutes} so the scheduler knows how often to
 * trigger a send-all. A list in a group inherits the group's most recent
 * send time as its baseline — see FarmlistSendScheduler.
 */
@Entity
@Table(name = "farmlist")
public class Farmlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FarmlistType type = FarmlistType.PVE;

    /**
     * How often (in minutes) the scheduler should send-all this list.
     * Null means "never auto-send" — user must trigger manually.
     */
    @Column(name = "send_interval_minutes")
    private Integer sendIntervalMinutes;

    /**
     * Free-text group name. Lists sharing the same tag can be sent as a bundle
     * from the UI, and the scheduler uses the most recent send across the
     * group as the baseline for all members (prevents double-sending).
     */
    @Column(name = "group_tag", length = 60)
    private String groupTag;

    /**
     * Last time the bot send-alled this list (any target). Null on first scrape.
     */
    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    /**
     * The Travian-side farmlist id ({@code lid} in the rally point URL). Used by
     * the scraper to reconcile lists across runs without matching on name.
     */
    @Column(name = "travian_list_id")
    private Long travianListId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "farmlist", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<FarmlistTarget> targets = new ArrayList<>();

    protected Farmlist() {
    }

    public Farmlist(Account account, String name) {
        this.account = account;
        this.name = name;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public FarmlistType getType() { return type; }
    public Integer getSendIntervalMinutes() { return sendIntervalMinutes; }
    public String getGroupTag() { return groupTag; }
    public Instant getLastSentAt() { return lastSentAt; }
    public Long getTravianListId() { return travianListId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<FarmlistTarget> getTargets() { return targets; }

    public void update(String name, String description, boolean enabled) {
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    /**
     * Apply all the user-editable policy fields at once. Used by both the
     * CRUD endpoint and whatever the scraper decides to leave alone (it never
     * clobbers user policy — only list identity + targets).
     */
    public void updatePolicy(FarmlistType type, Integer sendIntervalMinutes, String groupTag) {
        this.type = (type != null) ? type : FarmlistType.PVE;
        this.sendIntervalMinutes = sendIntervalMinutes;
        this.groupTag = groupTag;
        this.updatedAt = Instant.now();
    }

    public void setTravianListId(Long travianListId) {
        this.travianListId = travianListId;
        this.updatedAt = Instant.now();
    }

    /**
     * Bookkeeping for "we just sent this list to the server". Updates each
     * target too so the per-target lastSentAt column reflects reality.
     */
    public void markAllSent() {
        Instant now = Instant.now();
        this.lastSentAt = now;
        for (FarmlistTarget t : targets) {
            if (t.isEnabled()) t.markSent();
        }
        this.updatedAt = now;
    }
}
