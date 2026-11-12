<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { DownOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, get, send } from '../api'
import { attempt, newPasswordRules, useList } from '../composables'
import { fmt, roleColor } from '../labels'
import { useHub } from '../store'
import type { Account, AdminKind, AdminRoleDefinition } from '../types'
import PageHead from '../components/PageHead.vue'
import GuardedButton from '../components/GuardedButton.vue'
import GuardedLink from '../components/GuardedLink.vue'

const hub = useHub()
const list = useList<Account>(() => get<Account[]>('/admin/accounts'))
const roles = useList<AdminRoleDefinition>(() => get<AdminRoleDefinition[]>('/admin/admin-roles'))
const expanded = ref<string[]>([])
onMounted(async () => { await Promise.all([list.load(), roles.load()]); expanded.value = list.rows.value.map(a => a.username) })
const roleOptions = computed(() => roles.rows.value.map(r => ({ value: r.code, label: r.name })))
const roleName = (code: string) => roles.rows.value.find(r => r.code === code)?.name ?? code
const roleDescription = computed(() => roles.rows.value.find(r => r.code === form.role)?.description)
const kindLabels: Record<AdminKind, string> = { MANAGER: '管理员', DEPARTMENT: '部门管理员' }
const notManaged = '只能管理自己下级的管理员账号'

type AccountRow = Account & { children?: AccountRow[] }
/** The accounts as the administrator tree: each under the account that manages it; the operator's own account on top. */
const tree = computed(() => {
  const rows = new Map<string, AccountRow>(list.rows.value.map(a => [a.username, { ...a }]))
  const top: AccountRow[] = []
  for (const row of rows.values()) {
    const parent = row.parentUsername ? rows.get(row.parentUsername) : undefined
    if (parent) (parent.children ??= []).push(row)
    else top.push(row)
  }
  return top
})

const columns: TableColumnsType<AccountRow> = [
  { title: '账号', dataIndex: 'username', key: 'username', width: 230, ellipsis: true },
  { title: '显示名称', dataIndex: 'displayName', width: 120, ellipsis: true },
  { title: '角色', key: 'role', width: 120 },
  { title: '类型', key: 'kind', width: 100 },
  { title: '管理范围', key: 'scope' },
  { title: '两步验证', key: 'mfa', width: 90 },
  { title: '状态', key: 'status', width: 100 },
  { title: '最近登录', dataIndex: 'lastLoginAt', key: 'lastLoginAt', width: 165 },
  { title: '操作', key: 'actions', width: 210, fixed: 'right' },
]

const open = ref(false), saving = ref(false), editing = ref<Account | null>(null), formRef = ref<FormInstance>()
const form = reactive<{
  username: string; displayName: string; role: string; kind: AdminKind; parentUsername: string
  allDepartments: boolean; departmentIds: number[]; allApplications: boolean; applicationIds: number[]
  enabled: boolean; password: string; confirm: string
}>({
  username: '', displayName: '', role: 'ADMIN', kind: 'MANAGER', parentUsername: '', allDepartments: true, departmentIds: [],
  allApplications: true, applicationIds: [], enabled: true, password: '', confirm: '',
})
const self = computed(() => editing.value?.username === hub.me?.username)
/** Accounts a new or moved account can be placed under: the operator itself and the managers it manages. */
const parentOptions = computed(() => list.rows.value
  .filter(a => a.kind === 'MANAGER' && (a.username === hub.me?.username || a.manageable) && a.username !== editing.value?.username)
  .map(a => ({ value: a.username, label: `${a.displayName}（${a.username}）` })))
const parent = computed(() => list.rows.value.find(a => a.username === form.parentUsername))
/** A child stays within its parent: when the parent is limited, only the parent's departments and applications are offered. */
const departmentChoices = computed(() => !parent.value || parent.value.allDepartments ? hub.departmentOptions
  : hub.departmentOptions.filter(o => parent.value!.departmentIds.includes(o.value)))
const applicationChoices = computed(() => !parent.value || parent.value.allApplications ? hub.applicationOptions
  : hub.applicationOptions.filter(o => parent.value!.applicationIds.includes(o.value)))
// Usernames only use characters that are valid in a path segment as they are; percent-encoding "@" would make
// a non-canonical path, which the server refuses.
const accountPath = (username: string) => `/admin/accounts/${username}`
const rules = computed(() => ({
  username: [{ required: true, pattern: /^[a-zA-Z0-9_@-][a-zA-Z0-9_.@-]{2,99}$/, message: '3-100 位字母、数字或 _ . @ -，不能以 . 开头' }],
  displayName: [{ max: 100, message: '最多 100 字' }],
  role: [{ required: true, message: '请选择角色' }],
  parentUsername: [{ required: !editing.value || !!editing.value.parentUsername, message: '请选择上级管理员' }],
  departmentIds: form.allDepartments ? [] : [{ required: true, type: 'array', min: 1, message: '至少选择一个可管理的部门' }],
  applicationIds: form.allApplications ? [] : [{ required: true, type: 'array', min: 1, message: '至少选择一个可管理的软件' }],
  password: editing.value ? [] : newPasswordRules(),
  confirm: editing.value ? [] : [{ required: true, validator: (_: unknown, v: string) => v === form.password ? Promise.resolve() : Promise.reject('两次输入的密码不一致') }],
}))
/** A department administrator is always limited to departments. */
function kindChanged() { if (form.kind === 'DEPARTMENT') form.allDepartments = false }
function edit(row?: Account, under?: Account) {
  editing.value = row ?? null
  const parentAccount = under ?? (row ? undefined : list.rows.value.find(a => a.username === hub.me?.username))
  Object.assign(form, {
    username: row?.username ?? '', displayName: row?.displayName ?? '', role: row?.role ?? 'ADMIN', kind: row?.kind ?? 'MANAGER',
    parentUsername: row?.parentUsername ?? parentAccount?.username ?? '',
    allDepartments: row?.allDepartments ?? parentAccount?.allDepartments ?? true, departmentIds: [...(row?.departmentIds ?? [])],
    allApplications: row?.allApplications ?? parentAccount?.allApplications ?? true, applicationIds: [...(row?.applicationIds ?? [])],
    enabled: row?.enabled ?? true, password: '', confirm: '',
  })
  open.value = true
}
async function save() {
  await formRef.value?.validate()
  const body = {
    displayName: form.displayName.trim(), role: form.role, enabled: form.enabled, kind: form.kind,
    parentUsername: form.parentUsername || undefined,
    allDepartments: form.allDepartments, departmentIds: form.allDepartments ? [] : form.departmentIds,
    allApplications: form.allApplications, applicationIds: form.allApplications ? [] : form.applicationIds,
  }
  const ok = await attempt(saving, () => editing.value
    ? send('PUT', accountPath(editing.value.username), self.value ? { displayName: body.displayName, role: form.role, enabled: true } : body)
    : send('POST', '/admin/accounts', { ...body, username: form.username.trim(), password: form.password }),
  editing.value ? '管理员已更新' : '管理员已创建')
  if (ok) { open.value = false; await list.load(); expanded.value = list.rows.value.map(a => a.username) }
}

const resetOpen = ref(false), resetTarget = ref<Account | null>(null), resetting = ref(false), resetRef = ref<FormInstance>()
const reset = reactive({ password: '', confirm: '' })
const resetRules = {
  password: newPasswordRules(),
  confirm: [{ required: true, validator: (_: unknown, v: string) => v === reset.password ? Promise.resolve() : Promise.reject('两次输入的密码不一致') }],
}
function askReset(row: Account) { resetTarget.value = row; Object.assign(reset, { password: '', confirm: '' }); resetOpen.value = true }
async function saveReset() {
  await resetRef.value?.validate()
  const ok = await attempt(resetting, () => send('PUT', `${accountPath(resetTarget.value!.username)}/password`, { password: reset.password }), '密码已重置，该账号的全部会话已下线')
  if (ok) { resetOpen.value = false; list.load() }
}
async function unlock(row: Account) {
  try { await send('POST', `${accountPath(row.username)}/unlock`); message.success('账号已解锁'); list.load() }
  catch (e) { message.error(errorText(e)) }
}
async function remove(row: Account) {
  try { await send('DELETE', accountPath(row.username)); message.success('管理员已删除'); list.load() }
  catch (e) { message.error(errorText(e)) }
}
function resetMfa(row: Account) {
  Modal.confirm({
    title: `重置“${row.username}”的两步验证？`,
    content: '重置后该账号登录不再需要动态口令，需要本人重新在账号菜单中绑定。',
    okText: '重置', okType: 'danger', cancelText: '取消',
    async onOk() {
      try {
        await send('POST', `${accountPath(row.username)}/mfa/reset`)
        message.success(`已重置 ${row.username} 的两步验证`)
        list.load()
      } catch (e) { message.error(errorText(e)) }
    },
  })
}
function more(row: Account, key: string) {
  if (key === 'reset') askReset(row)
  else if (key === 'unlock') unlock(row)
  else if (key === 'resetMfa') resetMfa(row)
}
const isSelf = (row: Account) => row.username === hub.me?.username
</script>

<template>
  <div class="page">
    <PageHead title="管理员账号" description="管理员按层级管理：每个账号由其上级管理，下级的角色功能与管理范围不超过上级；部门管理员只管理指定部门，不能再设下级。">
      <a-button :loading="list.loading.value" @click="list.load()"><ReloadOutlined />刷新</a-button>
      <GuardedButton fn="account.manage" :allowed="hub.can('account.manage') && hub.me?.kind === 'MANAGER'" type="primary" @click="edit()"><PlusOutlined />新建管理员</GuardedButton>
    </PageHead>
    <a-card :bordered="false">
      <a-table :columns="columns" :data-source="tree" row-key="username" size="small" :loading="list.loading.value" :scroll="{ x: 1300 }" :pagination="false"
               v-model:expanded-row-keys="expanded">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'username'"><code>{{ record.username }}</code><a-tag v-if="isSelf(record)" class="title-tag">当前</a-tag></template>
          <template v-else-if="column.key === 'role'"><a-tag :color="roleColor(record.role)">{{ roleName(record.role) }}</a-tag></template>
          <template v-else-if="column.key === 'kind'">
            <a-tag :color="record.kind === 'DEPARTMENT' ? 'gold' : 'geekblue'">{{ kindLabels[record.kind as AdminKind] }}</a-tag>
            <a-tag v-if="!record.parentUsername" class="title-tag">顶级</a-tag>
          </template>
          <template v-else-if="column.key === 'scope'">
            <div>
              <span class="muted">部门：</span>
              <span v-if="record.allDepartments">全部</span>
              <template v-else><a-tag v-for="id in record.departmentIds" :key="id">{{ hub.departmentName(id) }}</a-tag></template>
            </div>
            <div>
              <span class="muted">软件：</span>
              <span v-if="record.allApplications">全部</span>
              <template v-else>
                <a-tag v-for="id in record.applicationIds" :key="id">{{ hub.applicationName(id) }}</a-tag>
                <span v-if="!record.applicationIds.length" class="warn-text">未授权任何软件</span>
              </template>
            </div>
          </template>
          <template v-else-if="column.key === 'mfa'">
            <a-badge :status="record.mfaEnabled ? 'success' : 'default'" :text="record.mfaEnabled ? '已启用' : '未启用'" />
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tooltip v-if="record.locked" :title="`锁定至 ${fmt(record.lockedUntil)}`"><a-badge status="error" text="已锁定" /></a-tooltip>
            <a-badge v-else :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" />
          </template>
          <template v-else-if="column.key === 'lastLoginAt'">{{ fmt(record.lastLoginAt) }}</template>
          <template v-else-if="column.key === 'actions'">
            <a-space :size="4">
              <GuardedLink fn="account.manage" :allowed="record.manageable || isSelf(record)" :reason="notManaged" @click="edit(record)">编辑</GuardedLink>
              <template v-if="record.kind === 'MANAGER'">
                <a-divider type="vertical" />
                <GuardedLink fn="account.manage" :allowed="record.manageable || isSelf(record)" :reason="notManaged" @click="edit(undefined, record)">新建下级</GuardedLink>
              </template>
              <a-divider type="vertical" />
              <a-dropdown :disabled="!record.manageable">
                <GuardedLink fn="account.manage" :allowed="record.manageable" :reason="notManaged">更多 <DownOutlined /></GuardedLink>
                <template #overlay>
                  <a-menu @click="(e: { key: string }) => more(record, e.key)">
                    <a-menu-item key="reset">重置密码</a-menu-item>
                    <a-menu-item key="unlock" :disabled="!record.locked && !record.failedAttempts">解除锁定</a-menu-item>
                    <a-menu-item key="resetMfa" :disabled="!record.mfaEnabled">重置 MFA</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
              <a-divider type="vertical" />
              <a-popconfirm :disabled="!record.manageable" :title="record.children?.length ? '该管理员还有下级，需先移交或删除下级' : '删除后该账号立即无法登录，确定删除？'"
                            ok-text="删除" cancel-text="取消" :ok-button-props="{ danger: true }" @confirm="remove(record)">
                <GuardedLink fn="account.manage" :allowed="record.manageable" :reason="isSelf(record) ? '不能删除当前登录的账号' : notManaged" class="danger-link">删除</GuardedLink>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="editing ? `编辑管理员：${editing.username}` : '新建管理员'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" width="640px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12"><a-form-item label="账号" name="username"><a-input v-model:value="form.username" :disabled="!!editing" :maxlength="100" autocomplete="off" /></a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="显示名称" name="displayName"><a-input v-model:value="form.displayName" :maxlength="100" placeholder="默认同账号" /></a-form-item></a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12">
            <a-form-item label="上级管理员" name="parentUsername" extra="下级的角色功能与管理范围不能超过上级">
              <a-select v-model:value="form.parentUsername" :options="parentOptions" :disabled="self || (!!editing && !editing.parentUsername)" placeholder="顶级" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="类型" name="kind">
              <a-radio-group v-model:value="form.kind" :disabled="self" @change="kindChanged">
                <a-radio value="MANAGER">管理员</a-radio>
                <a-radio value="DEPARTMENT">部门管理员</a-radio>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="角色" name="role" :extra="form.kind === 'DEPARTMENT' ? '部门管理员不能有下级，角色中不能包含管理管理员账号或角色的功能' : roleDescription">
          <a-select v-model:value="form.role" :options="roleOptions" :loading="roles.loading.value" :disabled="self" />
        </a-form-item>
        <a-form-item label="可管理的部门" name="allDepartments">
          <a-radio-group v-model:value="form.allDepartments" :disabled="self">
            <a-radio :value="true" :disabled="form.kind === 'DEPARTMENT' || (!!parent && !parent.allDepartments)">全部部门</a-radio>
            <a-radio :value="false">指定部门</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="!form.allDepartments" name="departmentIds">
          <a-select v-model:value="form.departmentIds" mode="multiple" :options="departmentChoices" option-filter-prop="label" placeholder="选择部门" :disabled="self" />
        </a-form-item>
        <a-form-item label="可管理的软件" name="allApplications" extra="限定软件时，角色、权限、授权、审批、复核、密钥等按软件的操作只能在所选软件中进行。">
          <a-radio-group v-model:value="form.allApplications" :disabled="self">
            <a-radio :value="true" :disabled="!!parent && !parent.allApplications">全部软件</a-radio>
            <a-radio :value="false">指定软件</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="!form.allApplications" name="applicationIds">
          <a-select v-model:value="form.applicationIds" mode="multiple" :options="applicationChoices" option-filter-prop="label" placeholder="选择软件" :disabled="self" />
        </a-form-item>
        <template v-if="!editing">
          <a-row :gutter="16">
            <a-col :xs="24" :sm="12"><a-form-item label="初始密码" name="password"><a-input-password v-model:value="form.password" autocomplete="new-password" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="确认密码" name="confirm"><a-input-password v-model:value="form.confirm" autocomplete="new-password" /></a-form-item></a-col>
          </a-row>
        </template>
        <a-form-item label="启用" name="enabled"><a-switch v-model:checked="form.enabled" :disabled="self" /></a-form-item>
        <a-alert v-if="self" type="info" show-icon message="只能修改自己的显示名称；角色、类型、管理范围和上级由上级管理员设定。" />
        <a-alert v-else-if="editing && (editing.role !== form.role || editing.kind !== form.kind || (editing.enabled && !form.enabled))" type="warning" show-icon message="修改角色、类型或停用后，该账号的全部登录会话立即下线。" />
      </a-form>
    </a-modal>

    <a-modal v-model:open="resetOpen" :title="`重置密码：${resetTarget?.username ?? ''}`" :confirm-loading="resetting" ok-text="重置" destroy-on-close :mask-closable="false" @ok="saveReset">
      <a-form ref="resetRef" layout="vertical" :model="reset">
        <a-form-item label="新密码" name="password" :rules="resetRules.password"><a-input-password v-model:value="reset.password" autocomplete="new-password" /></a-form-item>
        <a-form-item label="确认新密码" name="confirm" :rules="resetRules.confirm">
          <a-input-password v-model:value="reset.confirm" autocomplete="new-password" />
        </a-form-item>
        <a-alert type="warning" show-icon message="重置后该账号的全部登录会话立即下线，同时解除锁定。" />
      </a-form>
    </a-modal>
  </div>
</template>
