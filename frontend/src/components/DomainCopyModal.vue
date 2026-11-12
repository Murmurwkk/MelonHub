<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { errorText, send } from '../api'
import { useHub } from '../store'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ mode: 'department' | 'application'; departmentId: number; applicationId: number }>()
const emit = defineEmits<{ changed: [] }>()
const hub = useHub()
const source = ref<number>(), targets = ref<number[]>([]), applications = ref<number[]>([]), saving = ref(false)
const results = ref<string[]>([]), failure = ref('')
const departmentOptions = computed(() => hub.departmentOptions.filter(d => d.value !== (props.mode === 'application' ? source.value : props.departmentId)))
const applicationOptions = computed(() => hub.applicationOptions.filter(a => hub.can('domain.transfer', a.value)))
const ready = computed(() => !!source.value && (props.mode === 'application' ? targets.value.length : applications.value.length))

watch(open, value => {
  if (!value) return
  source.value = props.mode === 'application' ? props.departmentId : undefined
  targets.value = []
  applications.value = applicationOptions.value.some(a => a.value === props.applicationId) ? [props.applicationId] : []
  results.value = []
  failure.value = ''
})
watch(source, value => { targets.value = targets.value.filter(id => id !== value) })

async function copy() {
  const jobs = props.mode === 'application'
    ? targets.value.map(id => ({ departmentId: id, applicationId: props.applicationId }))
    : applications.value.map(id => ({ departmentId: props.departmentId, applicationId: id }))
  saving.value = true
  failure.value = ''
  let completed = 0
  try {
    for (const job of jobs) {
      const result = await send<Record<string, number>>('POST', '/admin/domains/copy', {
        applicationId: job.applicationId, sourceDepartmentId: source.value, targetDepartmentId: job.departmentId,
      })
      results.value.push(`${hub.departmentName(job.departmentId)} × ${hub.applicationName(job.applicationId)}：新增 ${result.permissionsCreated} 个权限、${result.rolesCreated} 个角色；跳过 ${result.permissionsSkipped} 个已有权限、${result.rolesSkipped} 个已有角色。`)
      completed++
    }
    message.success('所选配置已复制，成员授权可在当前抽屉继续完成')
  } catch (e) {
    failure.value = `${errorText(e)}。已完成的配置保留，可继续处理剩余项。`
  } finally {
    if (props.mode === 'application') targets.value = jobs.slice(completed).map(j => j.departmentId)
    else applications.value = jobs.slice(completed).map(j => j.applicationId)
    saving.value = false
    if (completed) emit('changed')
  }
}
</script>

<template>
  <a-modal v-model:open="open" :title="mode === 'application' ? '复制软件配置到多个部门' : '批量配置部门的软件'" width="680px"
           :confirm-loading="saving" :ok-button-props="{ disabled: !ready }" :cancel-button-props="{ disabled: saving }"
           :closable="!saving" :keyboard="!saving" :mask-closable="false" ok-text="复制配置" cancel-text="关闭" @ok="copy">
    <p class="hint">只补充缺少的权限与角色，已有同编码配置保持不变。不复制成员授权；完成后可在抽屉中继续给成员分配角色。</p>
    <a-form layout="vertical" :disabled="saving">
      <a-form-item label="配置来源部门" required>
        <a-select v-model:value="source" :options="mode === 'application' ? hub.departmentOptions : departmentOptions" placeholder="选择已有配置的部门" show-search option-filter-prop="label" />
      </a-form-item>
      <a-form-item v-if="mode === 'application'" label="目标部门" required>
        <a-select v-model:value="targets" mode="multiple" :options="departmentOptions" placeholder="选择一个或多个部门" option-filter-prop="label" />
      </a-form-item>
      <a-form-item v-else label="要配置的软件" required>
        <a-select v-model:value="applications" mode="multiple" :options="applicationOptions" placeholder="选择一个或多个软件" option-filter-prop="label" />
      </a-form-item>
    </a-form>
    <a-alert v-if="failure" type="error" show-icon :message="failure" class="block-gap" />
    <ul v-if="results.length" class="batch-results"><li v-for="(result, index) in results" :key="index">{{ result }}</li></ul>
  </a-modal>
</template>
