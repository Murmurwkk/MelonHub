package com.melonhub.rbac.dto;

import java.time.OffsetDateTime;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class Requests {
    private Requests() {}
    private static final String CODE = "[a-zA-Z0-9_.-]{1,64}";
    private static final String DOMAIN_CODE = "[a-zA-Z0-9_.:-]{1,100}";
    public static final String USERNAME = "[a-zA-Z0-9_@-][a-zA-Z0-9_.@-]{2,99}";
    public static final String ADMIN_ROLE_CODE = "[A-Z][A-Z0-9_]{1,31}";

    public record Login(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=100) String password, @Size(max=12) String mfaCode) {
        public Login(String username, String password) { this(username, password, null); }
    }
    public record PasswordChange(@NotBlank @Size(max=100) String currentPassword, @NotBlank @Size(min=12,max=100) String newPassword) {}
    public record PasswordReset(@NotBlank @Size(min=12,max=100) String password) {}
    public static final String ADMIN_KIND = "MANAGER|DEPARTMENT";
    /**
     * allApplications false limits the account to applicationIds; when it is omitted, giving applicationIds limits the
     * account to them and leaving them out allows every application. Departments work the same way. parentUsername
     * defaults to the creating operator; kind defaults to MANAGER.
     */
    public record Account(@NotBlank @Pattern(regexp=USERNAME) String username, @Size(max=100) String displayName,
                          @NotBlank @Pattern(regexp=ADMIN_ROLE_CODE) String role,
                          @NotBlank @Size(min=12,max=100) String password, Boolean enabled,
                          @Size(max=200) List<@NotNull @Positive Long> applicationIds, Boolean allApplications,
                          @Pattern(regexp=USERNAME) String parentUsername, @Pattern(regexp=ADMIN_KIND) String kind,
                          Boolean allDepartments, @Size(max=200) List<@NotNull @Positive Long> departmentIds) {
        public Account(String username, String displayName, String role, String password, Boolean enabled, List<Long> applicationIds) {
            this(username, displayName, role, password, enabled, applicationIds, null, null, null, null, null);
        }
    }
    /** Omitted scopes, kind and parent keep their current values. */
    public record AccountUpdate(@Size(max=100) String displayName, @NotBlank @Pattern(regexp=ADMIN_ROLE_CODE) String role,
                                @NotNull Boolean enabled, @Size(max=200) List<@NotNull @Positive Long> applicationIds, Boolean allApplications,
                                @Pattern(regexp=USERNAME) String parentUsername, @Pattern(regexp=ADMIN_KIND) String kind,
                                Boolean allDepartments, @Size(max=200) List<@NotNull @Positive Long> departmentIds) {
        public AccountUpdate(String displayName, String role, Boolean enabled, List<Long> applicationIds) {
            this(displayName, role, enabled, applicationIds, null, null, null, null, null);
        }
    }
    public record AdminRoleCreate(@NotBlank @Pattern(regexp=ADMIN_ROLE_CODE) String code, @NotBlank @Size(max=100) String name,
                                  @Size(max=500) String description, @NotNull @Size(max=50) List<@NotBlank @Size(max=64) String> functions) {}
    public record AdminRoleUpdate(@NotBlank @Size(max=100) String name, @Size(max=500) String description,
                                  @NotNull @Size(max=50) List<@NotBlank @Size(max=64) String> functions) {}

    public record Department(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp=CODE) String code) {}
    public record Application(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp=CODE) String code,
                              @Size(max=500) String description, @Size(max=100) String owner) {
        public Application(String name, String code, String description) { this(name, code, description, null); }
    }
    public record ApiKey(@NotBlank @Size(max=100) String name, @Min(1) @Max(3650) Integer expiresInDays) {}
    public record User(@NotBlank @Size(max=100) String name, @NotBlank @Size(max=100) String externalId, @Size(max=150) String email,
                       @NotNull @Size(max=100) List<@NotNull @Positive Long> departmentIds) {}
    public record Role(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp=DOMAIN_CODE) String code, @Size(max=500) String description) {}
    public record Permission(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp=DOMAIN_CODE) String code,
                             @NotBlank @Size(max=100) String resource, @NotBlank @Size(max=64) String action) {}
    public record Ids(@NotNull @Size(max=1000) List<@NotNull @Positive Long> ids) {}
    public record Enabled(@NotNull Boolean enabled) {}
    /** switchDepartment=false refuses to move the user's current department; null or true keeps the switching behaviour. */
    public record Context(@NotBlank @Size(max=100) String externalId, @Positive long departmentId,
                          @Size(max=1000) List<@NotNull @Positive Long> activeRoleIds, Boolean switchDepartment) {
        public Context(String externalId, long departmentId, List<Long> activeRoleIds) { this(externalId, departmentId, activeRoleIds, null); }
    }
    public record Check(@NotNull @Size(min=1,max=100) List<@NotBlank @Size(max=100) String> permissions) {}
    public record Simulation(@Positive long userId, @Positive long departmentId, @Positive long applicationId,
                             @Size(max=1000) List<@NotNull @Positive Long> activeRoleIds,
                             @Size(max=100) List<@NotBlank @Size(max=100) String> permissions) {}
    public record DomainCopy(@Positive long applicationId, @Positive long sourceDepartmentId, @Positive long targetDepartmentId) {}

    public record MfaCode(@NotBlank @Pattern(regexp="\\d{6}") String code) {}
    public record MfaDisable(@NotBlank @Size(max=100) String password, @NotBlank @Pattern(regexp="\\d{6}") String code) {}
    /**
     * The complete role set of one user in one domain. expiries sets individual roles' expiry (expiresAt null is
     * permanent); expiresInDays applies to every listed role without an entry there. A role covered by neither
     * keeps its current expiry, and a new one is permanent.
     */
    public record UserRoles(@NotNull @Size(max=1000) List<@NotNull @Positive Long> ids, @Min(1) @Max(3650) Integer expiresInDays,
                            @Size(max=1000) List<@NotNull @Valid RoleExpiry> expiries) {
        public UserRoles(List<Long> ids) { this(ids, null, null); }
        public UserRoles(List<Long> ids, Integer expiresInDays) { this(ids, expiresInDays, null); }
    }
    public record RoleExpiry(@Positive long roleId, OffsetDateTime expiresAt) {}
    public record BatchUserRoles(@NotNull @Size(min=1,max=1000) List<@NotNull @Positive Long> userIds,
                                 @NotNull @Size(min=1,max=1000) List<@NotNull @Positive Long> roleIds,
                                 @Min(1) @Max(3650) Integer expiresInDays) {}
    public record RateLimitUpdate(@Min(0) @Max(100000) Integer perSecond) {}

    // ---- access requests (submitted by business systems on behalf of employees) ----
    public record AccessRequestSubmit(@NotBlank @Size(max=100) String externalId, @Positive long departmentId, @Positive long roleId,
                                      @NotBlank @Size(max=500) String reason, @Min(1) @Max(3650) Integer durationDays) {}
    public record AccessRequestCancel(@NotBlank @Size(max=100) String externalId) {}
    /** permanent=true grants without expiry; otherwise durationDays, or the requested duration when that is null. */
    public record RequestApprove(@Min(1) @Max(3650) Integer durationDays, @Size(max=500) String comment, Boolean permanent) {
        public RequestApprove(Integer durationDays, String comment) { this(durationDays, comment, null); }
    }
    public record RequestReject(@Size(max=500) String comment) {}

    // ---- periodic access reviews ----
    public record ReviewCreate(@NotBlank @Size(max=100) String name, @Positive long applicationId,
                               @Positive Long departmentId, @Min(1) @Max(365) Integer dueDays) {}
    public record ReviewItemDecision(@NotBlank @Pattern(regexp="KEEP|REVOKE") String decision, @Size(max=500) String comment) {}

    // ---- webhooks ----
    public record Webhook(@NotBlank @Size(max=100) String name,
                          @NotBlank @Size(max=500) @Pattern(regexp="https?://\\S+") String url,
                          @Size(min=16,max=100) @Pattern(regexp="[!-~][ -~]{14,98}[!-~]") String secret,
                          @NotNull @Size(min=1,max=50) List<@NotBlank @Size(max=64) @Pattern(regexp="\\*|[a-z0-9_.-]{1,64}") String> events,
                          Boolean enabled) {}

    // ---- bulk user import ----
    /** Rows are validated one by one during import, so one bad row is reported instead of rejecting the file. */
    public record UserImportRow(@NotBlank @Size(max=100) String name, @NotBlank @Size(max=100) String externalId,
                                @Size(max=150) String email, @NotNull @Size(max=100) List<@NotBlank @Size(max=64) String> departmentCodes) {}
    public record UserImport(@NotNull @Size(min=1,max=500) List<UserImportRow> users) {}

    // ---- permission manifest (export/import of one application's roles and permissions) ----
    public record ManifestPermission(@NotBlank @Pattern(regexp=DOMAIN_CODE) String code, @NotBlank @Size(max=100) String name,
                                     @NotBlank @Size(max=100) String resource, @NotBlank @Size(max=64) String action) {}
    public record ManifestRole(@NotBlank @Pattern(regexp=DOMAIN_CODE) String code, @NotBlank @Size(max=100) String name,
                               @Size(max=500) String description, Boolean enabled,
                               @NotNull @Size(max=1000) List<@NotBlank @Size(max=100) String> permissions) {}
    public record ManifestDepartment(@NotBlank @Pattern(regexp=CODE) String code,
                                     @NotNull @Size(max=2000) List<@Valid ManifestPermission> permissions,
                                     @NotNull @Size(max=2000) List<@Valid ManifestRole> roles) {}
    public record Manifest(@Positive Integer version, @NotNull @Size(max=200) List<@Valid ManifestDepartment> departments) {}
}
