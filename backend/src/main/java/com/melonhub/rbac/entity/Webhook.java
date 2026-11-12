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

/** A signed change-notification endpoint of an application; events is a comma-separated list or *. */
@Entity
@Table(name = "webhooks")
public class Webhook {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id") private Application application;
    private String name;
    private String url;
    private String secret;
    private String events;
    private boolean enabled;
    private String createdBy;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public Webhook() {}
    public Long getId() { return id; }
    public Application getApplication() { return application; }
    public Webhook setApplication(Application application) { this.application = application; return this; }
    public String getName() { return name; }
    public Webhook setName(String name) { this.name = name; return this; }
    public String getUrl() { return url; }
    public Webhook setUrl(String url) { this.url = url; return this; }
    public String getSecret() { return secret; }
    public Webhook setSecret(String secret) { this.secret = secret; return this; }
    public String getEvents() { return events; }
    public Webhook setEvents(String events) { this.events = events; return this; }
    public boolean isEnabled() { return enabled; }
    public Webhook setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public String getCreatedBy() { return createdBy; }
    public Webhook setCreatedBy(String createdBy) { this.createdBy = createdBy; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
