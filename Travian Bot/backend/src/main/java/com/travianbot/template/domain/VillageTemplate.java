package com.travianbot.template.domain;

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
 * A reusable, named plan of build steps ("Cap Offense", "Defensive crop
 * village", etc.) that the user can import as CSV and apply to a village to
 * seed its {@code BuildingQueueItem} list.
 * <p>
 * Templates are account-scoped — different servers/alliances favour different
 * builds, and a "my account's library" model matches how the rest of this app
 * scopes data (notes, tasks, farmlists, villages). They are NOT village-scoped,
 * because their whole point is being applied across multiple villages.
 * <p>
 * {@code category} is advisory only ({@link TemplateCategory}) — the bot doesn't
 * check it before applying, but it's handy for filtering a long list.
 */
@Entity
@Table(
        name = "village_template",
        indexes = {
                @Index(name = "idx_village_template_account", columnList = "account_id"),
                @Index(name = "idx_village_template_account_name", columnList = "account_id,name")
        }
)
public class VillageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TemplateCategory category;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC, id ASC")
    private List<TemplateStep> steps = new ArrayList<>();

    protected VillageTemplate() {
    }

    public VillageTemplate(Account account, String name, String description, TemplateCategory category) {
        this.account = account;
        this.name = name;
        this.description = description;
        this.category = category;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public TemplateCategory getCategory() { return category; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<TemplateStep> getSteps() { return steps; }

    public void update(String name, String description, TemplateCategory category) {
        this.name = name;
        this.description = description;
        this.category = category;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
