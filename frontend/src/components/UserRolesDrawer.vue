<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { errorText, get } from '../api'
import { useHub } from '../store'
import { useAuthorizationScope } from '../authorizationScope'
import type { Role, User } from '../types'
import UserRolesForm from './UserRolesForm.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ user?: User; departmentId?: number }>()
const emit = defineEmits<{ (e: 'changed'): void }>()
const hub = useHub()
const scope = useAuthorizationScope()

const departmentId = ref(0), applicationId = ref(0)
const roles = ref<Role[]>([]), assigned = ref<number[]>([]), expiries = ref<Record<string, string>>({})
const loading = ref(false), loaded = ref(false), version = ref(0)
const form = ref<InstanceType<typeof UserRolesForm>>()
let sequence = 0

const departmentOptions = computed(() => (props.user?.departmentIds ?? []).map(id => ({ value: id, label: hub.departmentName(id) })))
const canWrite = computed(() => hub.can('domain.assign', applicationId.value))

watch(() => [open.value, props.user?.id], () => {
  sequence++
  roles.value = []
  assigned.value = []
  expiries.value = {}
  loaded.value = false
  if (!open.value || !props.user) return
  const user = props.user
  const preferredDepartment = props.departmentId ?? scope.departmentId
  departmentId.value = user.departmentIds.includes(preferredDepartment) ? preferredDepartment : user.departmentIds[0] ?? 0
  applicationId.value = hub.applications.some(a => a.id === scope.applicationId) ? scope.applicationId : hub.applications[0]?.id ?? 0
  if (departmentId.value && applicationId.value) load()
}, { immediate: true })

watch([departmentId, applicationId], () => { if (open.value && departmentId.value && applicationId.value) load() })

async function load() {
  const current = ++sequence
  loading.value = true
  loaded.value = false
  try {
    const query = { departmentId: departmentId.value, applicationId: applicationId.value }
    const [roleList, current1] = await Promise.all([
      get<Role[]>('/admin/roles', query),
      get<{ roleIds: number[]; roleExpiries?: Record<string, string> }>(`/admin/users/${props.user!.id}/roles`, query),
    ])
    if (current !== sequence) return
    roles.value = roleList
    assigned.value = current1.roleIds
    expiries.value = current1.roleExpiries ?? {}
    loaded.value = true
    version.value++
  } catch (e) {
    if (current === sequence) message.error(errorText(e))
  } finally {
    if (current === sequence) loading.value = false
  }
}
async function save() {
  const ok = await form.value?.submit()
  if (ok) { emit('changed'); await load() }
}
</script>

<template>
  <a-drawer v-model:open="open" :title="`分配角色：${user?.name ?? ''}`" width="640" destroy-on-close>
    <template #extra>
      <a-button v-if="user?.departmentIds.length" type="primary" :disabled="!canWrite || !loaded" :loading="form?.saving" @click="save">保存</a-button>
    </template>
    <template v-if="user">
      <a-empty v-if="!user.departmentIds.length" :image-style="{ height: '60px' }"
               description="该用户还没有加入任何部门。先在「编辑」中把用户加入部门，再来分配角色。" />
      <template v-else>
        <div class="grant-scope">
          <a-select v-model:value="departmentId" :options="departmentOptions" class="grow" aria-label="部门" />
          <span class="domain-x">×</span>
          <a-select v-model:value="applicationId" :options="hub.applicationOptions" class="grow" aria-label="软件" />
        </div>
        <a-alert v-if="!canWrite" type="info" show-icon class="block-gap" message="你对所选软件只有只读权限，这里只能查看。" />
        <a-spin :spinning="loading">
          <UserRolesForm v-if="loaded" ref="form" :key="`${departmentId}:${applicationId}:${version}`"
                         :user-id="user.id" :department-id="departmentId" :application-id="applicationId"
                         :roles="roles" :assigned="assigned" :expiries="expiries" :disabled="!canWrite" />
        </a-spin>
      </template>
    </template>
  </a-drawer>
</template>

<style scoped>
.grant-scope { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.grant-scope .grow { flex: 1; min-width: 0; }
.grant-scope .domain-x { color: var(--mh-text-4); }
</style>
