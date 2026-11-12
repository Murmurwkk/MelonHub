import dayjs from 'dayjs'
import type { AdminFunctionCode } from './types'

/** Names of the console functions, as in the admin_functions table (V6). */
export const functionLabels: Record<AdminFunctionCode, string> = {
  'org.view': '查看部门、用户与软件', 'department.manage': '维护部门', 'user.manage': '维护用户', 'application.manage': '维护软件',
  'application.credentials': '管理 API Key 与 Webhook', 'domain.view': '查看授权配置', 'domain.configure': '配置角色与权限',
  'domain.assign': '分配用户角色', 'domain.transfer': '复制配置与导入清单', 'governance.view': '查看审批与复核',
  'request.approve': '审批角色申请', 'review.manage': '发起与处理权限复核', 'audit.view': '查看与校验审计日志',
  'system.view': '查看系统状态', 'account.manage': '管理管理员账号', 'role.manage': '管理管理员角色',
}

/** Tag colours of the built-in administrator roles; roles defined in the console use the default colour. */
const builtinRoleColors: Record<string, string> = { SUPER_ADMIN: 'red', ADMIN: 'blue', APP_ADMIN: 'cyan', AUDITOR: 'default' }
export const roleColor = (code: string) => builtinRoleColors[code] ?? 'purple'

const actionGroups: [string, Record<string, string>][] = [
  ['登录与账号', {
    LOGIN: '登录', LOGOUT: '退出登录', LOGIN_FAILED: '登录失败', LOGIN_BLOCKED: '锁定期内尝试登录', ACCOUNT_LOCKED: '账号被锁定',
    PASSWORD_CHANGED: '修改本人密码', ACCOUNT_CREATED: '新建管理员', ACCOUNT_UPDATED: '修改管理员', ACCOUNT_DELETED: '删除管理员',
    ACCOUNT_PASSWORD_RESET: '重置管理员密码', ACCOUNT_UNLOCKED: '解锁管理员',
    ADMIN_ROLE_CREATED: '新建管理员角色', ADMIN_ROLE_UPDATED: '修改管理员角色', ADMIN_ROLE_DELETED: '删除管理员角色',
    MFA_ENABLED: '启用两步验证', MFA_DISABLED: '停用两步验证', MFA_RESET: '重置两步验证',
  }],
  ['组织与用户', {
    DEPARTMENT_CREATED: '新建部门', DEPARTMENT_UPDATED: '修改部门', DEPARTMENT_DELETED: '删除部门',
    USER_CREATED: '新建用户', USER_UPDATED: '修改用户', USER_ENABLED: '启用用户', USER_DISABLED: '停用用户', USER_DELETED: '删除用户',
    USER_CONTEXTS_REVOKED: '强制用户下线',
  }],
  ['软件与密钥', {
    APPLICATION_CREATED: '接入软件', APPLICATION_UPDATED: '修改软件', APPLICATION_ENABLED: '启用软件', APPLICATION_DISABLED: '停用软件',
    APPLICATION_DELETED: '删除软件', API_KEY_CREATED: '新建 API Key', API_KEY_REVOKED: '吊销 API Key', API_KEY_ROTATED: '紧急轮换 API Key',
    APPLICATION_RATE_LIMIT_UPDATED: '调整软件限流',
  }],
  ['授权配置', {
    ROLE_CREATED: '新建角色', ROLE_UPDATED: '修改角色', ROLE_ENABLED: '启用角色', ROLE_DISABLED: '停用角色', ROLE_DELETED: '删除角色',
    ROLE_PERMISSIONS_UPDATED: '调整角色权限', ROLE_MEMBERS_UPDATED: '调整角色成员', USER_ROLES_UPDATED: '调整用户角色', USER_ROLES_EXPIRED: '角色到期',
    PERMISSION_CREATED: '新建权限', PERMISSION_UPDATED: '修改权限', PERMISSION_DELETED: '删除权限', DOMAIN_COPIED: '复制授权配置',
  }],
  ['申请与复核', {
    ACCESS_REQUESTED: '提交权限申请', ACCESS_REQUEST_APPROVED: '批准申请', ACCESS_REQUEST_REJECTED: '驳回申请', ACCESS_REQUEST_CANCELLED: '撤回申请',
    REVIEW_CREATED: '发起权限复核', REVIEW_CLOSED: '关闭权限复核',
  }],
  ['集成与导入', {
    WEBHOOK_CREATED: '新建 Webhook', WEBHOOK_UPDATED: '修改 Webhook', WEBHOOK_DELETED: '删除 Webhook', WEBHOOK_TESTED: '测试 Webhook',
    USER_IMPORTED: '批量导入用户', MANIFEST_IMPORTED: '导入权限清单',
  }],
  ['权限上下文', { CONTEXT_ISSUED: '创建权限上下文', CONTEXT_REVOKED: '注销权限上下文' }],
]
export const actionLabels: Record<string, string> = Object.assign({}, ...actionGroups.map(([, items]) => items))
export const actionOptions = actionGroups.map(([label, items]) => ({ label, options: Object.entries(items).map(([value, text]) => ({ value, label: text })) }))
const riskyActions = new Set(['LOGIN_FAILED', 'LOGIN_BLOCKED', 'ACCOUNT_LOCKED', 'API_KEY_ROTATED', 'APPLICATION_DISABLED', 'APPLICATION_DELETED', 'USER_CONTEXTS_REVOKED', 'ACCOUNT_DELETED'])
export function actionColor(action: string) {
  if (riskyActions.has(action)) return 'red'
  if (action.endsWith('_DELETED') || action.endsWith('_DISABLED') || action.endsWith('_REVOKED') || action.endsWith('_EXPIRED')) return 'orange'
  if (action.endsWith('_CREATED') || action === 'DOMAIN_COPIED') return 'green'
  if (action.startsWith('LOG')) return 'default'
  return 'blue'
}

export const keyStatus: Record<string, { text: string; color: string }> = {
  ACTIVE: { text: '有效', color: 'green' }, EXPIRED: { text: '已过期', color: 'orange' }, REVOKED: { text: '已吊销', color: 'default' },
}
export const cacheStatus: Record<string, { text: string; badge: 'success' | 'processing' | 'warning' | 'default' | 'error' }> = {
  UP: { text: '正常', badge: 'success' },
  RECOVERING: { text: '恢复中（暂用数据库校验）', badge: 'processing' },
  DOWN: { text: '不可用（已回退数据库校验）', badge: 'warning' },
  DISABLED: { text: '未启用', badge: 'default' },
}

export const requestStatus: Record<string, { text: string; color: string }> = {
  PENDING: { text: '待审批', color: 'gold' }, APPROVED: { text: '已批准', color: 'green' },
  REJECTED: { text: '已驳回', color: 'red' }, CANCELLED: { text: '已撤回', color: 'default' },
}
export const reviewStatus: Record<string, { text: string; color: string }> = {
  OPEN: { text: '进行中', color: 'processing' }, CLOSED: { text: '已关闭', color: 'default' },
}
export const reviewDecision: Record<string, { text: string; color: string }> = {
  PENDING: { text: '未决', color: 'gold' }, KEEP: { text: '保留', color: 'green' }, REVOKE: { text: '回收', color: 'red' },
}
export const deliveryStatus: Record<string, { text: string; color: string }> = {
  PENDING: { text: '待投递', color: 'gold' }, SENDING: { text: '投递中', color: 'processing' },
  DELIVERED: { text: '已送达', color: 'green' }, FAILED: { text: '失败', color: 'red' },
}
export const webhookEventLabels: Record<string, string> = {
  '*': '全部事件',
  'user.enabled': '用户启用', 'user.disabled': '用户停用', 'user.deleted': '用户删除', 'user.updated': '用户资料变更',
  'user.roles.changed': '用户角色变更', 'user.roles.expired': '用户角色到期', 'role.disabled': '角色停用', 'role.permissions.changed': '角色权限变更',
  'user.contexts.revoked': '用户强制下线', 'role.enabled': '角色启用', 'role.deleted': '角色删除',
  'permission.updated': '权限修改', 'permission.deleted': '权限删除',
  'application.enabled': '软件启用', 'application.disabled': '软件停用', 'apikey.revoked': '密钥吊销',
  'access.approved': '申请批准', 'access.rejected': '申请驳回', 'review.closed': '复核关闭', 'webhook.test': '测试事件',
}
export const webhookEventOptions = Object.entries(webhookEventLabels).filter(([value]) => value !== 'webhook.test').map(([value, label]) => ({ value, label }))

/** True when the timestamp exists, is still in the future, and falls within `days`. */
export function expiringSoon(value?: string | null, days = 7) {
  if (!value) return false
  const diff = dayjs(value).diff(dayjs(), 'day', true)
  return diff >= 0 && diff <= days
}

export function fmt(value?: string | null) { return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—' }
export function fromNow(value?: string | null) {
  if (!value) return '—'
  const minutes = dayjs().diff(dayjs(value), 'minute')
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 1440) return `${Math.floor(minutes / 60)} 小时前`
  return fmt(value)
}
