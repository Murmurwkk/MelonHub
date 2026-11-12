package com.melonhub.rbac.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.Generated;

/** Console operator account. The username is the key, so new accounts must be persisted, never merged. */
@Entity
@Table(name = "admin_accounts")
public class AdminAccount {
    @Id private String username;
    private String passwordHash;
    private String displayName;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role") private AdminRole role;
    private boolean enabled;
    private int failedAttempts;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant passwordChangedAt;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private String mfaSecret;
    private String mfaPendingSecret;
    private long mfaLastStep;
    private long credentialVersion;
    /** False when the account may only manage the applications listed in applicationIds. */
    private boolean allApplications;
    /** Applications the account may manage when allApplications is false. */
    @ElementCollection
    @CollectionTable(name = "admin_account_applications", joinColumns = @JoinColumn(name = "username"))
    @Column(name = "application_id")
    private Set<Long> applicationIds = new HashSet<>();

    protected AdminAccount() {}
    public AdminAccount(String username, String displayName, AdminRole role, String passwordHash, boolean enabled, boolean allApplications,
                        Instant passwordChangedAt) {
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.allApplications = allApplications;
        this.passwordChangedAt = passwordChangedAt;
    }

    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public AdminRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getMfaSecret() { return mfaSecret; }
    public long getCredentialVersion() { return credentialVersion; }
    public boolean isAllApplications() { return allApplications; }
    public Set<Long> getApplicationIds() { return applicationIds; }
}
