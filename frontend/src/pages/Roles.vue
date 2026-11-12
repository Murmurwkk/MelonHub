<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { CopyOutlined, DownOutlined, ExportOutlined, ImportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, get, send } from '../api'
import { attempt, useList } from '../composables'
import { fmt } from '../labels'
import { downloadCsv } from '../csv'
import { useHub } from '../store'
import { useAuthorizationScope } from '../authorizationScope'
import type { ManifestImportResult, Permission, PermissionManifest, Role } from '../types'
import PageHead from '../components/PageHead.vue'
import DomainEmpty from '../components/DomainEmpty.vue'
import ReadonlyHint from '../components/ReadonlyHint.vue'
import RoleMembersDrawer from '../components/RoleMembersDrawer.vue'
import PermissionEditor from '../components/PermissionEditor.vue'

const scope = useAuthorizationScope()
const props = defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ navigate: [tab: string] }>()
const hub = useHub()
const router = useRouter()
function navigate(tab: string) { if (props.embedded) emit('navigate', tab); else router.push('/' + tab) }
const roles = useList<Role>(() => get<Role[]>('/admin/roles', scope.domainQuery))
const permissions = useList<Permission>(() => get<Permission[]>('/admin/permissions', scope.domainQuery))
function reload() { roles.load(); permissions.load() }
watch(() => scope.domainKey, key => { if (key) reload(); else { roles.clear(); permissions.clear() } }, { immediate: true })

const keyword = ref(''), status = ref<string>()
const rows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return roles.rows.value.filter(r => (!k || r.name.toLowerCase().includes(k) || r.code.toLowerCase().includes(k))
    && (!status.value || String(r.enabled) === status.value))
})
const columns: TableColumnsType<Role> = [
  { title: '角色名称', key: 'name', ellipsis: true },
  { title: '编码', dataIndex: 'code', key: 'code', width: 180, ellipsis: true },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '权限数', key: 'permissionCount', width: 80, align: 'right' },
  { title: '成员数', dataIndex: 'memberCount', width: 80, align: 'right' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 165 },
  { title: '操作', key: 'actions', width: 200, fixed: 'right' },
]

const open = ref(false), saving = ref(false), editing = ref<Role | null>(null), formRef = ref<FormInstance>()
const form = reactive({ name: '', code: '', description: '' })
const rules = {
  name: [{ required: true, whitespace: true, max: 100, message: '请输入角色名称（最多 100 字）' }],
  code: [{ required: true, pattern: /^[a-zA-Z0-9_.:-]{1,100}$/, message: '1-100 位字母、数字或 _ . : -' }],
}
function edit(row?: Role) {
  editing.value = row ?? null
  Object.assign(form, { name: row?.name ?? '', code: row?.code ?? '', description: row?.description ?? '' })
  open.value = true
}
async function save() {
  await formRef.value?.validate()
  const body = { name: form.name.trim(), code: form.code.trim(), description: form.description.trim() }
  let createdId = 0
  const ok = await attempt(saving, async () => {
    if (editing.value) await send('PUT', `/admin/roles/${editing.value.id}`, body, scope.domainQuery)
    else createdId = (await send<{ id: number }>('POST', '/admin/roles', body, scope.domainQuery)).id
  }, editing.value ? '角色已更新' : '角色已创建')
  if (!ok) return
  open.value = false
  roles.load()
  // New role: continue straight to granting permissions, so 权限 → 角色 → 成员 can be done in one pass.
  if (!editing.value) {
    grant(roles.rows.value.find(r => r.id === createdId)
      ?? { id: createdId, name: body.name, code: body.code, description: body.description, enabled: true, createdAt: '', memberCount: 0, permissionIds: [] })
  }
}

const grantOpen = ref(false), grantRole = ref<Role | null>(null), grantKeys = ref<string[]>([]), granting = ref(false)
const permissionOpen = ref(false)
async function permissionCreated(id: number) { await permissions.load(); grantKeys.value.push(String(id)) }
const transferData = computed(() => permissions.rows.value.map(p => ({ key: String(p.id), title: p.code, description: p.name })))
function grant(row: Role) { grantRole.value = row; grantKeys.value = row.permissionIds.map(String); grantOpen.value = true }
async function saveGrant(assignMembers = false) {
  const ok = await attempt(granting, () => send('PUT', `/admin/roles/${grantRole.value!.id}/permissions`, { ids: grantKeys.value.map(Number) }, scope.domainQuery), '角色权限已更新')
  if (!ok) return
  grantOpen.value = false
  reload()
  if (assignMembers) showMembers(grantRole.value!)
}
const filterPermission = (input: string, item: { title: string; description: string }) =>
  item.title.toLowerCase().includes(input.toLowerCase()) || item.description.toLowerCase().includes(input.toLowerCase())

function toggle(row: Role) {
  const enabling = !row.enabled
  Modal.confirm({
    title: `${enabling ? '启用' : '停用'}角色“${row.name}”？`,
    content: enabling ? '启用后，已分配该角色的用户在下一次权限校验时重新获得其权限。' : '停用后，所有拥有该角色的用户在下一次权限校验时失去其权限。角色分配本身保留。',
    okText: enabling ? '启用' : '停用', okType: enabling ? 'primary' : 'danger', cancelText: '取消',
    async onOk() {
      try { await send('PATCH', `/admin/roles/${row.id}/status`, { enabled: enabling }, scope.domainQuery); message.success(enabling ? '角色已启用' : '角色已停用'); roles.load() }
      catch (e) { message.error(errorText(e)) }
    },
  })
}
function remove(row: Role) {
  Modal.confirm({
    title: `删除角色“${row.name}”？`, content: `会同时解除 ${row.memberCount} 个用户的该角色分配和全部权限关联，无法恢复。`,
    okText: '删除', okType: 'danger', cancelText: '取消',
    async onOk() {
      try { await send('DELETE', `/admin/roles/${row.id}`, undefined, scope.domainQuery); message.success('角色已删除'); reload() }
      catch (e) { message.error(errorText(e)) }
    },
  })
}
function more(row: Role, key: string) {
  if (key === 'edit') edit(row)
  else if (key === 'toggle') toggle(row)
  else if (key === 'delete') remove(row)
}

const membersOpen = ref(false), membersRole = ref<Role>()
function showMembers(row: Role) { membersRole.value = row; membersOpen.value = true }
function exportRows() {
  const codes = new Map(permissions.rows.value.map(p => [p.id, p.code]))
  downloadCsv('roles', ['部门', '软件', '角色名称', '编码', '描述', '状态', '权限', '成员数'],
    rows.value.map(r => [scope.department?.name, scope.application?.name, r.name, r.code, r.description, r.enabled ? '启用' : '停用', r.permissionIds.map(id => codes.get(id)).join('、'), r.memberCount]))
}

const exporting = ref(false)
async function exportManifest(allDepartments: boolean) {
  exporting.value = true
  try {
    const department = scope.department
    const query = allDepartments ? { applicationId: scope.applicationId } : { applicationId: scope.applicationId, departmentId: scope.departmentId }
    const manifest = await get<PermissionManifest>('/admin/manifest', query)
    const blob = new Blob([JSON.stringify(manifest, null, 2)], { type: 'application/json;charset=utf-8' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    const filenameScope = allDepartments ? 'all' : department?.code ?? String(scope.departmentId)
    link.download = `manifest-${manifest.application.code}-${filenameScope}-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    URL.revokeObjectURL(link.href)
    message.success(allDepartments ? `已导出 ${manifest.departments.length} 个部门的清单` : `已导出「${department?.name ?? ''}」的清单`)
  } catch (e) {
    message.error(errorText(e))
  } finally {
    exporting.value = false
  }
}
const importOpen = ref(false), importing = ref(false), importText = ref('')
function openImport() { importText.value = ''; importOpen.value = true }
async function importManifest() {
  let payload: unknown
  try {
    payload = JSON.parse(importText.value)
  } catch {
    message.error('JSON 格式不正确，请检查后重试')
    return
  }
  // Assigned inside the callback, which control-flow narrowing cannot see; the cast keeps the declared union.
  let result = null as ManifestImportResult | null
  const ok = await attempt(importing, async () => {
    result = await send<ManifestImportResult>('POST', '/admin/manifest/import', payload, { applicationId: scope.applicationId })
  })
  if (!ok || !result) return
  importOpen.value = false
  Modal.success({
    title: '权限清单已导入',
    content: `新建权限 ${result.permissionsCreated} 个、更新权限 ${result.permissionsUpdated} 个；新建角色 ${result.rolesCreated} 个、更新角色 ${result.rolesUpdated} 个。同编码按编码合并，不会删除任何已有配置。`,
  })
  reload()
}

const copyOpen = ref(false), copySource = ref<number>(), copying = ref(false)
const copySources = computed(() => hub.departmentOptions.filter(o => o.value !== scope.departmentId))
function openCopy() { copySource.value = undefined; copyOpen.value = true }
function headAction(key: string) {
  if (key === 'export') exportManifest(false)
  else if (key === 'export-all') exportManifest(true)
  else if (key === 'import') openImport()
  else if (key === 'copy') openCopy()
}
async function copy() {
  if (!copySource.value) { message.warning('请选择源部门'); return }
  let result: Record<string, number> = {}
  const ok = await attempt(copying, async () => {
    result = await send<Record<string, number>>('POST', '/admin/domains/copy', { applicationId: scope.applicationId, sourceDepartmentId: copySource.value, targetDepartmentId: scope.departmentId })
  })
  if (!ok) return
  copyOpen.value = false
  Modal.success({
    title: '授权配置已复制',
    content: `新建权限 ${result.permissionsCreated} 个、角色 ${result.rolesCreated} 个；同编码已存在而跳过权限 ${result.permissionsSkipped} 个、角色 ${result.rolesSkipped} 个。`,
  })
  reload()
}
</script>

<template>
  <div class="page">
    <PageHead :title="embedded ? undefined : '角色'" description="选择角色即可配置权限或维护成员；多人、多软件授权使用「成员授权」。">
      <a-button @click="navigate('assignments')">成员授权</a-button>
      <a-button :disabled="!scope.hasDomain" @click="exportRows"><ExportOutlined />导出列表</a-button>
      <a-button :disabled="!scope.hasDomain" @click="reload"><ReloadOutlined />刷新</a-button>
      <a-dropdown :disabled="!scope.hasDomain" :trigger="['click']" placement="bottomRight">
        <a-button :loading="exporting">清单与复制 <DownOutlined /></a-button>
        <template #overlay>
          <a-menu @click="(e: { key: string }) => headAction(e.key)">
            <a-menu-item key="export"><ExportOutlined /> 导出本部门清单</a-menu-item>
            <a-menu-item key="export-all"><ExportOutlined /> 导出全部部门清单</a-menu-item>
            <a-menu-item v-if="scope.canTransfer" key="import"><ImportOutlined /> 导入清单</a-menu-item>
            <a-menu-item v-if="scope.canTransfer" key="copy" :disabled="copySources.length === 0"><CopyOutlined /> 从其他部门复制</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-button v-if="scope.canConfigure" type="primary" @click="edit()"><PlusOutlined />新建角色</a-button>
    </PageHead>
    <DomainEmpty v-if="!scope.hasDomain" />
    <template v-else>
      <ReadonlyHint :editable="scope.canConfigure" />
      <a-card :bordered="false">
        <div class="toolbar">
          <a-input-search v-model:value="keyword" placeholder="搜索名称或编码" allow-clear class="toolbar-search" />
          <a-select v-model:value="status" placeholder="全部状态" allow-clear class="toolbar-select-sm" :options="[{ value: 'true', label: '启用' }, { value: 'false', label: '停用' }]" />
        </div>
        <a-table :columns="columns" :data-source="rows" row-key="id" size="small" :loading="roles.loading.value" :scroll="{ x: 1000 }"
                 :pagination="{ size: 'small', defaultPageSize: 20, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
          <template #emptyText>
            <a-empty :image-style="{ height: '60px' }"
                     :description="keyword || status ? '没有匹配的角色' : '还没有角色。新建后可直接添加权限，再分配给部门成员。'">
              <a-space v-if="!keyword && !status">
                <a-button v-if="scope.canConfigure" type="primary" @click="edit()">新建角色</a-button>
              </a-space>
            </a-empty>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <div class="cell-main">{{ record.name }}</div>
              <div v-if="record.description" class="cell-sub">{{ record.description }}</div>
            </template>
            <template v-else-if="column.key === 'code'"><code>{{ record.code }}</code></template>
            <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
            <template v-else-if="column.key === 'permissionCount'">{{ record.permissionIds.length }}</template>
            <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
            <template v-else-if="column.key === 'actions'">
              <a-space :size="4">
                <a @click="grant(record)">{{ scope.canConfigure ? '配置权限' : '查看权限' }}</a>
                <a-divider type="vertical" />
                <a @click="showMembers(record)">成员</a>
                <template v-if="scope.canConfigure">
                  <a-divider type="vertical" />
                  <a-dropdown>
                    <a>更多 <DownOutlined /></a>
                    <template #overlay>
                      <a-menu @click="(e: { key: string }) => more(record, e.key)">
                        <a-menu-item key="edit">编辑</a-menu-item>
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
    </template>

    <a-modal v-model:open="open" :title="editing ? '编辑角色' : '新建角色'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-form-item label="角色名称" name="name"><a-input v-model:value="form.name" :maxlength="100" placeholder="例如：项目经理" /></a-form-item>
        <a-form-item label="编码" name="code" extra="同一部门、同一软件内唯一，区分大小写"><a-input v-model:value="form.code" :maxlength="100" placeholder="例如：project-manager" /></a-form-item>
        <a-form-item label="描述" name="description"><a-textarea v-model:value="form.description" :maxlength="500" :rows="3" show-count /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="grantOpen" :title="`角色权限：${grantRole?.name ?? ''}`" width="820px" :confirm-loading="granting" ok-text="保存" destroy-on-close
             :ok-button-props="{ disabled: !scope.canConfigure }">
      <template #footer>
        <a-button @click="grantOpen = false">关闭</a-button>
        <template v-if="scope.canConfigure">
          <a-button :loading="granting" @click="saveGrant()">保存</a-button>
          <a-button type="primary" :loading="granting" @click="saveGrant(true)">保存并分配成员</a-button>
        </template>
      </template>
      <p class="muted">保存后立即生效：拥有该角色的用户在下一次权限校验时按新的权限集合判定。</p>
      <a-space class="block-gap">
        <a-button v-if="scope.canConfigure" size="small" @click="permissionOpen = true">新建权限并勾选</a-button>
        <a-button size="small" :disabled="!scope.canConfigure" @click="grantKeys = transferData.map(p => p.key)">全选权限</a-button>
        <a-button size="small" :disabled="!scope.canConfigure" @click="grantKeys = []">清空权限</a-button>
      </a-space>
      <a-transfer v-model:target-keys="grantKeys" :data-source="transferData" :titles="['可选权限', '已授予']" show-search :filter-option="filterPermission"
                  :disabled="!scope.canConfigure" :list-style="{ width: '360px', height: '400px' }" :locale="{ searchPlaceholder: '搜索编码或名称', itemUnit: '项', itemsUnit: '项', notFoundContent: '暂无权限' }">
        <template #render="item"><code>{{ item.title }}</code> <span class="muted">{{ item.description }}</span></template>
      </a-transfer>
    </a-modal>

    <PermissionEditor v-model:open="permissionOpen" @saved="permissionCreated" />

    <a-modal v-model:open="copyOpen" title="从其他部门复制授权配置" :confirm-loading="copying" ok-text="复制" destroy-on-close @ok="copy">
      <p class="hint">复制到 {{ scope.department?.name }} × {{ scope.application?.name }}。同编码的权限复用、角色跳过，不复制用户的角色分配。</p>
      <a-form layout="vertical">
        <a-form-item label="源部门" required>
          <a-select v-model:value="copySource" :options="copySources" placeholder="选择源部门" show-search option-filter-prop="label" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="importOpen" title="导入权限清单" :confirm-loading="importing" ok-text="导入" width="720px" destroy-on-close :mask-closable="false" @ok="importManifest">
      <p class="hint">导入到 {{ scope.application?.name }}。粘贴导出的清单 JSON，按编码创建或更新权限与角色，清单里没有的配置保持不变。</p>
      <a-textarea v-model:value="importText" :rows="12" placeholder='{"version":1,"application":{"code":"oa","name":"OA"},"departments":[{"code":"rd","name":"研发","permissions":[],"roles":[]}]}' class="import-json" />
    </a-modal>

    <RoleMembersDrawer v-model:open="membersOpen" :role="membersRole" :department-id="scope.departmentId" :application-id="scope.applicationId" :embedded="embedded" @changed="roles.load()" @navigate="navigate" />
  </div>
</template>
