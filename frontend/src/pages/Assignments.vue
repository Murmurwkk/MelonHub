<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { useRoute, useRouter } from 'vue-router'
import { get, type PageResult } from '../api'
import { useList, usePaged } from '../composables'
import { expiringSoon, fmt } from '../labels'
import { useAuthorizationScope } from '../authorizationScope'
import type { ExpiringAssignment, Member, Role } from '../types'
import type { TableColumnsType } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import PageHead from '../components/PageHead.vue'
import DomainEmpty from '../components/DomainEmpty.vue'
import ReadonlyHint from '../components/ReadonlyHint.vue'
import UserRolesForm from '../components/UserRolesForm.vue'
import BatchRolesModal from '../components/BatchRolesModal.vue'
import GuardedButton from '../components/GuardedButton.vue'
import GuardedLink from '../components/GuardedLink.vue'

const scope = useAuthorizationScope()
const props = defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ navigate: [tab: string] }>()
const route = useRoute(), router = useRouter()
const keyword = ref('')
const table = usePaged<Member>((page, size) => get<PageResult<Member>>('/admin/assignments', { ...scope.domainQuery, keyword: keyword.value.trim(), page, size }))
const roles = useList<Role>(() => get<Role[]>('/admin/roles', scope.domainQuery))
const roleMap = computed(() => new Map(roles.rows.value.map(r => [r.id, r])))
function reload() { table.load(); roles.load(); expiring.load() }

const tab = ref(!props.embedded && route.query.tab === 'expiring' ? 'expiring' : 'members')
const days = ref(7)
const expiring = useList<ExpiringAssignment>(() => get<ExpiringAssignment[]>('/admin/assignments/expiring', { days: days.value, applicationId: scope.applicationId }))
watch(tab, value => { if (!props.embedded) router.replace({ query: value === 'expiring' ? { tab: 'expiring' } : {} }) })
watch(() => route.query.tab, value => { if (!props.embedded) tab.value = value === 'expiring' ? 'expiring' : 'members' })
watch(days, () => { if (scope.hasDomain) expiring.load() })
watch(() => scope.domainKey, key => {
  if (key) { keyword.value = ''; table.load(true); roles.load(); expiring.load() }
  else { table.clear(); roles.clear(); expiring.clear() }
}, { immediate: true })

const expiringColumns: TableColumnsType<ExpiringAssignment> = [
  { title: '用户', key: 'user', width: 180, ellipsis: true },
  { title: '角色', key: 'role', width: 200, ellipsis: true },
  { title: '部门', dataIndex: 'departmentName', width: 130, ellipsis: true },
  { title: '到期时间', dataIndex: 'expiresAt', key: 'expiresAt', width: 175 },
  { title: '授予', key: 'granted', width: 220, ellipsis: true },
]

const columns: TableColumnsType<Member> = [
  { title: '姓名', dataIndex: 'name', width: 140, ellipsis: true },
  { title: '外部标识', dataIndex: 'externalId', key: 'externalId', width: 160, ellipsis: true },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '已分配角色', key: 'roles' },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' },
]

const open = ref(false), target = ref<Member | null>(null)
const grantForm = ref<InstanceType<typeof UserRolesForm>>()
const selected = ref<number[]>([]), bulkOpen = ref(false)
const rowSelection = computed(() => scope.canAssign ? {
  selectedRowKeys: selected.value,
  preserveSelectedRowKeys: true,
  onChange: (keys: (string | number)[]) => { selected.value = keys.map(Number) },
} : undefined)
watch(() => scope.departmentId, () => { selected.value = [] })
watch(() => scope.domainKey, () => { open.value = false; bulkOpen.value = false })
function navigate(value: string) {
  if (props.embedded) emit('navigate', value)
  else router.push(value === 'members' ? { path: '/users', query: { departmentId: scope.departmentId } } : '/' + value)
}
function expiryOf(row: Member, role: number) { return row.roleExpiries?.[role] ?? null }
function edit(row: Member) { target.value = row; open.value = true }
async function save() {
  const ok = await grantForm.value?.submit()
  if (ok) { open.value = false; table.load(); roles.load(); expiring.load() }
}
</script>

<template>
  <div class="page">
    <PageHead :title="embedded ? undefined : '用户授权'" description="选一次成员，即可追加多个软件的角色；表格显示当前软件的分配，可按成员个别调整。">
      <a-button :disabled="!scope.hasDomain" @click="reload"><ReloadOutlined />刷新</a-button>
    </PageHead>
    <DomainEmpty v-if="!scope.hasDomain" />
    <template v-else>
      <ReadonlyHint :editable="scope.canAssign" />
      <a-tabs v-model:active-key="tab" class="block-gap">
        <a-tab-pane key="members" tab="成员授权" />
        <a-tab-pane key="expiring" tab="即将到期" />
      </a-tabs>
      <a-card v-if="tab === 'members'" :bordered="false">
        <div class="toolbar">
          <a-input-search v-model:value="keyword" placeholder="姓名、外部标识或邮箱" allow-clear class="toolbar-search" @search="table.load(true)" />
          <span class="muted">本部门共 {{ table.total.value }} 名成员</span>
          <a @click="navigate('members')">管理本部门成员</a>
        </div>
        <div class="toolbar">
          <GuardedButton fn="domain.assign" :allowed="scope.canAssign" type="primary" :disabled="!selected.length" @click="bulkOpen = true">批量分配软件角色</GuardedButton>
          <span class="muted">已选 {{ selected.length }} 名成员，可跨页勾选</span>
          <a-button v-if="selected.length" type="link" @click="selected = []">清空选择</a-button>
          <a v-if="!roles.rows.value.length" @click="navigate('roles')">先创建角色并配置权限</a>
        </div>
        <a-table :columns="columns" :data-source="table.rows.value" :row-selection="rowSelection" row-key="id" size="small" :loading="table.loading.value" :pagination="table.pagination.value" :scroll="{ x: 900 }" @change="table.onChange">
          <template #emptyText><a-empty :image-style="{ height: '60px' }" :description="keyword.trim() ? '没有匹配的成员' : '该部门还没有成员，请先添加部门成员。'"><a-button v-if="!keyword.trim()" @click="navigate('members')">管理本部门成员</a-button></a-empty></template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'externalId'"><code>{{ record.externalId }}</code></template>
            <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
            <template v-else-if="column.key === 'roles'">
              <span v-if="!record.roleIds?.length" class="muted">未分配</span>
              <a-tag v-for="id in record.roleIds" :key="id"
                     :color="roleMap.get(id)?.enabled === false ? 'default' : expiringSoon(expiryOf(record, id)) ? 'orange' : 'blue'">
                {{ roleMap.get(id)?.name ?? `角色 #${id}` }}{{ roleMap.get(id)?.enabled === false ? '（停用）' : '' }}
                <span v-if="expiryOf(record, id)" :title="`到期：${fmt(expiryOf(record, id))}`"> · 至 {{ dayjs(expiryOf(record, id)).format('MM-DD') }}</span>
              </a-tag>
            </template>
            <template v-else-if="column.key === 'actions'"><GuardedLink fn="domain.assign" :allowed="scope.canAssign" @click="edit(record)">个别调整</GuardedLink></template>
          </template>
        </a-table>
      </a-card>
      <a-card v-else :bordered="false">
        <div class="toolbar">
          <a-radio-group v-model:value="days" option-type="button" button-style="solid"
                         :options="[{ value: 7, label: '7 天内' }, { value: 15, label: '15 天内' }, { value: 30, label: '30 天内' }]" />
          <span class="muted">当前软件下即将到期的角色分配共 {{ expiring.rows.value.length }} 条，到期后用户自动失去对应角色。</span>
        </div>
        <a-table :columns="expiringColumns" :data-source="expiring.rows.value" :row-key="(r: ExpiringAssignment) => `${r.userId}:${r.roleId}:${r.departmentId}`" size="small"
                 :loading="expiring.loading.value" :scroll="{ x: 1000 }"
                 :pagination="{ size: 'small', defaultPageSize: 20, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'user'">
              <div class="cell-main">{{ record.userName }}</div>
              <div class="cell-sub"><code>{{ record.externalId }}</code></div>
            </template>
            <template v-else-if="column.key === 'role'">{{ record.roleName }} <code>{{ record.roleCode }}</code></template>
            <template v-else-if="column.key === 'expiresAt'">
              <a-tag :color="expiringSoon(record.expiresAt) ? 'orange' : 'blue'">{{ fmt(record.expiresAt) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'granted'">
              <template v-if="record.grantedBy">{{ record.grantedBy }} · {{ fmt(record.grantedAt) }}</template>
              <span v-else class="muted">—</span>
            </template>
          </template>
          <template #emptyText><a-empty description="该时间范围内没有即将到期的授权" :image-style="{ height: '40px' }" /></template>
        </a-table>
      </a-card>
    </template>

    <BatchRolesModal v-model:open="bulkOpen" :department-id="scope.departmentId" :application-id="scope.applicationId" :user-ids="selected" @changed="reload" />

    <a-modal v-model:open="open" :title="`个别调整：${target?.name ?? ''}`" :confirm-loading="grantForm?.saving" ok-text="保存" width="640px" destroy-on-close @ok="save">
      <UserRolesForm v-if="target" ref="grantForm" :key="target.id" :user-id="target.id" :department-id="scope.departmentId" :application-id="scope.applicationId"
                     :roles="roles.rows.value" :assigned="target.roleIds ?? []" :expiries="target.roleExpiries ?? {}" :disabled="!scope.canAssign" />
    </a-modal>
  </div>
</template>
