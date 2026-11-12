/** Console functions; a console page or button is available when the signed-in role grants its function. */
export type AdminFunctionCode = 'org.view' | 'department.manage' | 'user.manage' | 'application.manage' | 'application.credentials'
  | 'domain.view' | 'domain.configure' | 'domain.assign' | 'domain.transfer' | 'governance.view' | 'request.approve' | 'review.manage'
  | 'audit.view' | 'system.view' | 'account.manage' | 'role.manage'
export type AdminKind = 'MANAGER' | 'DEPARTMENT'
export interface Principal {
  username: string; displayName: string; role: string; roleName: string; functions: AdminFunctionCode[]
  /** A root of the administrator tree manages every account; kind DEPARTMENT is a leaf limited to its departments. */
  root: boolean; kind: AdminKind
  /** False when the account may only manage the departments in departmentIds (likewise for applications). */
  allDepartments: boolean; departmentIds: number[]
  allApplications: boolean; applicationIds: number[]; mfaEnabled: boolean
}
export interface AdminFunctionDefinition { code: AdminFunctionCode; name: string; category: string }
export interface AdminRoleDefinition {
  code: string; name: string; description: string; builtin: boolean; functions: AdminFunctionCode[]; accountCount: number; createdAt: string
}
export interface Department { id: number; name: string; code: string; createdAt: string; memberCount: number; roleCount: number }
export interface Application {
  id: number; name: string; code: string; description: string; owner: string; enabled: boolean; createdAt: string
  roleCount: number; permissionCount: number; activeKeyCount: number; rateLimit: number | null
}
export interface ApiKey {
  id: number; name: string; prefix: string; createdBy: string; createdAt: string; expiresAt: string | null
  lastUsedAt: string | null; revokedAt: string | null; status: 'ACTIVE' | 'EXPIRED' | 'REVOKED'
}
export interface User {
  id: number; name: string; externalId: string; email: string; enabled: boolean; activeDepartmentId: number | null
  contextVersion: number; createdAt: string; departmentIds: number[]
}
export interface Member {
  id: number; name: string; externalId: string; email: string; enabled: boolean; roleIds?: number[]
  /** Expiry of each time-limited role, keyed by role id; permanent roles are absent. */
  roleExpiries?: Record<string, string>
}
export interface Role { id: number; name: string; code: string; description: string; enabled: boolean; createdAt: string; memberCount: number; permissionIds: number[] }
export interface Permission { id: number; name: string; code: string; resource: string; action: string; createdAt: string; roleCount: number }
export interface AuditEvent {
  id: number; actor: string; action: string; target: string; detail: string; sourceIp: string; createdAt: string
  departmentId: number | null; departmentName: string | null; applicationId: number | null; applicationName: string | null
}
export interface Overview {
  applications: number; enabledApplications: number; departments: number; users: number; enabledUsers: number; departmentMembers: number
  roles: number; permissions: number; members: number; activeKeys: number; activeContexts: number; auditLast24h: number
}
export interface Health { status: string; database: string; cache: string; model?: string }
export interface Account {
  username: string; displayName: string; role: string; enabled: boolean; failedAttempts: number; locked: boolean
  lockedUntil: string | null; lastLoginAt: string | null; passwordChangedAt: string | null; createdAt: string
  activeSessions: number; allApplications: boolean; applicationIds: number[]; mfaEnabled: boolean
  /** The managing account; null for a root. manageable: whether the signed-in operator may change this account. */
  parentUsername: string | null; kind: AdminKind; allDepartments: boolean; departmentIds: number[]; manageable: boolean
}
export interface AccessDomain {
  departmentId: number; departmentName: string; applicationId: number; applicationName: string; applicationEnabled: boolean
  roles: { id: number; name: string; code: string; enabled: boolean; expiresAt: string | null; grantedBy: string | null }[]; permissions: string[]
}
export interface ActiveContext {
  departmentId: number; departmentName: string; applicationId: number; applicationName: string
  createdAt: string; expiresAt: string; activeRoleCount: number; valid: boolean
}
export interface UserAccess { user: User; domains: AccessDomain[]; contexts: ActiveContext[] }
/** userId, departmentId and roleId become null once that record is deleted; the names are kept from submission. */
export interface AccessRequest {
  id: number; userId: number | null; userName: string; externalId: string
  departmentId: number | null; departmentName: string; applicationId: number; applicationName: string
  roleId: number | null; roleName: string; roleCode: string; roleEnabled: boolean | null
  reason: string; durationDays: number | null; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  requestedBy: string; decidedBy: string | null; decidedAt: string | null; decisionComment: string | null
  grantExpiresAt: string | null; createdAt: string
}
export interface AccessReview {
  id: number; name: string; applicationId: number; applicationName: string
  departmentId: number | null; departmentName: string | null
  status: 'OPEN' | 'CLOSED'; items: number; pending: number; kept: number; revoked: number; skipped: number
  dueAt: string | null; createdBy: string; createdAt: string; closedBy: string | null; closedAt: string | null; summary: string | null
}
export interface ReviewItem {
  id: number; userId: number | null; userName: string; externalId: string; roleId: number | null; roleName: string; roleCode: string
  departmentId: number | null; departmentName: string; decision: 'PENDING' | 'KEEP' | 'REVOKE'
  comment: string | null; decidedBy: string | null; decidedAt: string | null
  /** applied: closing processed the item; skipped: the assignment had changed since the snapshot, so it was not revoked. */
  applied: boolean; skipped: boolean
}
export interface Webhook {
  id: number; name: string; url: string; events: string[]; enabled: boolean
  pending: number; failed: number; lastDeliveredAt: string | null; createdAt: string
}
export interface WebhookDelivery {
  id: number; eventId: string; eventType: string; status: 'PENDING' | 'SENDING' | 'DELIVERED' | 'FAILED'
  attempts: number; lastStatus: number | null; lastError: string | null
  nextAttemptAt: string | null; createdAt: string; deliveredAt: string | null
}
export interface PermissionHolderRole { id: number; name: string; code: string; expiresAt: string | null }
export interface PermissionHolder { userId: number; userName: string; externalId: string; departmentId: number; departmentName: string; roles: PermissionHolderRole[] }
/** total counts every (user, department) holder; holders is capped and truncated says whether some were left out. */
export interface PermissionHolders { code: string; applicationId: number; total: number; truncated: boolean; holders: PermissionHolder[] }
export interface ExpiringAssignment {
  userId: number; userName: string; externalId: string; roleId: number; roleName: string; roleCode: string
  departmentId: number; departmentName: string; applicationId: number; applicationName: string
  expiresAt: string; grantedBy: string | null; grantedAt: string | null
}
export interface SystemCounts {
  users: number; enabledUsers: number; departments: number; applications: number; enabledApplications: number
  roles: number; permissions: number; assignments: number; activeContexts: number
}
/** Every key is always present; the ones read from the database are null while it is unavailable. */
export interface SystemStatus {
  version: string; uptimeSeconds: number; javaVersion: string; cache: string; database: 'UP' | 'DOWN'; databaseLatencyMs: number
  counts: SystemCounts | null
  pendingRequests: number | null; openReviews: number | null; webhookBacklog: number | null; expiringIn7Days: number | null
  audit: AuditSealState | null
}
export interface AuditSealState {
  lastId: number; lastSeq: number; lastHash: string; sealedCount: number; anchorSeq: number
  unsealed: number; total: number; updatedAt: string | null
}
export interface AuditVerifyResult {
  ok: boolean; problem: 'LINK_BROKEN' | 'MODIFIED' | 'TAIL_MISSING' | null; checked: number
  firstSeq: number | null; lastSeq: number | null; firstId: number | null; lastId: number | null
  brokenAt: number | null; brokenSeq: number | null; complete: boolean; nextSeq: number | null; seal: AuditSealState
}
export interface PermissionManifest {
  version: number; application: { code: string; name: string }; exportedAt: string
  departments: {
    code: string; name: string
    permissions: { code: string; name: string; resource: string; action: string }[]
    roles: { code: string; name: string; description: string; enabled: boolean; permissions: string[] }[]
  }[]
}
export interface ManifestImportResult { permissionsCreated: number; permissionsUpdated: number; rolesCreated: number; rolesUpdated: number }
export interface UserImportFailure { line: number; externalId: string; error: string }
export interface UserImportResult { created: number; updated: number; failed: UserImportFailure[] }
export interface SearchResults {
  users: { id: number; name: string; externalId: string; enabled: boolean }[]
  departments: { id: number; name: string; code: string }[]
  applications: { id: number; name: string; code: string; enabled: boolean }[]
  roles: { id: number; name: string; code: string; departmentId: number; departmentName: string; applicationId: number; applicationName: string }[]
}
export interface Simulation {
  eligible: boolean; issues: string[]; notices: string[]; user: User; member: boolean; applicationEnabled: boolean; switchesDepartment: boolean
  roles: { id: number; name: string; code: string; enabled: boolean; active: boolean }[]
  permissions: { id: number; code: string; name: string; resource: string; action: string; grantedBy: number[] }[]
  decisions?: Record<string, boolean>; allowed?: boolean
}
