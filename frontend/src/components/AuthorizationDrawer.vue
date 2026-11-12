<script setup lang="ts">
import { computed, provide, reactive, ref, watch } from 'vue'
import { useHub } from '../store'
import { authorizationScopeKey } from '../authorizationScope'
import Roles from '../pages/Roles.vue'
import Permissions from '../pages/Permissions.vue'
import Assignments from '../pages/Assignments.vue'
import Users from '../pages/Users.vue'
import DomainCopyModal from './DomainCopyModal.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ departmentId?: number; applicationId?: number; initialTab?: string }>()
const hub = useHub()
const departmentId = ref(0), applicationId = ref(0), tab = ref('assignments'), revision = ref(0), copyOpen = ref(false)
const department = computed(() => hub.departments.find(d => d.id === departmentId.value))
const application = computed(() => hub.applications.find(a => a.id === applicationId.value))
const hasDomain = computed(() => !!department.value && !!application.value)
const scope = reactive({
  departmentId, applicationId, department, application, hasDomain,
  domainKey: computed(() => hasDomain.value ? `${departmentId.value}:${applicationId.value}` : ''),
  domainQuery: computed(() => ({ departmentId: departmentId.value, applicationId: applicationId.value })),
  canConfigure: computed(() => hasDomain.value && hub.can('domain.configure', applicationId.value)),
  canAssign: computed(() => hasDomain.value && hub.can('domain.assign', applicationId.value)),
  canTransfer: computed(() => hasDomain.value && hub.can('domain.transfer', applicationId.value)),
})
provide(authorizationScopeKey, scope)
const title = computed(() => props.applicationId ? `软件配置：${hub.applicationName(props.applicationId)}` : `部门配置：${hub.departmentName(props.departmentId)}`)
const canCopy = computed(() => props.applicationId ? hub.can('domain.transfer', props.applicationId) : hub.applications.some(a => hub.can('domain.transfer', a.id)))

watch(() => [open.value, props.departmentId, props.applicationId], () => {
  if (!open.value) return
  departmentId.value = props.departmentId ?? hub.departmentId
  applicationId.value = props.applicationId ?? hub.applicationId
  tab.value = props.initialTab ?? 'assignments'
  copyOpen.value = false
  revision.value++
}, { immediate: true })

function navigate(value: string) { tab.value = value }
function copied() { revision.value++; hub.loadLists() }
function afterOpenChange(value: boolean) { if (!value) hub.loadLists() }
</script>

<template>
  <a-drawer v-model:open="open" :title="title" width="min(1180px, 94vw)" destroy-on-close :push="false"
            root-class-name="authorization-drawer" @after-open-change="afterOpenChange">
    <template #extra>
      <a-button v-if="canCopy && hub.departments.length > 1" @click="copyOpen = true">{{ props.applicationId ? '复制到多个部门' : '批量配置软件' }}</a-button>
    </template>
    <div class="toolbar authorization-scope">
      <template v-if="props.applicationId">
        <span>配置部门</span>
        <a-select v-model:value="departmentId" :options="hub.departmentOptions" placeholder="选择部门" aria-label="配置部门" show-search option-filter-prop="label" class="toolbar-select" />
      </template>
      <template v-else>
        <span>配置软件</span>
        <a-select v-model:value="applicationId" :options="hub.applicationOptions" placeholder="选择软件" aria-label="配置软件" show-search option-filter-prop="label" class="toolbar-select" />
      </template>
      <span class="muted">{{ department?.name }} × {{ application?.name }}</span>
    </div>
    <a-tabs v-model:active-key="tab">
      <a-tab-pane key="assignments" tab="成员授权" />
      <a-tab-pane key="roles" tab="角色" />
      <a-tab-pane key="permissions" tab="权限项" />
      <a-tab-pane key="members" tab="部门成员" />
    </a-tabs>
    <div v-if="open" class="authorization-content">
      <Users v-if="tab === 'members' && departmentId" :key="`members:${departmentId}`" embedded :department-id="departmentId" @navigate="navigate" />
      <template v-else-if="scope.hasDomain">
        <Roles v-if="tab === 'roles'" :key="`roles:${scope.domainKey}:${revision}`" embedded @navigate="navigate" />
        <Permissions v-else-if="tab === 'permissions'" :key="`permissions:${scope.domainKey}:${revision}`" embedded />
        <Assignments v-else :key="`assignments:${departmentId}:${revision}`" embedded @navigate="navigate" />
      </template>
      <a-empty v-else description="请先选择部门和软件。可在部门、软件列表中创建。" />
    </div>
    <DomainCopyModal v-model:open="copyOpen" :department-id="departmentId" :application-id="applicationId"
                     :mode="props.applicationId ? 'application' : 'department'" @changed="copied" />
  </a-drawer>
</template>
