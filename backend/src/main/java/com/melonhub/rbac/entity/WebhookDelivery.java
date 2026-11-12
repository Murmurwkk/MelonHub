package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One queued delivery of an event to a webhook (transactional outbox). */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "webhook_id") private Webhook webhook;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private String eventId;
    private String eventType;
    @Column(columnDefinition = "text") private String payload;
    private String status;
    private int attempts;
    private Instant nextAttemptAt;
    private Integer lastStatus;
    private String lastError;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private Instant deliveredAt;

    public WebhookDelivery() {}
    public Long getId() { return id; }
    public Webhook getWebhook() { return webhook; }
    public WebhookDelivery setWebhook(Webhook webhook) { this.webhook = webhook; return this; }
    public String getEventId() { return eventId; }
    public WebhookDelivery setEventId(String eventId) { this.eventId = eventId; return this; }
    public String getEventType() { return eventType; }
    public WebhookDelivery setEventType(String eventType) { this.eventType = eventType; return this; }
    public String getPayload() { return payload; }
    public WebhookDelivery setPayload(String payload) { this.payload = payload; return this; }
    public String getStatus() { return status; }
    public WebhookDelivery setStatus(String status) { this.status = status; return this; }
    public int getAttempts() { return attempts; }
    public WebhookDelivery setAttempts(int attempts) { this.attempts = attempts; return this; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public WebhookDelivery setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; return this; }
    public Integer getLastStatus() { return lastStatus; }
    public WebhookDelivery setLastStatus(Integer lastStatus) { this.lastStatus = lastStatus; return this; }
    public String getLastError() { return lastError; }
    public WebhookDelivery setLastError(String lastError) { this.lastError = lastError; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public WebhookDelivery setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; return this; }
}
