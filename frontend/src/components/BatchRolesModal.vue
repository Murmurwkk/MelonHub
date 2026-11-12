<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { errorText, get, send } from '../api'
import { useHub } from '../store'
import type { Role } from '../types'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ departmentId: number; applicationId: number; userIds: number[] }>()
const emit = defineEmits<{ changed: [] }>()
const hub = useHub()
const applications = ref<number[]>([]), roles = ref<Record<number, Role[]>>({}), selected = ref<Record<number, number[]>>({})
const days = ref<number | null>(null), loading = ref(false), saving = ref(false), failure = ref(''), results = ref<string[]>([])
const options = computed(() => hub.applicationOptions.filter(a => hub.can('domain.assign', a.value)))
const ready = computed(() => applications.value.length && applications.value.every(id => selected.value[id]?.length))
let sequence = 0

watch(open, value => {
  sequence++
  if (!value) return
  roles.value = {}
  selected.value = {}
  days.value = null
  failure.value = ''
  results.value = []
  applications.value = options.value.some(a => a.value === props.applicationId) ? [props.applicationId] : []
})
watch(applications, async ids => {
  const current = ++sequence
  loading.value = true
  try {
    const lists = await Promise.all(ids.map(async id => ({ id, roles: await get<Role[]>('/admin/roles', { departmentId: props.departmentId, applicationId: id }) })))
    if (current !== sequence) return
    roles.value = Object.fromEntries(lists.map(item => [item.id, item.roles]))
    selected.value = Object.fromEntries(lists.map(item => [item.id, (selected.value[item.id] ?? []).filter(id => item.roles.some(r => r.id === id))]))
  } catch (e) {
    if (current === sequence) { roles.value = {}; selected.value = {}; message.error(errorText(e)) }
  } finally { if (current === sequence) loading.value = false }
})

async function save() {
  const jobs = applications.value.map(id => ({ applicationId: id, roleIds: [...selected.value[id]!] }))
  saving.value = true
  failure.value = ''
  let completed = 0
  try {
    for (const job of jobs) {
      const result = await send<{ changedUsers: number; grantsAdded: number }>('POST', '/admin/assignments/batch',
        { userIds: props.userIds, roleIds: job.roleIds, expiresInDays: days.value }, { departmentId: props.departmentId, applicationId: job.applicationId })
      results.value.push(`${hub.applicationName(job.applicationId)}：${result.changedUsers} 名成员新增 ${result.grantsAdded} 条角色分配。`)
      completed++
    }
    message.success('所选软件的角色已分配，可继续按成员个别调整')
  } catch (e) {
    failure.value = `${errorText(e)}。已完成的软件保留，可继续处理剩余软件。`
  } finally {
    applications.value = jobs.slice(completed).map(j => j.applicationId)
    saving.value = false
    if (completed) emit('changed')
  }
}
</script>

<template>
  <a-modal v-model:open="open" :title="`为 ${userIds.length} 名成员分配软件角色`" width="720px" ok-text="追加角色" cancel-text="关闭"
           :confirm-loading="saving" :ok-button-props="{ disabled: !ready || loading }" :cancel-button-props="{ disabled: saving }"
           :closable="!saving" :keyboard="!saving" :mask-closable="false" @ok="save">
    <p class="hint">{{ hub.departmentName(departmentId) }}。可同时选择多个软件；已有有效角色及期限保持不变，新增分配使用下面的有效天数。</p>
    <a-form layout="vertical" :disabled="saving">
      <a-form-item label="软件" required>
        <a-select v-model:value="applications" mode="multiple" :options="options" placeholder="选择一个或多个软件" option-filter-prop="label" />
      </a-form-item>
      <a-spin :spinning="loading">
        <section v-for="id in applications" :key="id" class="batch-role-group">
          <div class="toolbar">
            <strong>{{ hub.applicationName(id) }}</strong>
            <span class="muted">已选 {{ selected[id]?.length ?? 0 }} 个角色</span>
            <a-button size="small" :disabled="saving || loading" @click="selected[id] = roles[id]?.map(r => r.id) ?? []">全选</a-button>
            <a-button size="small" :disabled="saving || loading" @click="selected[id] = []">清空</a-button>
          </div>
          <a-checkbox-group v-model:value="selected[id]" :disabled="saving || loading" class="check-list"
                            :options="(roles[id] ?? []).map(r => ({ value: r.id, label: `${r.name}（${r.code}）${r.enabled ? '' : ' · 已停用'}` }))" />
          <p v-if="!loading && !roles[id]?.length" class="muted">该部门在此软件下还没有角色，请先在配置抽屉的「角色」中添加或复制配置。</p>
        </section>
      </a-spin>
      <a-form-item label="新增分配的有效天数" extra="留空为永久，不改变已有有效分配的期限。">
        <a-input-number v-model:value="days" :min="1" :max="3650" :precision="0" placeholder="永久" />
      </a-form-item>
    </a-form>
    <a-alert v-if="failure" type="error" show-icon :message="failure" class="block-gap" />
    <ul v-if="results.length" class="batch-results"><li v-for="(result, index) in results" :key="index">{{ result }}</li></ul>
  </a-modal>
</template>
