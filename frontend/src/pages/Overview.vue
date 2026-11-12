<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TableColumnsType } from 'ant-design-vue'
import { RightOutlined } from '@ant-design/icons-vue'
import { errorText, get, type PageResult } from '../api'
import { actionColor, actionLabels, cacheStatus, fmt, fromNow } from '../labels'
import { useHub } from '../store'
import type { Application, AuditEvent, Overview, SystemStatus } from '../types'
import EmptyState from '../components/EmptyState.vue'
import PageHead from '../components/PageHead.vue'
import AuthorizationDrawer from '../components/AuthorizationDrawer.vue'

const hub = useHub(), router = useRouter()
const overview = ref<Overview | null>(null), recent = ref<AuditEvent[]>([]), governance = ref<SystemStatus | null>(null)
const loading = ref(false), loaded = ref(false), failure = ref('')
const canStatus = computed(() => hub.can('system.view'))
let sequence = 0

async function load() {
  const current = ++sequence
  loading.value = true
  failure.value = ''
  try {
    const [o, a, s] = await Promise.all([
      hub.hasDomain ? get<Overview>('/admin/overview', hub.domainQuery) : Promise.resolve(null),
      get<PageResult<AuditEvent>>('/admin/audit', { page: 1, size: 8 }),
      !canStatus.value ? Promise.resolve(null) : get<SystemStatus>('/admin/system/status').catch(() => null),
      hub.refreshHealth(),
    ])
    if (current !== sequence) return
    overview.value = o
    recent.value = a.items
    governance.value = s
  } catch (e) {
    if (current === sequence) failure.value = errorText(e)
  } finally {
    if (current === sequence) { loading.value = false; loaded.value = true }
  }
}
async function refresh() { await hub.loadLists().catch(() => {}); await load() }
onMounted(load)
watch(() => hub.domainKey, (now, before) => { if (now !== before) load() })

const disabledApps = computed(() => hub.applications.filter(a => !a.enabled).length)
const users = computed(() => overview.value?.users ?? governance.value?.counts?.users)
const enabledUsers = computed(() => overview.value?.enabledUsers ?? governance.value?.counts?.enabledUsers)
const metrics = computed(() => [
  { label: '接入软件', value: hub.applications.length, sub: disabledApps.value ? `${disabledApps.value} 个停用` : '', to: '/applications' },
  { label: '部门', value: hub.departments.length, sub: '', to: '/departments' },
  { label: '用户', value: users.value, sub: enabledUsers.value === undefined ? '' : `${enabledUsers.value} 启用`, to: '/users' },
  { label: '近 24 小时操作', value: overview.value?.auditLast24h, sub: '', to: '/audit' },
])
const todos = computed(() => {
  const g = governance.value
  if (!g) return []
  return [
    { label: '待审批申请', value: g.pendingRequests, to: '/approvals' },
    { label: '进行中的复核', value: g.openReviews, to: '/reviews' },
    { label: '7 天内到期的授权', value: g.expiringIn7Days, to: '/assignments?tab=expiring' },
  ].filter((t): t is { label: string; value: number; to: string } => t.value !== null)
})
const domainStats = computed(() => [
  { label: '角色', value: overview.value?.roles, to: '/roles' },
  { label: '权限', value: overview.value?.permissions, to: '/permissions' },
  { label: '已授权用户', value: overview.value?.members, to: '/assignments' },
  { label: '部门成员', value: overview.value?.departmentMembers, to: '/assignments' },
  { label: '有效 API Key', value: overview.value?.activeKeys, to: '/applications' },
  { label: '有效上下文', value: overview.value?.activeContexts, to: '/simulate' },
])
const steps = [
  { title: '创建部门', to: '/departments' },
  { title: '接入软件并保存 API Key', to: '/applications' },
  { title: '在软件配置中定义权限和角色', to: '/applications' },
  { title: '在部门配置中给成员分配角色', to: '/departments' },
]

const dbUp = computed(() => hub.health?.database === 'UP')
const cache = computed(() => cacheStatus[hub.health?.cache ?? ''])
const dotOf = (badge?: string) => badge === 'success' ? 'ok' : badge === 'error' ? 'bad' : badge === 'warning' || badge === 'processing' ? 'warn' : ''
function uptime(seconds: number) {
  const d = Math.floor(seconds / 86400), h = Math.floor(seconds % 86400 / 3600), m = Math.floor(seconds % 3600 / 60)
  return d ? `${d} 天 ${h} 小时` : h ? `${h} 小时 ${m} 分` : `${m} 分钟`
}

const appsWithoutKey = computed(() => hub.applications.filter(a => a.enabled && !a.activeKeyCount))
const appColumns: TableColumnsType<Application> = [
  { title: '软件', dataIndex: 'name', key: 'name', ellipsis: true },
  { title: '编码', dataIndex: 'code', key: 'code', width: 140, ellipsis: true },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '角色', dataIndex: 'roleCount', width: 64, align: 'right' },
  { title: '权限', dataIndex: 'permissionCount', width: 64, align: 'right' },
  { title: '有效密钥', dataIndex: 'activeKeyCount', width: 84, align: 'right' },
  { title: '', key: 'go', width: 72, align: 'right' },
]
const configurationOpen = ref(false), configurationApplication = ref<number>(), configurationTab = ref('roles')
function open(app: Application) { configurationApplication.value = app.id; configurationTab.value = 'roles'; configurationOpen.value = true }
function openDomain(to: string) {
  if (['/roles', '/permissions', '/assignments'].includes(to)) {
    configurationApplication.value = hub.applicationId
    configurationTab.value = to.slice(1)
    configurationOpen.value = true
  } else router.push(to)
}
</script>

<template>
  <div class="page">
    <PageHead title="概览">
      <a-button :loading="loading" @click="refresh">刷新</a-button>
    </PageHead>
    <a-alert v-if="failure" type="error" show-icon :message="failure" class="block-gap" />

    <div class="ov-stats">
      <button v-for="m in metrics" :key="m.label" type="button" class="stat link" @click="router.push(m.to)">
        <span class="stat-label">{{ m.label }}</span>
        <span class="stat-value">
          <template v-if="m.value !== undefined">{{ m.value }}</template>
          <a-skeleton-button v-else-if="!loaded" active size="small" />
          <template v-else>—</template>
          <small v-if="m.sub">{{ m.sub }}</small>
        </span>
      </button>
    </div>

    <div class="ov-grid">
      <div class="ov-main">
        <section v-if="hub.hasDomain" class="ov-sec">
          <div class="ov-head">
            <h3>{{ hub.department?.name }}<span class="sep">/</span>{{ hub.application?.name }}</h3>
            <router-link to="/simulate" class="ov-link">权限诊断</router-link>
          </div>
          <div class="facts">
            <a v-for="s in domainStats" :key="s.label" class="fact" @click="openDomain(s.to)">
              <span class="fact-value">
                <template v-if="s.value !== undefined">{{ s.value }}</template>
                <a-skeleton-button v-else-if="!loaded" active size="small" />
                <template v-else>—</template>
              </span>
              <span class="fact-label">{{ s.label }}</span>
            </a>
          </div>
        </section>
        <section v-else class="ov-sec">
          <div class="ov-head"><h3>开始使用</h3></div>
          <ol class="steps">
            <li v-for="s in steps" :key="s.title"><router-link :to="s.to">{{ s.title }}</router-link></li>
          </ol>
        </section>

        <section v-if="hub.applications.length" class="ov-sec">
          <div class="ov-head">
            <h3>接入软件</h3>
            <router-link to="/applications" class="ov-link">全部</router-link>
          </div>
          <p v-if="appsWithoutKey.length" class="ov-warn">{{ appsWithoutKey.map(a => a.name).join('、') }} 没有有效的 API Key，调用权限接口会失败。</p>
          <a-table :columns="appColumns" :data-source="hub.applications" row-key="id" size="small" :scroll="{ x: 700 }" :pagination="{ size: 'small', pageSize: 8, hideOnSinglePage: true }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">{{ record.name }}<span v-if="record.id === hub.applicationId" class="muted current">当前</span></template>
              <template v-else-if="column.key === 'code'"><code>{{ record.code }}</code></template>
              <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
              <template v-else-if="column.key === 'go'"><a @click="open(record)">{{ hub.can('domain.configure', record.id) || hub.can('domain.assign', record.id) ? '配置' : '查看' }}</a></template>
            </template>
          </a-table>
        </section>
      </div>

      <aside class="ov-side">
        <section v-if="todos.length" class="ov-sec">
          <div class="ov-head"><h3>待处理</h3></div>
          <ul class="rows">
            <li v-for="t in todos" :key="t.label">
              <router-link :to="t.to" class="row" :class="{ zero: !t.value }">
                <span>{{ t.label }}</span><b>{{ t.value }}</b><RightOutlined class="row-go" />
              </router-link>
            </li>
          </ul>
        </section>

        <section class="ov-sec">
          <div class="ov-head">
            <h3>服务状态</h3>
            <router-link v-if="canStatus" to="/status" class="ov-link">详情</router-link>
          </div>
          <ul class="rows">
            <li class="row static"><span><i class="dot" :class="dbUp ? 'ok' : 'bad'" />数据库</span><b>{{ dbUp ? '正常' : '不可用' }}<small v-if="governance">{{ governance.databaseLatencyMs }} ms</small></b></li>
            <li class="row static"><span><i class="dot" :class="dotOf(cache?.badge)" />权限缓存</span><b>{{ cache?.text ?? (hub.health?.cache || '未知') }}</b></li>
            <li v-if="governance && governance.webhookBacklog !== null" class="row static"><span><i class="dot" :class="governance.webhookBacklog ? 'warn' : 'ok'" />待投递通知</span><b>{{ governance.webhookBacklog }}</b></li>
            <li v-if="governance" class="row static"><span><i class="dot" />运行时长</span><b>{{ uptime(governance.uptimeSeconds) }}</b></li>
          </ul>
        </section>

      </aside>
    </div>

    <section class="ov-sec ov-recent">
      <div class="ov-head">
        <h3>最近操作</h3>
        <router-link to="/audit" class="ov-link">审计日志</router-link>
      </div>
      <ul v-if="recent.length" class="feed">
        <li v-for="item in recent" :key="item.id">
          <a-tooltip :title="fmt(item.createdAt)"><span class="feed-time">{{ fromNow(item.createdAt) }}</span></a-tooltip>
          <span class="feed-action"><a-tag :color="actionColor(item.action)">{{ actionLabels[item.action] ?? item.action }}</a-tag></span>
          <span class="feed-target">{{ item.target }}<span v-if="item.detail" class="feed-detail">{{ item.detail }}</span></span>
          <span class="feed-actor">{{ item.actor }}</span>
        </li>
      </ul>
      <a-skeleton v-else-if="!loaded" active :title="false" :paragraph="{ rows: 4 }" />
      <EmptyState v-else title="暂无操作记录" compact />
    </section>
    <AuthorizationDrawer v-model:open="configurationOpen" :application-id="configurationApplication" :department-id="hub.departmentId" :initial-tab="configurationTab" />
  </div>
</template>

<style scoped>
.ov-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin-bottom: 36px; border-block: 1px solid var(--mh-border-soft); }
.stat { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; min-width: 0; padding: 16px 20px; border: 0; border-left: 1px solid var(--mh-border-soft); background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; transition: background-color 0.15s; }
.stat:first-child { border-left: 0; padding-left: 0; }
.stat:hover { background: var(--mh-hover); }
.stat:first-child:hover { background: transparent; }
.stat:hover .stat-label { color: var(--mh-text); }
.stat-label { color: var(--mh-text-3); font-size: 12px; }
.stat-value { color: var(--mh-text); font-size: 24px; font-weight: 600; line-height: 32px; font-variant-numeric: tabular-nums; }
.stat-value small { margin-left: 8px; color: var(--mh-text-3); font-size: 12px; font-weight: 400; }

.ov-grid { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 48px; align-items: start; }
.ov-main, .ov-side { display: flex; flex-direction: column; gap: 36px; min-width: 0; }
.ov-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--mh-border-soft); }
.ov-head h3 { margin: 0; overflow: hidden; color: var(--mh-text); font-size: 14px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sep { margin: 0 8px; color: var(--mh-text-4); font-weight: 400; }
.ov-link { flex: none; color: var(--mh-text-3); font-size: 12px; }
.ov-link:hover { color: var(--mh-text); }
.ov-warn { margin: 0 0 12px; color: var(--mh-warn); font-size: 12px; }
.current { margin-left: 8px; font-size: 12px; }

.facts { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 16px; }
.fact { display: flex; flex-direction: column; gap: 2px; min-width: 0; color: inherit; }
.fact-value { color: var(--mh-text); font-size: 18px; font-weight: 600; line-height: 26px; font-variant-numeric: tabular-nums; }
.fact-label { color: var(--mh-text-3); font-size: 12px; }
.fact:hover .fact-label { color: var(--mh-text); }

.steps { margin: 0; padding-left: 20px; color: var(--mh-text-3); line-height: 2; }

.rows { margin: 0; padding: 0; list-style: none; }
.row { display: flex; align-items: center; gap: 8px; padding: 7px 0; color: var(--mh-text-2); }
.row > span { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.row b { margin-left: auto; color: var(--mh-text); font-weight: 500; font-variant-numeric: tabular-nums; }
.row small { margin-left: 6px; color: var(--mh-text-3); font-weight: 400; }
.row.zero b { color: var(--mh-text-4); font-weight: 400; }
.row-go { color: var(--mh-text-4); font-size: 10px; }
a.row:hover, a.row:hover b { color: var(--mh-text); }
.dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--mh-text-4); }
.dot.ok { background: #22c55e; }
.dot.warn { background: #f59e0b; }
.dot.bad { background: #ef4444; }

/* Full width under both columns, one line per entry, so the feed never outgrows the columns above it. */
.ov-recent { margin-top: 36px; }
.feed { margin: 0; padding: 0; list-style: none; }
.feed li { display: grid; grid-template-columns: 96px 124px minmax(0, 1fr) minmax(0, 160px); grid-template-areas: 'time action target actor'; align-items: center; gap: 4px 16px; padding: 7px 0; }
.feed li + li { border-top: 1px solid var(--mh-border-soft); }
.feed-time { grid-area: time; color: var(--mh-text-3); font-size: 12px; white-space: nowrap; }
.feed-action { grid-area: action; min-width: 0; overflow: hidden; }
.feed-action .ant-tag { margin: 0; }
.feed-target { grid-area: target; overflow: hidden; color: var(--mh-text-2); text-overflow: ellipsis; white-space: nowrap; }
.feed-detail { margin-left: 10px; color: var(--mh-text-3); font-size: 12px; }
.feed-actor { grid-area: actor; overflow: hidden; color: var(--mh-text-3); font-size: 12px; text-align: right; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 1180px) {
  .ov-grid { grid-template-columns: minmax(0, 1fr); gap: 36px; }
  .facts { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .ov-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-bottom: 28px; }
  .stat { padding: 12px 14px; }
  .stat:nth-child(odd) { border-left: 0; padding-left: 0; }
  .stat:nth-child(-n + 2) { border-bottom: 1px solid var(--mh-border-soft); }
  .stat:nth-child(odd):hover { background: transparent; }
  .stat-value { font-size: 20px; line-height: 28px; }
  .facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .ov-recent { margin-top: 28px; }
  .feed li { grid-template-columns: auto minmax(0, 1fr) auto; grid-template-areas: 'action target target' 'actor actor time'; }
  .feed-actor { text-align: left; }
}
</style>
