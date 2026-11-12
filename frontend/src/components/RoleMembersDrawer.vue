<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { errorText, get, send } from '../api'
import { attempt } from '../composables'
import { useHub } from '../store'
import type { Member, Role, User } from '../types'
import UserPicker from './UserPicker.vue'
import GuardedButton from './GuardedButton.vue'
import GuardedLink from './GuardedLink.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ role?: Role; departmentId: number; applicationId: number; embedded?: boolean }>()
const emit = defineEmits<{ changed: []; navigate: [tab: string] }>()
const hub = useHub()
const members = ref<Member[]>([]), original = ref<number[]>([]), loading = ref(false), saving = ref(false), adding = ref<number[]>([])
const picked = ref<User[]>([])
type Target = { roleId: number; departmentId: number; applicationId: number }
/** The role and domain the member list on screen was loaded for; edits are only ever saved there. */
const loaded = ref<Target | null>(null)
const target = computed<Target | null>(() => props.role ? { roleId: props.role.id, departmentId: props.departmentId, applicationId: props.applicationId } : null)
const same = (a: Target | null, b: Target | null) => !!a && !!b && a.roleId === b.roleId && a.departmentId === b.departmentId && a.applicationId === b.applicationId
const ready = computed(() => same(loaded.value, target.value) && !loading.value)
const canWrite = computed(() => hub.can('domain.assign', props.applicationId))
const dirty = computed(() => {
  const now = members.value.map(m => m.id).sort((a, b) => a - b)
  return now.length !== original.value.length || now.some((id, i) => id !== original.value[i])
})
let sequence = 0

function reset() {
  sequence++
  loaded.value = null
  members.value = []
  original.value = []
  adding.value = []
  picked.value = []
  loading.value = false
}
async function load() {
  const at = target.value
  if (!at) return
  const current = ++sequence
  loading.value = true
  try {
    const rows = await get<Member[]>(`/admin/roles/${at.roleId}/members`, { departmentId: at.departmentId, applicationId: at.applicationId })
    if (current !== sequence) return
    members.value = rows
    original.value = rows.map(m => m.id).sort((a, b) => a - b)
    adding.value = []
    picked.value = []
    loaded.value = at
  } catch (e) {
    if (current === sequence) message.error(errorText(e))
  } finally {
    if (current === sequence) loading.value = false
  }
}
watch(() => [open.value, target.value?.roleId, target.value?.departmentId, target.value?.applicationId], () => {
  reset()
  if (open.value) load()
}, { immediate: true })

function add() {
  if (!ready.value) return
  for (const u of picked.value) {
    if (!members.value.some(m => m.id === u.id)) members.value.push({ id: u.id, name: u.name, externalId: u.externalId, email: u.email, enabled: u.enabled })
  }
  adding.value = []
  picked.value = []
}
function removeMember(id: number) { if (ready.value) members.value = members.value.filter(m => m.id !== id) }
async function save() {
  const at = loaded.value
  if (!ready.value || !at) return
  const ids = members.value.map(m => m.id)
  const ok = await attempt(saving, () => send('PUT', `/admin/roles/${at.roleId}/members`, { ids }, { departmentId: at.departmentId, applicationId: at.applicationId }), '角色成员已更新')
  if (!ok) return
  emit('changed')
  if (same(loaded.value, at)) await load()
}

const columns: TableColumnsType<Member> = [
  { title: '姓名', dataIndex: 'name', ellipsis: true },
  { title: '外部标识', dataIndex: 'externalId', key: 'externalId', ellipsis: true },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '操作', key: 'actions', width: 70 },
]
</script>

<template>
  <a-drawer v-model:open="open" :title="`角色成员：${role?.name ?? ''}`" width="720" destroy-on-close>
    <template #extra>
      <a-space>
        <GuardedButton fn="domain.assign" :allowed="canWrite" :disabled="!ready || !dirty || saving" @click="load">放弃修改</GuardedButton>
        <GuardedButton fn="domain.assign" :allowed="canWrite" type="primary" :disabled="!ready || !dirty" :loading="saving" @click="save">保存</GuardedButton>
      </a-space>
    </template>
    <p class="hint">{{ hub.departmentName(departmentId) }} × {{ hub.applicationName(applicationId) }}。只能添加该部门的成员，保存后变动用户的现有上下文失效。</p>
    <p class="hint">这里按一个角色维护成员。给多人分配多个软件的角色，可使用
      <a v-if="embedded" @click="open = false; emit('navigate', 'assignments')">成员授权</a>
      <router-link v-else to="/assignments" @click="open = false">用户授权</router-link>。
    </p>
    <div class="toolbar">
      <UserPicker v-model:value="adding" :disabled="!ready || !canWrite" multiple @picks="(users: User[]) => (picked = users)" :department-id="departmentId" :exclude="members.map(m => m.id)" placeholder="搜索并选择要加入的部门成员" class="grow" />
      <GuardedButton fn="domain.assign" :allowed="canWrite" :disabled="!ready || !adding.length" @click="add">添加</GuardedButton>
    </div>
    <a-table :columns="columns" :data-source="members" row-key="id" size="small" :loading="loading" :pagination="{ size: 'small', pageSize: 50, hideOnSinglePage: true }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'externalId'"><code>{{ record.externalId }}</code></template>
        <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
        <template v-else-if="column.key === 'actions'"><GuardedLink v-if="ready" fn="domain.assign" :allowed="canWrite" class="danger-link" @click="removeMember(record.id)">移除</GuardedLink></template>
      </template>
      <template #emptyText><a-empty :description="ready ? '该角色还没有成员' : loading ? '正在加载' : '成员列表未能加载'" :image-style="{ height: '40px' }" /></template>
    </a-table>
    <p v-if="dirty" class="warn-text">有未保存的修改</p>
  </a-drawer>
</template>
