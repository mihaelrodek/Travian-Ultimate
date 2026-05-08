package com.travianbot.troops.domain;

import com.travianbot.village.domain.QueueStatus;
import com.travianbot.village.domain.Village;
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
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One "please train N of troop X" row in a village's training queue.
 * <p>
 * Deliberately modelled as a sibling of {@code BuildingQueueItem} rather than
 * shoe-horned into it — the two have different lifecycles (a build slot finishes
 * and disappears; troop training runs until the quantity is done or the user
 * cancels) and different in-game entry points.
 * <p>
 * The {@link #troopCode} is a free-form string (tribe-qualified, e.g.
 * {@code ROMANS_t1}) so we can evolve the {@link TroopCatalog} without DB
 * migrations. Validity against the catalog is checked in the service layer.
 * {@link #building} is denormalized from the catalog for cheap
 * {@code WHERE village_id = ? AND building = ?} filters — useful when we
 * later add a training scheduler that only acts on one building at a time.
 */
@Entity
@Table(
        name = "troop_queue_item",
        indexes = {
                @Index(name = "idx_troop_queue_village_order",
                        columnList = "village_id,orderIndex,id")
        }
)
public class TroopQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(nullable = false, length = 64)
    private String troopCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TroopBuilding building;

    /**
     * How many units of {@code troopCode} to train. Positive; the service
     * rejects zero/negative so a stale UI row can't be used as a "no-op
     * placeholder" that confuses the scheduler later.
     */
    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QueueStatus status;

    @Column(nullable = false)
    private int orderIndex;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant completedAt;

    @Column(length = 1024)
    private String resultMessage;

    protected TroopQueueItem() {
    }

    public TroopQueueItem(
            Village village,
            String troopCode,
            TroopBuilding building,
            int quantity,
            int orderIndex
    ) {
        this.village = village;
        this.troopCode = troopCode;
        this.building = building;
        this.quantity = quantity;
        this.orderIndex = orderIndex;
        this.status = QueueStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public String getTroopCode() { return troopCode; }
    public TroopBuilding getBuilding() { return building; }
    public int getQuantity() { return quantity; }
    public QueueStatus getStatus() { return status; }
    public int getOrderIndex() { return orderIndex; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getResultMessage() { return resultMessage; }

    public void update(String troopCode, TroopBuilding building, int quantity) {
        this.troopCode = troopCode;
        this.building = building;
        this.quantity = quantity;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public void markStatus(QueueStatus status, String message) {
        this.status = status;
        this.resultMessage = message;
        if (status == QueueStatus.DONE || status == QueueStatus.FAILED || status == QueueStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }
}
