<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { get, send, type PageResult } from '../api'
import { attempt, usePaged } from '../composables'
import { fmt, requestStatus } from '../labels'
import { useHub } from '../store'
import type { AccessRequest } from '../types'
import PageHead from '../components/PageHead.vue'
import GuardedLink from '../components/GuardedLink.vue'

const hub = useHub()
const filters = reactive<{ status?: string; keyword: string; applicationId?: number }>({ keyword: '', applicationId: hub.applicationId || undefined })
const table = usePaged<AccessRequest>((page, size) =>
  get<PageResult<AccessRequest>>('/admin/access-requests', { status: filters.status, keyword: filters.keyword.trim(), applicationId: filters.applicationId, page, size }))
table.load()

const columns: TableColumnsType<AccessRequest> = [
  { title: '申请人', key: 'user', width: 170, ellipsis: true },
  { title: '授权域', key: 'domain', width: 210, ellipsis: true },
  { title: '角色', key: 'role', width: 190, ellipsis: true },
  { title: '申请理由', dataIndex: 'reason', key: 'reason', ellipsis: true },
  { title: '有效天数', dataIndex: 'durationDays', key: 'durationDays', width: 90, align: 'right' },
  { title: '状态', key: 'status', width: 90 },
  { title: '提交', key: 'created', width: 200, ellipsis: true },
  { title: '决定信息', key: 'decision', width: 220, ellipsis: true },
  { title: '操作', key: 'actions', width: 110, fixed: 'right' },
]

const decideOpen = ref(false), decideMode = ref<'approve' | 'reject'>('approve'), deciding = ref(false)
const decideTarget = ref<AccessRequest | null>(null), formRef = ref<FormInstance>()
type Term = 'requested' | 'permanent' | 'days'
const form = reactive<{ term: Term; durationDays: number | null; comment: string }>({ term: 'requested', durationDays: null, comment: '' })
const termOptions = computed(() => [
  { value: 'requested', label: decideTarget.value?.durationDays ? `按申请（${decideTarget.value.durationDays} 天）` : '按申请（永久）' },
  { value: 'permanent', label: '永久' },
  { value: 'days', label: '指定天数' },
])
function approve(row: AccessRequest) {
  decideTarget.value = row
  decideMode.value = 'approve'
  Object.assign(form, { term: 'requested', durationDays: row.durationDays, comment: '' })
  decideOpen.value = true
}
function reject(row: AccessRequest) {
  decideTarget.value = row
  decideMode.value = 'reject'
  Object.assign(form, { term: 'requested', durationDays: null, comment: '' })
  decideOpen.value = true
}
function approval() {
  const comment = form.comment.trim() || undefined
  if (form.term === 'permanent') return { permanent: true, comment }
  if (form.term === 'days') return { durationDays: form.durationDays, comment }
  return { comment }
}
async function save() {
  const target = decideTarget.value!
  if (decideMode.value === 'approve' && form.term === 'days' && !form.durationDays) { message.warning('请填写有效天数（1-3650）'); return }
  const ok = await attempt(deciding, () => decideMode.value === 'approve'
    ? send('POST', `/admin/access-requests/${target.id}/approve`, approval())
    : send('POST', `/admin/access-requests/${target.id}/reject`, { comment: form.comment.trim() || undefined }),
  decideMode.value === 'approve' ? '申请已批准，授权已生效' : '申请已驳回')
  if (ok) { decideOpen.value = false; table.load() }
}
</script>

<template>
  <div class="page">
    <PageHead title="审批中心" description="跨部门查看申请，使用下方的软件和状态筛选。每条申请的授权域以该行显示为准。">
      <a-button :loading="table.loading.value" @click="table.load()"><ReloadOutlined />刷新</a-button>
    </PageHead>
    <a-card :bordered="false">
      <div class="toolbar">
        <a-select v-model:value="filters.status" placeholder="全部状态" allow-clear class="toolbar-select-sm"
                  :options="Object.entries(requestStatus).map(([value, s]) => ({ value, label: s.text }))" @change="table.load(true)" />
        <a-select v-model:value="filters.applicationId" :options="hub.applicationOptions" placeholder="全部软件" allow-clear show-search
                  option-filter-prop="label" class="toolbar-select" @change="table.load(true)" />
        <a-input-search v-model:value="filters.keyword" placeholder="申请人、外部标识或理由" allow-clear class="toolbar-search" @search="table.load(true)" />
        <span class="muted">共 {{ table.total.value }} 条申请</span>
      </div>
      <a-table :columns="columns" :data-source="table.rows.value" row-key="id" size="small" :loading="table.loading.value"
               :pagination="table.pagination.value" :scroll="{ x: 1500 }" @change="table.onChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'user'">
            <div class="cell-main">{{ record.userName }}</div>
            <div class="cell-sub"><code>{{ record.externalId }}</code></div>
          </template>
          <template v-else-if="column.key === 'domain'">{{ record.departmentName }} × {{ record.applicationName }}</template>
          <template v-else-if="column.key === 'role'">
            {{ record.roleName }} <code>{{ record.roleCode }}</code>
            <a-tag v-if="record.roleId == null" color="default">已删除</a-tag>
            <a-tag v-else-if="record.roleEnabled === false" color="default">已停用</a-tag>
          </template>
          <template v-else-if="column.key === 'reason'"><a-tooltip v-if="record.reason" :title="record.reason">{{ record.reason }}</a-tooltip><span v-else class="muted">—</span></template>
          <template v-else-if="column.key === 'durationDays'">{{ record.durationDays ? `${record.durationDays} 天` : '永久' }}</template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="requestStatus[record.status]?.color">{{ requestStatus[record.status]?.text ?? record.status }}</a-tag>
          </template>
          <template v-else-if="column.key === 'created'">
            <div class="cell-main">{{ record.requestedBy }}</div>
            <div class="cell-sub">{{ fmt(record.createdAt) }}</div>
          </template>
          <template v-else-if="column.key === 'decision'">
            <template v-if="record.decidedBy">
              <div class="cell-main">{{ record.decidedBy }} · {{ fmt(record.decidedAt) }}</div>
              <div class="cell-sub">
                {{ record.decisionComment || '—' }}
                <template v-if="record.grantExpiresAt">；授权至 {{ fmt(record.grantExpiresAt) }}</template>
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-space v-if="record.status === 'PENDING'" :size="4">
              <GuardedLink fn="request.approve" :app="record.applicationId" @click="approve(record)">批准</GuardedLink>
              <a-divider type="vertical" />
              <GuardedLink fn="request.approve" :app="record.applicationId" class="danger-link" @click="reject(record)">驳回</GuardedLink>
            </a-space>
            <span v-else class="muted">—</span>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="decideOpen" :title="decideMode === 'approve' ? `批准申请：${decideTarget?.userName ?? ''}` : `驳回申请：${decideTarget?.userName ?? ''}`"
             :confirm-loading="deciding" :ok-text="decideMode === 'approve' ? '批准' : '驳回'" :ok-button-props="decideMode === 'reject' ? { danger: true } : {}"
             destroy-on-close :mask-closable="false" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form">
        <template v-if="decideMode === 'approve'">
          <a-form-item label="授权期限" extra="批准后立即生效；有期限的授权到期自动回收">
            <a-radio-group v-model:value="form.term" :options="termOptions" />
          </a-form-item>
          <a-form-item v-if="form.term === 'days'" label="有效天数" required>
            <a-input-number v-model:value="form.durationDays" :min="1" :max="3650" :precision="0" placeholder="1-3650" class="grow" />
          </a-form-item>
        </template>
        <a-form-item label="备注（可选）"><a-textarea v-model:value="form.comment" :maxlength="500" :rows="3" show-count /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
