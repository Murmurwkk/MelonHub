<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { DownOutlined, ExportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { downloadCsv } from '../csv'
import { errorText, send } from '../api'
import { attempt } from '../composables'
import { fmt } from '../labels'
import { useHub } from '../store'
import type { Application } from '../types'
import PageHead from '../components/PageHead.vue'
import KeysDrawer from '../components/KeysDrawer.vue'
import SecretModal from '../components/SecretModal.vue'
import WebhooksDrawer from '../components/WebhooksDrawer.vue'
import AuthorizationDrawer from '../components/AuthorizationDrawer.vue'

const hub = useHub(), router = useRouter(), route = useRoute()
const keyword = ref(''), status = ref<string>(), loading = ref(false)
watch(() => route.query.keyword, value => { keyword.value = typeof value === 'string' ? value : '' }, { immediate: true })
const rows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return hub.applications.filter(a => (!status.value || String(a.enabled) === status.value)
    && (!k || [a.name, a.code, a.owner, a.description].some(v => (v || '').toLowerCase().includes(k))))
})
const columns: TableColumnsType<Application> = [
  { title: '软件名称', key: 'name', ellipsis: true },
  { title: '编码', dataIndex: 'code', key: 'code', width: 140, ellipsis: true },
  { title: '负责人', dataIndex: 'owner', key: 'owner', width: 100, ellipsis: true },
  { title: '状态', key: 'enabled', width: 72 },
  { title: '角色', dataIndex: 'roleCount', width: 60, align: 'right' },
  { title: '权限', dataIndex: 'permissionCount', width: 60, align: 'right' },
  { title: '有效密钥', dataIndex: 'activeKeyCount', key: 'keys', width: 84, align: 'right' },
  { title: '限流', key: 'rateLimit', width: 80, align: 'right' },
  { title: '接入日期', dataIndex: 'createdAt', key: 'createdAt', width: 104 },
  { title: '操作', key: 'actions', width: 300, fixed: 'right' },
]

async function reload() {
  loading.value = true
  try { await hub.loadLists() } catch (e) { message.error(errorText(e)) } finally { loading.value = false }
}

const open = ref(false), saving = ref(false), editing = ref<Application | null>(null), formRef = ref<FormInstance>()
const form = reactive({ name: '', code: '', owner: '', description: '' })
const rules = {
  name: [{ required: true, whitespace: true, max: 100, message: '请输入软件名称（最多 100 字）' }],
  code: [{ required: true, pattern: /^[a-zA-Z0-9_.-]{1,64}$/, message: '1-64 位字母、数字或 _ . -' }],
}
function edit(row?: Application) {
  editing.value = row ?? null
  Object.assign(form, { name: row?.name ?? '', code: row?.code ?? '', owner: row?.owner ?? '', description: row?.description ?? '' })
  open.value = true
}
const secretOpen = ref(false), secret = ref(''), configureAfterSecret = ref(false)
watch(secretOpen, value => {
  if (value) return
  secret.value = ''
  if (configureAfterSecret.value) { configureAfterSecret.value = false; configurationOpen.value = true }
})
async function save() {
  await formRef.value?.validate()
  const body = { name: form.name.trim(), code: form.code.trim(), owner: form.owner.trim(), description: form.description.trim() }
  let key = '', createdId = 0
  const ok = await attempt(saving, async () => {
    if (editing.value) await send('PUT', `/admin/applications/${editing.value.id}`, body)
    else {
      const result = await send<{ id: number; apiKey: string }>('POST', '/admin/applications', body)
      key = result.apiKey
      createdId = result.id
    }
  }, editing.value ? '软件信息已更新' : '软件已接入')
  if (!ok) return
  open.value = false
  await reload()
  if (createdId) {
    configurationId.value = createdId
    configurationDepartment.value = undefined
    configureAfterSecret.value = true
    secret.value = key
    secretOpen.value = true
  }
}

function toggle(row: Application) {
  const enabling = !row.enabled
  Modal.confirm({
    title: `${enabling ? '启用' : '停用'}软件“${row.name}”？`,
    content: enabling ? '启用后该软件的有效 API Key 恢复可用。' : '停用后该软件的 API 调用全部被拒绝，所有用户在该软件中的权限上下文立即清除。',
    okText: enabling ? '启用' : '停用', okType: enabling ? 'primary' : 'danger', cancelText: '取消',
    async onOk() {
      try { await send('PATCH', `/admin/applications/${row.id}/status`, { enabled: enabling }); message.success(enabling ? '软件已启用' : '软件已停用'); await reload() }
      catch (e) { message.error(errorText(e)) }
    },
  })
}

const deleteTarget = ref<Application | null>(null), deleteInput = ref(''), deleting = ref(false)
const deleteOpen = computed({ get: () => !!deleteTarget.value, set: v => { if (!v) deleteTarget.value = null } })
function askDelete(row: Application) { deleteTarget.value = row; deleteInput.value = '' }
async function confirmDelete() {
  const target = deleteTarget.value!
  const ok = await attempt(deleting, () => send('DELETE', `/admin/applications/${target.id}`), '软件已删除')
  if (ok) { deleteTarget.value = null; await reload() }
}

const keysOpen = ref(false), keysApp = ref<Application>()
function manageKeys(row: Application) { keysApp.value = row; keysOpen.value = true }
const hooksOpen = ref(false), hooksApp = ref<Application>()
function manageHooks(row: Application) { hooksApp.value = row; hooksOpen.value = true }

const limitOpen = ref(false), limitTarget = ref<Application | null>(null), limitSaving = ref(false), limitValue = ref<number | null>(null)
function editLimit(row: Application) { limitTarget.value = row; limitValue.value = row.rateLimit; limitOpen.value = true }
async function saveLimit() {
  const ok = await attempt(limitSaving, () => send('PUT', `/admin/applications/${limitTarget.value!.id}/rate-limit`, { perSecond: limitValue.value }), '软件限流已更新')
  if (ok) { limitOpen.value = false; await reload() }
}

const configurationOpen = ref(false), configurationId = ref<number>(), configurationDepartment = ref<number>()
function configure(row: Application) { configurationId.value = row.id; configurationDepartment.value = undefined; configurationOpen.value = true }
watch(() => [route.query.configure, route.query.departmentId, hub.applications.length], () => {
  const row = hub.applications.find(a => a.id === Number(route.query.configure))
  if (row) { configure(row); configurationDepartment.value = Number(route.query.departmentId) || undefined }
}, { immediate: true })
watch(configurationOpen, value => {
  if (!value && route.query.configure) router.replace({ query: { ...route.query, configure: undefined, departmentId: undefined } })
})
function more(row: Application, key: string) {
  if (key === 'edit') edit(row)
  else if (key === 'toggle') toggle(row)
  else if (key === 'limit') editLimit(row)
  else if (key === 'delete') askDelete(row)
}
function exportRows() {
  downloadCsv('applications', ['ID', '软件名称', '编码', '负责人', '描述', '状态', '角色数', '权限数'],
    rows.value.map(a => [a.id, a.name, a.code, a.owner, a.description, a.enabled ? '启用' : '停用', a.roleCount, a.permissionCount]))
}
</script>

<template>
  <div class="page">
    <PageHead title="软件接入" description="选中软件，在配置抽屉中维护部门的角色与权限；配好一处后可复制到多个部门。">
      <a-button @click="exportRows"><ExportOutlined />导出列表</a-button>
      <a-button :loading="loading" @click="reload"><ReloadOutlined />刷新</a-button>
      <a-button v-if="hub.can('application.manage')" type="primary" @click="edit()"><PlusOutlined />接入软件</a-button>
    </PageHead>
    <a-card :bordered="false">
      <div class="toolbar">
        <a-input-search v-model:value="keyword" placeholder="名称、编码、负责人或描述" allow-clear class="toolbar-search" />
        <a-select v-model:value="status" placeholder="全部状态" allow-clear class="toolbar-select-sm" :options="[{ value: 'true', label: '启用' }, { value: 'false', label: '停用' }]" />
        <span class="muted">共 {{ rows.length }} 个软件<template v-if="!hub.me?.allApplications">（仅显示授权给你的软件）</template></span>
      </div>
      <a-table :columns="columns" :data-source="rows" row-key="id" size="small" :loading="loading" :scroll="{ x: 1140 }"
               :pagination="{ size: 'small', defaultPageSize: 20, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <div class="cell-main"><a @click="configure(record)">{{ record.name }}</a></div>
            <div v-if="record.description" class="cell-sub">{{ record.description }}</div>
          </template>
          <template v-else-if="column.key === 'code'"><code>{{ record.code }}</code></template>
          <template v-else-if="column.key === 'owner'">{{ record.owner || '—' }}</template>
          <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
          <template v-else-if="column.key === 'keys'"><span :class="{ 'warn-text': !record.activeKeyCount }">{{ record.activeKeyCount }}</span></template>
          <template v-else-if="column.key === 'rateLimit'">
            <span v-if="record.rateLimit === null" class="muted">默认</span>
            <span v-else-if="record.rateLimit === 0">不限</span>
            <span v-else>{{ record.rateLimit }}/秒</span>
          </template>
          <template v-else-if="column.key === 'createdAt'"><a-tooltip :title="fmt(record.createdAt)">{{ record.createdAt?.slice(0, 10) }}</a-tooltip></template>
          <template v-else-if="column.key === 'actions'">
            <a-space :size="4">
              <a @click="configure(record)">部门与授权</a>
              <a-divider type="vertical" />
              <a @click="manageKeys(record)">API Key</a>
              <a-divider type="vertical" />
              <a @click="manageHooks(record)">Webhook</a>
              <template v-if="hub.can('application.manage')">
                <a-divider type="vertical" />
                <a-dropdown>
                  <a>更多 <DownOutlined /></a>
                  <template #overlay>
                    <a-menu @click="(e: { key: string }) => more(record, e.key)">
                      <a-menu-item key="edit">编辑</a-menu-item>
                      <a-menu-item key="limit">限流</a-menu-item>
                      <a-menu-item key="toggle">{{ record.enabled ? '停用' : '启用' }}</a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="delete" danger>删除</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </template>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="editing ? '编辑软件' : '接入软件'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" width="560px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12"><a-form-item label="软件名称" name="name"><a-input v-model:value="form.name" :maxlength="100" /></a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="编码" name="code"><a-input v-model:value="form.code" :maxlength="64" placeholder="例如：oa" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="负责人" name="owner"><a-input v-model:value="form.owner" :maxlength="100" placeholder="团队或联系人" /></a-form-item>
        <a-form-item label="描述" name="description"><a-textarea v-model:value="form.description" :maxlength="500" :rows="3" show-count /></a-form-item>
        <p v-if="!editing" class="hint">创建后保存 API Key，即可在配置抽屉中设置角色与权限，并复制给多个部门。</p>
      </a-form>
    </a-modal>

    <a-modal v-model:open="deleteOpen" title="删除软件" ok-text="删除" :ok-button-props="{ danger: true, disabled: deleteInput !== deleteTarget?.code }" :confirm-loading="deleting" destroy-on-close @ok="confirmDelete">
      <a-alert type="error" show-icon class="block-gap" message="删除无法恢复" description="会删除该软件的全部 API Key、管理员授权和权限上下文。仍有角色或权限的软件无法删除，请先清理或改为停用。" />
      <p>请输入软件编码 <code>{{ deleteTarget?.code }}</code> 确认：</p>
      <a-input v-model:value="deleteInput" autocomplete="off" />
    </a-modal>

    <a-modal v-model:open="limitOpen" :title="`软件限流：${limitTarget?.name ?? ''}`" :confirm-loading="limitSaving" ok-text="保存" destroy-on-close @ok="saveLimit">
      <a-form layout="vertical">
        <a-form-item label="每秒请求数" extra="留空使用平台配置的默认值，0 表示不限制。">
          <a-input-number v-model:value="limitValue" :min="0" :max="100000" :precision="0" placeholder="平台默认" class="grow" />
        </a-form-item>
      </a-form>
    </a-modal>

    <KeysDrawer v-model:open="keysOpen" :application="keysApp" @changed="reload" />
    <WebhooksDrawer v-model:open="hooksOpen" :application="hooksApp" />
    <AuthorizationDrawer v-model:open="configurationOpen" :application-id="configurationId" :department-id="configurationDepartment" initial-tab="roles" />
    <SecretModal v-model:open="secretOpen" title="软件已接入：默认 API Key" :secret="secret" />
  </div>
</template>
