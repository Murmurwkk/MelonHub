import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { get, session } from './api'
import { functionLabels } from './labels'
import type { AdminFunctionCode, Application, Department, Health, Principal } from './types'

const DEPARTMENT = 'melonhub-department', APPLICATION = 'melonhub-application'
/** Functions that change what every department shares; only accounts over all departments can use them (see AdminFunction). */
const PLATFORM_WIDE = new Set<AdminFunctionCode>(['department.manage', 'application.manage', 'application.credentials', 'role.manage'])

export const useHub = defineStore('hub', () => {
  const me = ref<Principal | null>(null)
  const departments = ref<Department[]>([])
  const applications = ref<Application[]>([])
  const departmentId = ref(Number(localStorage.getItem(DEPARTMENT)) || 0)
  const applicationId = ref(Number(localStorage.getItem(APPLICATION)) || 0)
  const health = ref<Health | null>(null)

  const department = computed(() => departments.value.find(d => d.id === departmentId.value))
  const application = computed(() => applications.value.find(a => a.id === applicationId.value))
  const hasDomain = computed(() => !!department.value && !!application.value)
  /** Changes whenever the isolation domain changes; domain pages reload on it and drop stale responses. */
  const domainKey = computed(() => hasDomain.value ? `${departmentId.value}:${applicationId.value}` : '')
  const domainQuery = computed(() => ({ departmentId: departmentId.value, applicationId: applicationId.value }))

  const functions = computed(() => new Set(me.value?.functions ?? []))
  const inApplication = (id?: number) => id === undefined || !!me.value?.allApplications || !!me.value?.applicationIds.includes(id)
  /** null is a record whose department was deleted: only operators over all departments may act on it. */
  const inDepartment = (id?: number | null) => id === undefined || !!me.value?.allDepartments || (id !== null && !!me.value?.departmentIds.includes(id))
  /** Why the operator may not use the function (in that application and department), or '' when it may. */
  function denial(fn: AdminFunctionCode, application?: number, department?: number | null) {
    if (!functions.value.has(fn)) return `当前角色没有“${functionLabels[fn]}”功能`
    if (PLATFORM_WIDE.has(fn) && !me.value?.allDepartments) return `“${functionLabels[fn]}”只对可管理全部部门的管理员开放`
    if (!inApplication(application)) return '当前账号不能管理该软件'
    if (!inDepartment(department)) return '当前账号不能管理该部门'
    return ''
  }
  /** Whether the signed-in role grants the function and the account may use it in that application and department. */
  function can(fn: AdminFunctionCode, application?: number, department?: number | null) { return !!me.value && !denial(fn, application, department) }
  /** Disabling, deleting or signing out a user needs a user that belongs only to the operator's departments. */
  function canManageUser(departments: number[]) {
    return can('user.manage') && (!!me.value?.allDepartments || (departments.length > 0 && departments.every(d => inDepartment(d))))
  }
  const canConfigure = computed(() => hasDomain.value && can('domain.configure', applicationId.value, departmentId.value))
  const canAssign = computed(() => hasDomain.value && can('domain.assign', applicationId.value, departmentId.value))
  const canTransfer = computed(() => hasDomain.value && can('domain.transfer', applicationId.value, departmentId.value))

  const departmentName = (id: number | null | undefined) => id ? departments.value.find(d => d.id === id)?.name ?? `部门 #${id}` : '—'
  const applicationName = (id: number | null | undefined) => id ? applications.value.find(a => a.id === id)?.name ?? `软件 #${id}` : '—'
  const departmentOptions = computed(() => departments.value.map(d => ({ value: d.id, label: d.name })))
  const applicationOptions = computed(() => applications.value.map(a => ({ value: a.id, label: a.enabled ? a.name : `${a.name}（已停用）` })))

  function persist() {
    localStorage.setItem(DEPARTMENT, String(departmentId.value))
    localStorage.setItem(APPLICATION, String(applicationId.value))
  }
  function setDomain(dept?: number, app?: number) {
    if (dept !== undefined) departmentId.value = dept
    if (app !== undefined) applicationId.value = app
    persist()
  }
  async function loadLists() {
    const [d, a] = await Promise.all([get<Department[]>('/admin/departments'), get<Application[]>('/admin/applications')])
    departments.value = d
    applications.value = a
    if (!d.some(x => x.id === departmentId.value)) departmentId.value = d[0]?.id ?? 0
    if (!a.some(x => x.id === applicationId.value)) applicationId.value = a[0]?.id ?? 0
    persist()
  }
  async function refreshHealth() {
    // /api/health answers 503 with a JSON body when the database is down, so read the body either way.
    try {
      const response = await fetch('/api/health', { headers: { Accept: 'application/json' } })
      health.value = await response.json() as Health
    } catch {
      health.value = { status: 'DOWN', database: 'UNKNOWN', cache: 'UNKNOWN' }
    }
  }
  function signIn(principal: Principal) { me.value = principal }
  function signOut() {
    session.clear()
    me.value = null
    departments.value = []
    applications.value = []
  }

  return {
    me, departments, applications, departmentId, applicationId, health, department, application, hasDomain, domainKey, domainQuery,
    can, denial, canManageUser, canConfigure, canAssign, canTransfer, departmentName, applicationName, departmentOptions, applicationOptions,
    setDomain, loadLists, refreshHealth, signIn, signOut,
  }
})
