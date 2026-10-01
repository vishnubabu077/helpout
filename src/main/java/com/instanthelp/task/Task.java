package com.instanthelp.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private UUID requesterId;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "category_code", nullable = false, length = 40)
    private String categoryCode;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "time_start", nullable = false)
    private Instant timeStart;

    @Column(name = "time_end", nullable = false)
    private Instant timeEnd;

    @Column(name = "offered_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal offeredAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TaskStatus status;

    /** Optimistic lock; protects concurrent transitions (claim, cancel, ...). */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Task() {}

    public Task(UUID requesterId, String title, String description, String categoryCode, String address,
                double latitude, double longitude, Instant timeStart, Instant timeEnd,
                BigDecimal offeredAmount, String currency, TaskStatus status) {
        this.requesterId = requesterId;
        this.title = title;
        this.description = description;
        this.categoryCode = categoryCode;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeStart = timeStart;
        this.timeEnd = timeEnd;
        this.offeredAmount = offeredAmount;
        this.currency = currency;
        this.status = status;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getProviderId() { return providerId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategoryCode() { return categoryCode; }
    public String getAddress() { return address; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Instant getTimeStart() { return timeStart; }
    public Instant getTimeEnd() { return timeEnd; }
    public BigDecimal getOfferedAmount() { return offeredAmount; }
    public String getCurrency() { return currency; }
    public TaskStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
