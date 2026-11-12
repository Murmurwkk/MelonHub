<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { DownOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, get, send } from '../api'
import { attempt, newPasswordRules, useList } from '../composables'
import { fmt, roleColor } from '../labels'
import { useHub } from '../store'
import type { Account, AdminRoleDefinition } from '../types'
import PageHead from '../components/PageHead.vue'

const hub = useHub()
const list = useList<Account>(() => get<Account[]>('/admin/accounts'))
const roles = useList<AdminRoleDefinition>(() => get<AdminRoleDefinition[]>('/admin/admin-roles'))
onMounted(() => { list.load(); roles.load() })
const roleOptions = computed(() => roles.rows.value.map(r => ({ value: r.code, label: r.name })))
const roleName = (code: string) => roles.rows.value.find(r => r.code === code)?.name ?? code
const roleDescription = computed(() => roles.rows.value.find(r => r.code === form.role)?.description)

const columns: TableColumnsType<Account> = [
  { title: '账号', dataIndex: 'username', key: 'username', width: 150, ellipsis: true },
  { title: '显示名称', dataIndex: 'displayName', width: 130, ellipsis: true },
  { title: '角色', key: 'role', width: 110 },
  { title: '两步验证', key: 'mfa', width: 90 },
  { title: '可管理软件', key: 'applications' },
  { title: '状态', key: 'status', width: 110 },
  { title: '最近登录', dataIndex: 'lastLoginAt', key: 'lastLoginAt', width: 165 },
  { title: '在线会话', dataIndex: 'activeSessions', width: 90, align: 'right' },
  { title: '操作', key: 'actions', width: 150, fixed: 'right' },
]

const open = ref(false), saving = ref(false), editing = ref<Account | null>(null), formRef = ref<FormInstance>()
const form = reactive<{
  username: string; displayName: string; role: string; allApplications: boolean; applicationIds: number[]; enabled: boolean; password: string; confirm: string
}>({ username: '', displayName: '', role: 'ADMIN', allApplications: true, applicationIds: [], enabled: true, password: '', confirm: '' })
const self = computed(() => editing.value?.username === hub.me?.username)
// Usernames only use characters that are valid in a path segment as they are; percent-encoding "@" would make
// a non-canonical path, which the server refuses.
const accountPath = (username: string) => `/admin/accounts/${username}`
const rules = computed(() => ({
  username: [{ required: true, pattern: /^[a-zA-Z0-9_@-][a-zA-Z0-9_.@-]{2,99}$/, message: '3-100 位字母、数字或 _ . @ -，不能以 . 开头' }],
  displayName: [{ max: 100, message: '最多 100 字' }],
  role: [{ required: true, message: '请选择角色' }],
  applicationIds: form.allApplications ? [] : [{ required: true, type: 'array', min: 1, message: '至少选择一个可管理的软件' }],
  password: editing.value ? [] : newPasswordRules(),
  confirm: editing.value ? [] : [{ required: true, validator: (_: unknown, v: string) => v === form.password ? Promise.resolve() : Promise.reject('两次输入的密码不一致') }],
}))
function edit(row?: Account) {
  editing.value = row ?? null
  Object.assign(form, {
    username: row?.username ?? '', displayName: row?.displayName ?? '', role: row?.role ?? 'ADMIN',
    allApplications: row?.allApplications ?? true, applicationIds: [...(row?.applicationIds ?? [])],
    enabled: row?.enabled ?? true, password: '', confirm: '',
  })
  open.value = true
}
async function save() {
  await formRef.value?.validate()
  const scope = { allApplications: form.allApplications, applicationIds: form.allApplications ? [] : form.applicationIds }
  const ok = await attempt(saving, () => editing.value
    ? send('PUT', accountPath(editing.value.username), { displayName: form.displayName.trim(), role: form.role, enabled: form.enabled, ...scope })
    : send('POST', '/admin/accounts', { username: form.username.trim(), displayName: form.displayName.trim(), role: form.role, enabled: form.enabled, ...scope, password: form.password }),
  editing.value ? '管理员已更新' : '管理员已创建')
  if (ok) { open.value = false; list.load() }
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
</script>

<template>
  <div class="page">
    <PageHead title="管理员账号">
      <a-button :loading="list.loading.value" @click="list.load()"><ReloadOutlined />刷新</a-button>
      <a-button type="primary" @click="edit()"><PlusOutlined />新建管理员</a-button>
    </PageHead>
    <a-card :bordered="false">
      <a-table :columns="columns" :data-source="list.rows.value" row-key="username" size="small" :loading="list.loading.value" :scroll="{ x: 1100 }" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'username'"><code>{{ record.username }}</code><a-tag v-if="record.username === hub.me?.username" class="title-tag">当前</a-tag></template>
          <template v-else-if="column.key === 'role'"><a-tag :color="roleColor(record.role)">{{ roleName(record.role) }}</a-tag></template>
          <template v-else-if="column.key === 'applications'">
            <span v-if="record.allApplications" class="muted">全部</span>
            <template v-else>
              <a-tag v-for="id in record.applicationIds" :key="id">{{ hub.applicationName(id) }}</a-tag>
              <span v-if="!record.applicationIds.length" class="warn-text">未授权任何软件</span>
            </template>
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
              <a @click="edit(record)">编辑</a>
              <a-divider type="vertical" />
              <a-dropdown>
                <a>更多 <DownOutlined /></a>
                <template #overlay>
                  <a-menu @click="(e: { key: string }) => more(record, e.key)">
                    <a-menu-item key="reset">重置密码</a-menu-item>
                    <a-menu-item key="unlock" :disabled="!record.locked && !record.failedAttempts">解除锁定</a-menu-item>
                    <a-menu-item v-if="record.mfaEnabled && record.username !== hub.me?.username" key="resetMfa">重置 MFA</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
              <template v-if="record.username !== hub.me?.username">
                <a-divider type="vertical" />
                <a-popconfirm title="删除后该账号立即无法登录，确定删除？" ok-text="删除" cancel-text="取消" :ok-button-props="{ danger: true }" @confirm="remove(record)">
                  <a class="danger-link">删除</a>
                </a-popconfirm>
              </template>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="editing ? `编辑管理员：${editing.username}` : '新建管理员'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" width="580px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12"><a-form-item label="账号" name="username"><a-input v-model:value="form.username" :disabled="!!editing" :maxlength="100" autocomplete="off" /></a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="显示名称" name="displayName"><a-input v-model:value="form.displayName" :maxlength="100" placeholder="默认同账号" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="角色" name="role" :extra="roleDescription">
          <a-select v-model:value="form.role" :options="roleOptions" :loading="roles.loading.value" :disabled="self" />
        </a-form-item>
        <a-form-item label="可管理的软件" name="allApplications" extra="限定软件时，角色、权限、授权、审批、复核、密钥等按软件的操作只能在所选软件中进行。">
          <a-radio-group v-model:value="form.allApplications" :disabled="self">
            <a-radio :value="true">全部软件</a-radio>
            <a-radio :value="false">指定软件</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="!form.allApplications" name="applicationIds">
          <a-select v-model:value="form.applicationIds" mode="multiple" :options="hub.applicationOptions" option-filter-prop="label" placeholder="选择软件" />
        </a-form-item>
        <template v-if="!editing">
          <a-row :gutter="16">
            <a-col :xs="24" :sm="12"><a-form-item label="初始密码" name="password"><a-input-password v-model:value="form.password" autocomplete="new-password" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="确认密码" name="confirm"><a-input-password v-model:value="form.confirm" autocomplete="new-password" /></a-form-item></a-col>
          </a-row>
        </template>
        <a-form-item label="启用" name="enabled"><a-switch v-model:checked="form.enabled" :disabled="self" /></a-form-item>
        <a-alert v-if="self" type="info" show-icon message="不能修改自己的角色或停用自己的账号。" />
        <a-alert v-else-if="editing && (editing.role !== form.role || (editing.enabled && !form.enabled))" type="warning" show-icon message="修改角色或停用后，该账号的全部登录会话立即下线。" />
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
