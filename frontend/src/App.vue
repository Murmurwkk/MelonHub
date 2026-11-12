<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import {
  ApartmentOutlined, ApiOutlined, AppstoreOutlined, AuditOutlined, DashboardOutlined, DownOutlined, ExperimentOutlined,
  LockOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, MonitorOutlined, ReconciliationOutlined,
  SafetyCertificateOutlined, SafetyOutlined, ScheduleOutlined, TeamOutlined, UserOutlined,
} from '@ant-design/icons-vue'
import { get, send, session } from './api'
import { useHub } from './store'
import { antTheme } from './theme'
import { cacheStatus } from './labels'
import { isPhone, labelTables } from './responsive'
import type { AdminFunctionCode, Principal } from './types'
import LoginView from './components/LoginView.vue'
import DomainPicker from './components/DomainPicker.vue'
import PasswordModal from './components/PasswordModal.vue'
import MfaModal from './components/MfaModal.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import ThemeToggle from './components/ThemeToggle.vue'
import EmptyState from './components/EmptyState.vue'

const hub = useHub(), route = useRoute(), router = useRouter()
const booting = ref(true), collapsed = ref(false), navOpen = ref(false), passwordOpen = ref(false), mfaOpen = ref(false), notice = ref('')
let healthTimer: ReturnType<typeof setInterval> | undefined

const compactEmpty = new Set(['Select', 'TreeSelect', 'Cascader', 'Transfer', 'AutoComplete', 'Mentions'])
const renderEmpty = (name?: string) => h(EmptyState, { compact: !!name && compactEmpty.has(name) })
const icon = (component: Component) => () => h(component)
type MenuEntry = { key: string; icon: () => unknown; label: string; functions?: AdminFunctionCode[] }
/** Entries whose page the signed-in role can open; groups left without entries are dropped. */
const menu = computed(() => {
  const groups: { key: string; label: string; children: MenuEntry[] }[] = [
    { key: 'org', label: '组织与软件', children: [
      { key: '/departments', icon: icon(ApartmentOutlined), label: '部门', functions: ['org.view'] },
      { key: '/users', icon: icon(TeamOutlined), label: '用户', functions: ['org.view'] },
      { key: '/applications', icon: icon(AppstoreOutlined), label: '软件接入', functions: ['org.view'] },
    ] },
    { key: 'grant', label: '授权治理', children: [
      { key: '/approvals', icon: icon(ScheduleOutlined), label: '审批中心', functions: ['governance.view'] },
      { key: '/reviews', icon: icon(ReconciliationOutlined), label: '权限复核', functions: ['governance.view'] },
      { key: '/simulate', icon: icon(ExperimentOutlined), label: '权限诊断', functions: ['domain.view'] },
    ] },
    { key: 'security', label: '安全与审计', children: [
      { key: '/audit', icon: icon(AuditOutlined), label: '审计日志', functions: ['audit.view'] },
      { key: '/status', icon: icon(MonitorOutlined), label: '系统状态', functions: ['system.view'] },
      { key: '/accounts', icon: icon(UserOutlined), label: '管理员账号', functions: ['account.manage'] },
      { key: '/admin-roles', icon: icon(SafetyCertificateOutlined), label: '管理员角色', functions: ['role.manage', 'account.manage'] },
    ] },
    { key: 'dev', label: '开发接入', children: [{ key: '/integration', icon: icon(ApiOutlined), label: '接入指南' }] },
  ]
  const allowed = (entry: MenuEntry) => !entry.functions || entry.functions.some(fn => hub.can(fn))
  const items: Record<string, unknown>[] = [{ key: '/', icon: icon(DashboardOutlined), label: '概览' }]
  for (const group of groups) {
    const children = group.children.filter(allowed).map(({ key, icon, label }) => ({ key, icon, label }))
    if (children.length) items.push({ type: 'group', key: group.key, label: group.label, children })
  }
  return items
})

const healthBadge = computed(() => {
  const h = hub.health
  if (!h) return { status: 'default', text: '检测中' }
  if (h.database !== 'UP') return { status: 'error', text: '数据库不可用' }
  if (h.cache === 'DOWN' || h.cache === 'RECOVERING') return { status: 'warning', text: '缓存降级' }
  return { status: 'success', text: '服务正常' }
})
const healthTip = computed(() => hub.health ? `数据库：${hub.health.database === 'UP' ? '正常' : '不可用'}；权限缓存：${cacheStatus[hub.health.cache]?.text ?? hub.health.cache}` : '')
const initial = computed(() => (hub.me?.displayName || hub.me?.username || '?').slice(0, 1).toUpperCase())

async function enter(principal: Principal) {
  hub.signIn(principal)
  notice.value = ''
  await Promise.all([hub.loadLists(), hub.refreshHealth()])
  clearInterval(healthTimer)
  healthTimer = setInterval(() => { if (!document.hidden) hub.refreshHealth() }, 30_000)
}
function expire() {
  clearInterval(healthTimer)
  if (hub.me) notice.value = '登录已过期或已在其他位置下线，请重新登录'
  hub.signOut()
}
function mfaChanged() {
  clearInterval(healthTimer)
  mfaOpen.value = false
  hub.signOut()
  notice.value = '两步验证已更新，请重新登录'
}
async function logout() {
  try { await send('POST', '/auth/logout') } catch { /* the session is dropped locally either way */ }
  clearInterval(healthTimer)
  hub.signOut()
  router.push('/')
}
function go(info: { key: string | number }) { navOpen.value = false; router.push(String(info.key)) }
function home() { navOpen.value = false; router.push('/') }
function toggleNav() {
  if (isPhone.value) navOpen.value = !navOpen.value
  else collapsed.value = !collapsed.value
}
watch(() => route.path, () => { navOpen.value = false })
watch(isPhone, labelTables, { immediate: true })

onMounted(async () => {
  window.addEventListener('session-expired', expire)
  if (session.token()) {
    try { await enter(await get<Principal>('/auth/me')) } catch { hub.signOut() }
  }
  booting.value = false
})
onBeforeUnmount(() => { window.removeEventListener('session-expired', expire); clearInterval(healthTimer) })
</script>

<template>
  <a-config-provider :locale="zhCN" :theme="antTheme" :render-empty="renderEmpty" :auto-insert-space-in-button="false" component-size="middle">
    <div v-if="booting" class="boot"><a-spin /></div>
    <LoginView v-else-if="!hub.me" :notice="notice" @signed-in="enter" />
    <a-layout v-else class="shell">
      <a-layout-sider v-if="!isPhone" v-model:collapsed="collapsed" :trigger="null" collapsible :width="228" :collapsed-width="64" breakpoint="lg" theme="light" class="sider">
        <div class="logo" role="link" tabindex="0" @click="home" @keydown.enter="home">
          <img src="/brand.png" alt="" />
          <span v-show="!collapsed" class="logo-text">MelonHub</span>
        </div>
        <a-menu mode="inline" :items="menu" :selected-keys="[route.path]" class="nav" @click="go" />
      </a-layout-sider>
      <a-drawer v-else v-model:open="navOpen" placement="left" :width="264" :closable="false" root-class-name="nav-drawer">
        <div class="logo" role="link" tabindex="0" @click="home" @keydown.enter="home">
          <img src="/brand.png" alt="" />
          <span class="logo-text">MelonHub</span>
        </div>
        <a-menu mode="inline" :items="menu" :selected-keys="[route.path]" class="nav" @click="go" />
      </a-drawer>
      <a-layout class="main">
        <a-layout-header class="header">
          <button type="button" class="icon-btn" :aria-label="isPhone ? '打开导航' : collapsed ? '展开导航' : '收起导航'" @click="toggleNav">
            <MenuUnfoldOutlined v-if="isPhone || collapsed" /><MenuFoldOutlined v-else />
          </button>
          <img v-if="isPhone" src="/brand.png" alt="MelonHub" class="header-logo" @click="home" />
          <a-breadcrumb class="crumb">
            <a-breadcrumb-item v-if="route.meta.group">{{ route.meta.group }}</a-breadcrumb-item>
            <a-breadcrumb-item>{{ route.meta.title }}</a-breadcrumb-item>
          </a-breadcrumb>
          <div class="header-right">
            <GlobalSearch />
            <DomainPicker v-if="route.meta.domain && !isPhone" />
            <a-tooltip :title="healthTip">
              <span class="health-pill" :class="healthBadge.status"><i />{{ healthBadge.text }}</span>
            </a-tooltip>
            <ThemeToggle />
            <a-dropdown placement="bottomRight">
              <button type="button" class="account">
                <span class="avatar">{{ initial }}</span>
                <span class="account-meta">
                  <span class="account-name">{{ hub.me.displayName }}</span>
                  <span class="account-role">{{ hub.me.roleName }}</span>
                </span>
                <DownOutlined class="caret" />
              </button>
              <template #overlay>
                <a-menu>
                  <a-menu-item key="who" disabled>登录账号：{{ hub.me.username }} · {{ hub.me.roleName }}</a-menu-item>
                  <a-menu-divider />
                  <a-menu-item key="mfa" @click="mfaOpen = true"><SafetyOutlined /> 两步验证{{ hub.me.mfaEnabled ? '（已启用）' : '' }}</a-menu-item>
                  <a-menu-item key="password" @click="passwordOpen = true"><LockOutlined /> 修改密码</a-menu-item>
                  <a-menu-item key="logout" @click="logout"><LogoutOutlined /> 退出登录</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </a-layout-header>
        <div v-if="isPhone && route.meta.domain" class="domain-bar"><DomainPicker /></div>
        <a-layout-content class="content">
          <router-view v-slot="{ Component }"><component :is="Component" :key="route.path" /></router-view>
        </a-layout-content>
      </a-layout>
    </a-layout>
    <PasswordModal v-model:open="passwordOpen" />
    <MfaModal v-model:open="mfaOpen" @changed="mfaChanged" />
  </a-config-provider>
</template>
