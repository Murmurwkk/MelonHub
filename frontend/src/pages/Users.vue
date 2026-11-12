<script setup lang="ts">
import { computed, defineAsyncComponent, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { DownOutlined, ExportOutlined, ImportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { downloadCsv } from '../csv'
import { errorText, get, send, type PageResult } from '../api'
import { attempt, usePaged } from '../composables'
import { fmt } from '../labels'
import { useHub } from '../store'
import type { User, UserImportResult } from '../types'
import PageHead from '../components/PageHead.vue'
import UserAccessDrawer from '../components/UserAccessDrawer.vue'
import UserRolesDrawer from '../components/UserRolesDrawer.vue'
import UserPicker from '../components/UserPicker.vue'
import GuardedButton from '../components/GuardedButton.vue'
import GuardedLink from '../components/GuardedLink.vue'

const hub = useHub()
const props = defineProps<{ embedded?: boolean; departmentId?: number }>()
const emit = defineEmits<{ navigate: [tab: string] }>()
const AuthorizationDrawer = defineAsyncComponent(() => import('../components/AuthorizationDrawer.vue'))
const configurationOpen = ref(false)
const filters = reactive<{ keyword: string; departmentId?: number; enabled?: 'true' | 'false' }>({ keyword: '' })
const table = usePaged<User>((page, size) => get<PageResult<User>>('/admin/users', { ...filters, keyword: filters.keyword.trim(), page, size }))
const route = useRoute()
// Global search links here with ?keyword=; following one while already on this page must filter again.
watch(() => [props.embedded ? undefined : route.query.keyword, props.departmentId ?? route.query.departmentId], ([keyword, department]) => {
  filters.keyword = typeof keyword === 'string' ? keyword : ''
  filters.departmentId = department ? Number(department) : undefined
  table.load(true)
}, { immediate: true })

const columns: TableColumnsType<User> = [
  { title: '姓名', dataIndex: 'name', width: 120, ellipsis: true },
  { title: '外部标识', dataIndex: 'externalId', key: 'externalId', width: 150, ellipsis: true },
  { title: '邮箱', dataIndex: 'email', key: 'email', width: 180, ellipsis: true },
  { title: '所属部门', key: 'departments' },
  { title: '当前部门', key: 'active', width: 120, ellipsis: true },
  { title: '状态', key: 'enabled', width: 72 },
  { title: '创建日期', dataIndex: 'createdAt', key: 'createdAt', width: 104 },
  { title: '操作', key: 'actions', width: 250, fixed: 'right' },
]

const open = ref(false), saving = ref(false), editing = ref<User | null>(null), formRef = ref<FormInstance>()
const existingOpen = ref(false), existingId = ref<number>(), existingUser = ref<User>()
function chooseExisting() { existingId.value = undefined; existingUser.value = undefined; existingOpen.value = true }
function joinDepartment() {
  const user = existingUser.value!
  existingOpen.value = false
  edit(user)
  if (!form.departmentIds.includes(props.departmentId!)) form.departmentIds.push(props.departmentId!)
}
const form = reactive<{ name: string; externalId: string; email: string; departmentIds: number[] }>({ name: '', externalId: '', email: '', departmentIds: [] })
const identityChanged = computed(() => !!editing.value && form.externalId.trim() !== editing.value.externalId)
const removedDepartments = computed(() => editing.value?.departmentIds.filter(id => !form.departmentIds.includes(id)) ?? [])
const rules = {
  name: [{ required: true, whitespace: true, max: 100, message: '请输入姓名（最多 100 字）' }],
  externalId: [{ required: true, whitespace: true, max: 100, message: '请输入外部标识（最多 100 字）' }],
  email: [{ type: 'email', max: 150, message: '邮箱格式不正确' }],
}
function edit(row?: User) {
  editing.value = row ?? null
  Object.assign(form, { name: row?.name ?? '', externalId: row?.externalId ?? '', email: row?.email ?? '', departmentIds: [...(row?.departmentIds ?? (filters.departmentId ? [filters.departmentId] : []))] })
  open.value = true
}
async function save() {
  await formRef.value?.validate()
  const body = { name: form.name.trim(), externalId: form.externalId.trim(), email: form.email.trim(), departmentIds: form.departmentIds }
  const ok = await attempt(saving, () => editing.value ? send('PUT', `/admin/users/${editing.value.id}`, body) : send('POST', '/admin/users', body),
    editing.value ? '用户已更新' : '用户已创建')
  if (ok) { open.value = false; table.load(); hub.loadLists().catch(() => {}) }
}

function toggle(row: User) {
  const enabling = !row.enabled
  Modal.confirm({
    title: `${enabling ? '启用' : '停用'}用户“${row.name}”？`,
    content: enabling ? '启用后业务系统可以重新为该用户创建权限上下文。' : '停用后该用户的全部权限上下文立即失效，业务系统无法再为其创建上下文。',
    okText: enabling ? '启用' : '停用', okType: enabling ? 'primary' : 'danger', cancelText: '取消',
    async onOk() {
      try { await send('PATCH', `/admin/users/${row.id}/status`, { enabled: enabling }); message.success(enabling ? '用户已启用' : '用户已停用'); table.load() }
      catch (e) { message.error(errorText(e)) }
    },
  })
}
function revoke(row: User) {
  Modal.confirm({
    title: `强制“${row.name}”下线？`, content: '该用户在所有软件中的权限上下文立即失效，需要业务系统重新创建。',
    okText: '强制下线', okType: 'danger', cancelText: '取消',
    async onOk() {
      try { const r = await send<{ revoked: number }>('DELETE', `/admin/users/${row.id}/contexts`); message.success(`已注销 ${r.revoked} 个权限上下文`) }
      catch (e) { message.error(errorText(e)) }
    },
  })
}
function remove(row: User) {
  Modal.confirm({
    title: `删除用户“${row.name}”？`, content: '会同时删除该用户的部门归属、全部角色分配和权限上下文，无法恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    async onOk() {
      try { await send('DELETE', `/admin/users/${row.id}`); message.success('用户已删除'); table.load(); hub.loadLists().catch(() => {}) }
      catch (e) { message.error(errorText(e)) }
    },
  })
}
function more(row: User, key: string) {
  if (key === 'toggle') toggle(row)
  else if (key === 'revoke') revoke(row)
  else if (key === 'delete') remove(row)
}

const accessOpen = ref(false), accessUser = ref<number>()
function access(row: User) { accessUser.value = row.id; accessOpen.value = true }

// Granting roles needs the assignment function for some software; the drawer checks it again per selected software.
const canGrant = computed(() => hub.applications.some(a => hub.can('domain.assign', a.id)))
const grantOpen = ref(false), grantUser = ref<User>()
function grantRoles(row: User) { grantUser.value = row; grantOpen.value = true }

const exporting = ref(false)
async function exportUsers() {
  await attempt(exporting, async () => {
    const result = await get<PageResult<User>>('/admin/users/export', { ...filters, keyword: filters.keyword.trim() })
    downloadCsv('users', ['ID', '姓名', '外部标识', '邮箱', '所属部门', '当前部门', '状态'],
      result.items.map(u => [u.id, u.name, u.externalId, u.email, u.departmentIds.map(hub.departmentName).join('、'), hub.departmentName(u.activeDepartmentId), u.enabled ? '启用' : '停用']))
  })
}

const importOpen = ref(false), importing = ref(false), importText = ref(''), importResult = ref<UserImportResult | null>(null)
const importExample = JSON.stringify({ users: [{ name: '张三', externalId: 'zhangsan', email: 'zhangsan@example.com', departmentCodes: ['rd-center'] }] }, null, 2)
const importColumns: TableColumnsType = [
  { title: '行号', dataIndex: 'line', width: 70, align: 'right' },
  { title: '外部标识', dataIndex: 'externalId', key: 'externalId', width: 160, ellipsis: true },
  { title: '失败原因', dataIndex: 'error', ellipsis: true },
]
function openImport() { importText.value = ''; importResult.value = null; importOpen.value = true }
async function runImport() {
  let payload: unknown
  try {
    payload = JSON.parse(importText.value)
  } catch {
    message.error('JSON 格式不正确，请检查后重试')
    return
  }
  const ok = await attempt(importing, async () => {
    importResult.value = await send<UserImportResult>('POST', '/admin/users/import', payload)
  })
  if (ok) { table.load(); hub.loadLists().catch(() => {}) }
}
</script>

<template>
  <div class="page">
    <PageHead :title="embedded ? undefined : '用户'" description="维护用户资料和所属部门；选中用户可直接分配角色，多人授权使用「成员授权」。">
      <GuardedButton v-if="embedded" fn="domain.assign" :allowed="canGrant" @click="emit('navigate', 'assignments')">成员授权</GuardedButton>
      <GuardedButton v-else fn="domain.assign" :allowed="canGrant" :disabled="!filters.departmentId" @click="configurationOpen = true">为所选部门批量授权</GuardedButton>
      <a-button :loading="exporting" @click="exportUsers"><ExportOutlined />导出列表</a-button>
      <a-button :loading="table.loading.value" @click="table.load()"><ReloadOutlined />刷新</a-button>
      <GuardedButton v-if="embedded" fn="user.manage" @click="chooseExisting">添加已有用户</GuardedButton>
      <GuardedButton fn="user.manage" @click="openImport"><ImportOutlined />批量导入</GuardedButton>
      <GuardedButton fn="user.manage" type="primary" @click="edit()"><PlusOutlined />新建用户</GuardedButton>
    </PageHead>
    <a-card :bordered="false">
      <div class="toolbar">
        <a-input-search v-model:value="filters.keyword" placeholder="姓名、外部标识或邮箱" allow-clear class="toolbar-search" @search="table.load(true)" />
        <a-select v-if="!embedded" v-model:value="filters.departmentId" :options="hub.departmentOptions" placeholder="全部部门" allow-clear show-search option-filter-prop="label" class="toolbar-select" @change="table.load(true)" />
        <a-select v-model:value="filters.enabled" placeholder="全部状态" allow-clear class="toolbar-select-sm" :options="[{ value: 'true', label: '启用' }, { value: 'false', label: '停用' }]" @change="table.load(true)" />
      </div>
      <a-table :columns="columns" :data-source="table.rows.value" row-key="id" size="small" :loading="table.loading.value" :pagination="table.pagination.value" :scroll="{ x: 1160 }" @change="table.onChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'externalId'"><code>{{ record.externalId }}</code></template>
          <template v-else-if="column.key === 'email'">{{ record.email || '—' }}</template>
          <template v-else-if="column.key === 'departments'">
            <a-tag v-for="id in record.departmentIds" :key="id">{{ hub.departmentName(id) }}</a-tag>
            <span v-if="!record.departmentIds.length" class="muted">未加入部门</span>
          </template>
          <template v-else-if="column.key === 'active'">{{ hub.departmentName(record.activeDepartmentId) }}</template>
          <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
          <template v-else-if="column.key === 'createdAt'"><a-tooltip :title="fmt(record.createdAt)">{{ record.createdAt?.slice(0, 10) }}</a-tooltip></template>
          <template v-else-if="column.key === 'actions'">
            <a-space :size="4">
              <GuardedLink fn="domain.assign" :allowed="canGrant" @click="grantRoles(record)">分配角色</GuardedLink>
              <a-divider type="vertical" />
              <a @click="access(record)">授权详情</a>
              <a-divider type="vertical" />
              <GuardedLink fn="user.manage" @click="edit(record)">编辑</GuardedLink>
              <a-divider type="vertical" />
              <a-dropdown :disabled="!hub.can('user.manage')">
                <GuardedLink fn="user.manage">更多 <DownOutlined /></GuardedLink>
                <template #overlay>
                  <a-menu @click="(e: { key: string }) => more(record, e.key)">
                    <a-menu-item key="toggle">{{ record.enabled ? '停用' : '启用' }}</a-menu-item>
                    <a-menu-item key="revoke">强制下线</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="delete" danger>删除</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="editing ? '编辑用户' : '新建用户'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" width="560px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12"><a-form-item label="姓名" name="name"><a-input v-model:value="form.name" :maxlength="100" /></a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="外部标识" name="externalId"><a-input v-model:value="form.externalId" :maxlength="100" placeholder="与业务系统的用户 ID 一致" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="邮箱" name="email"><a-input v-model:value="form.email" :maxlength="150" /></a-form-item>
        <a-form-item label="所属部门" name="departmentIds">
          <a-select v-model:value="form.departmentIds" mode="multiple" :options="hub.departmentOptions" placeholder="选择一个或多个部门" option-filter-prop="label" />
        </a-form-item>
        <a-alert v-if="identityChanged" type="warning" show-icon message="修改外部标识会使该用户现有的全部权限上下文失效。" class="block-gap" />
        <a-alert v-if="removedDepartments.length" type="warning" show-icon class="block-gap"
                 :message="`移出部门：${removedDepartments.map(id => hub.departmentName(id)).join('、')}。该用户在这些部门的角色分配会被一并删除。`" />
      </a-form>
    </a-modal>

    <a-modal v-model:open="importOpen" title="批量导入用户" :confirm-loading="importing" ok-text="导入" width="720px" destroy-on-close :mask-closable="false" @ok="runImport">
      <p class="hint">按外部标识创建或更新用户，并按部门编码补齐成员关系，不会移除已有的成员关系。每次最多 500 行。</p>
      <a-textarea v-model:value="importText" :rows="10" :placeholder="importExample" class="import-json" />
      <template v-if="importResult">
        <a-alert :type="importResult.failed.length ? 'warning' : 'success'" show-icon class="block-gap import-result"
                 :message="`导入完成：新建 ${importResult.created} 个，更新 ${importResult.updated} 个，失败 ${importResult.failed.length} 个`" />
        <a-table v-if="importResult.failed.length" :columns="importColumns" :data-source="importResult.failed" row-key="line" size="small" :pagination="false" />
      </template>
    </a-modal>

    <UserAccessDrawer v-model:open="accessOpen" :user-id="accessUser" @changed="table.load()" />
    <UserRolesDrawer v-model:open="grantOpen" :user="grantUser" :department-id="filters.departmentId" @changed="table.load()" />
    <AuthorizationDrawer v-if="!embedded" v-model:open="configurationOpen" :department-id="filters.departmentId" />
    <a-modal v-model:open="existingOpen" title="添加已有用户到当前部门" ok-text="继续" :ok-button-props="{ disabled: !existingUser }" destroy-on-close @ok="joinDepartment">
      <p class="hint">选择用户后检查资料并保存，原有部门归属保持不变。</p>
      <UserPicker v-model:value="existingId" @pick="(user: User | undefined) => existingUser = user" style="width: 100%" />
    </a-modal>
  </div>
</template>
