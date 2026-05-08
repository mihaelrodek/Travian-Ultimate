package com.travianbot.task.domain;

import com.travianbot.account.domain.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A queued or recurring bot action. Named {@code BotTask} to avoid clashing with
 * {@link java.util.concurrent.FutureTask} / similar in the standard library.
 * <p>
 * {@code type} is free-form at this stage — e.g. "LOGIN", "FARM_RUN", "BUILD_UPGRADE",
 * "HERO_ADVENTURE", plus whatever the user invents. We'll promote to an enum once
 * the stable set is known.
 */
@Entity
@Table(name = "bot_task")
public class BotTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Column
    private Instant scheduledAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant startedAt;

    @Column
    private Instant completedAt;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String resultMessage;

    protected BotTask() {
    }

    public BotTask(Account account, String type, String description, Instant scheduledAt) {
        this.account = account;
        this.type = type;
        this.description = description;
        this.scheduledAt = scheduledAt;
        this.status = TaskStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public TaskStatus getStatus() { return status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getResultMessage() { return resultMessage; }

    public void update(String type, String description, Instant scheduledAt) {
        this.type = type;
        this.description = description;
        this.scheduledAt = scheduledAt;
        this.updatedAt = Instant.now();
    }

    public void markStatus(TaskStatus status, String message) {
        this.status = status;
        this.resultMessage = message;
        this.updatedAt = Instant.now();
        if (status == TaskStatus.RUNNING && this.startedAt == null) {
            this.startedAt = this.updatedAt;
        }
        if (status == TaskStatus.DONE || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
            this.completedAt = this.updatedAt;
        }
    }
}
