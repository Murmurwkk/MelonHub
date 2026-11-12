<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { DownloadOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { errorText, get, type PageResult } from '../api'
import { usePaged } from '../composables'
import { downloadCsv } from '../csv'
import { actionColor, actionLabels, actionOptions, fmt } from '../labels'
import { useHub } from '../store'
import { isPhone } from '../responsive'
import type { AuditEvent, AuditVerifyResult } from '../types'
import PageHead from '../components/PageHead.vue'

const hub = useHub()
const filters = reactive<{ range?: [string, string]; action?: string; actor: string; keyword: string; departmentId?: number; applicationId?: number }>({ actor: '', keyword: '' })
const query = () => ({
  from: filters.range?.[0], to: filters.range?.[1], action: filters.action, actor: filters.actor.trim(), keyword: filters.keyword.trim(),
  departmentId: filters.departmentId, applicationId: filters.applicationId,
})
const table = usePaged<AuditEvent>((page, size) => get<PageResult<AuditEvent>>('/admin/audit', { ...query(), page, size }), 50)
onMounted(() => table.load())
const filtersOpen = ref(false)
const folded = computed(() => isPhone.value && !filtersOpen.value)
const foldedActive = computed(() => [filters.range?.some(Boolean), filters.action, filters.actor.trim(), filters.departmentId, filters.applicationId].filter(Boolean).length)
function search() { table.load(true) }
function reset() {
  Object.assign(filters, { range: undefined, action: undefined, actor: '', keyword: '', departmentId: undefined, applicationId: undefined })
  table.load(true)
}

const columns: TableColumnsType<AuditEvent> = [
  { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 156 },
  { title: '操作人', dataIndex: 'actor', width: 110, ellipsis: true },
  { title: '事件', key: 'action', width: 140 },
  { title: '对象', dataIndex: 'target', ellipsis: true },
  { title: '详情', dataIndex: 'detail', key: 'detail', ellipsis: true },
  { title: '部门', dataIndex: 'departmentName', key: 'department', width: 110, ellipsis: true },
  { title: '软件', dataIndex: 'applicationName', key: 'application', width: 110, ellipsis: true },
  { title: '来源 IP', dataIndex: 'sourceIp', key: 'sourceIp', width: 120, ellipsis: true },
]

const verifyOpen = ref(false), verifying = ref(false), verifyResult = ref<AuditVerifyResult | null>(null)
const verifyQuery = reactive<{ fromSeq: number | null; limit: number | null }>({ fromSeq: null, limit: null })
function openVerify() { verifyResult.value = null; verifyOpen.value = true }
const verifyAlert = computed(() => {
  const r = verifyResult.value
  if (!r) return null
  if (r.problem === 'MODIFIED') return { type: 'error' as const, text: `记录 ${r.brokenAt}（序号 ${r.brokenSeq}）的内容与封存时不一致，可能被修改` }
  if (r.problem === 'LINK_BROKEN') return { type: 'error' as const, text: `记录 ${r.brokenAt}（序号 ${r.brokenSeq}）与前一条衔接不上，中间的记录可能被删除` }
  if (r.problem === 'TAIL_MISSING') return { type: 'error' as const, text: `链尾应为序号 ${r.seal.lastSeq}，实际只到 ${r.lastSeq ?? '—'}，最新的记录可能被删除` }
  if (!r.complete) return { type: 'info' as const, text: `已校验 ${r.checked} 条，未发现问题；后面还有记录未校验` }
  return { type: 'success' as const, text: r.checked ? '哈希链完整，未发现篡改' : '没有需要校验的记录' }
})
async function continueVerify() {
  verifyQuery.fromSeq = verifyResult.value?.nextSeq ?? null
  await runVerify()
}
async function runVerify() {
  verifying.value = true
  try {
    verifyResult.value = await get<AuditVerifyResult>('/admin/audit/verify', { fromSeq: verifyQuery.fromSeq, limit: verifyQuery.limit })
  } catch (e) {
    message.error(errorText(e))
  } finally {
    verifying.value = false
  }
}

const exporting = ref(false)
async function exportCsv() {
  exporting.value = true
  try {
    const { items: rows, total } = await get<PageResult<AuditEvent>>('/admin/audit/export', query())
    const header = ['时间', '操作人', '操作', '操作编码', '对象', '详情', '部门', '软件', '来源 IP']
    downloadCsv('audit', header, rows.map(e => [fmt(e.createdAt), e.actor, actionLabels[e.action] ?? e.action, e.action, e.target, e.detail, e.departmentName, e.applicationName, e.sourceIp]))
    message.success(total > rows.length ? `已导出前 ${rows.length} 条（共 ${total} 条），请缩小筛选范围后分批导出` : `已导出 ${rows.length} 条`)
  } catch (e) {
    message.error(errorText(e))
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="page">
    <PageHead title="审计日志" :description="!hub.me?.allApplications ? '仅显示你管理的软件的记录' : undefined">
      <a-button :loading="table.loading.value" @click="table.load()"><ReloadOutlined />刷新</a-button>
      <a-button :loading="exporting" @click="exportCsv"><DownloadOutlined />导出 CSV</a-button>
      <a-button @click="openVerify"><SafetyCertificateOutlined />校验链条</a-button>
    </PageHead>
    <a-card :bordered="false">
      <a-form layout="inline" class="filter-form" :class="{ folded }" @submit.prevent="search">
        <a-form-item label="时间" class="filter-extra"><a-range-picker v-model:value="filters.range" value-format="YYYY-MM-DD" :allow-empty="[true, true]" /></a-form-item>
        <a-form-item label="操作" class="filter-extra"><a-select v-model:value="filters.action" :options="actionOptions" placeholder="全部" allow-clear show-search option-filter-prop="label" class="filter-wide" /></a-form-item>
        <a-form-item label="操作人" class="filter-extra"><a-input v-model:value="filters.actor" placeholder="账号或软件标识" allow-clear class="filter-mid" /></a-form-item>
        <a-form-item label="部门" class="filter-extra"><a-select v-model:value="filters.departmentId" :options="hub.departmentOptions" placeholder="全部" allow-clear show-search option-filter-prop="label" class="filter-mid" /></a-form-item>
        <a-form-item label="软件" class="filter-extra"><a-select v-model:value="filters.applicationId" :options="hub.applicationOptions" placeholder="全部" allow-clear show-search option-filter-prop="label" class="filter-mid" /></a-form-item>
        <a-form-item label="关键字"><a-input v-model:value="filters.keyword" placeholder="对象、详情或操作人" allow-clear class="filter-mid" /></a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit">查询</a-button><a-button @click="reset">重置</a-button>
            <a-button v-if="isPhone" type="link" @click="filtersOpen = !filtersOpen">{{ filtersOpen ? '收起筛选' : foldedActive ? `更多筛选（${foldedActive}）` : '更多筛选' }}</a-button>
          </a-space>
        </a-form-item>
      </a-form>
      <a-table :columns="columns" :data-source="table.rows.value" row-key="id" size="small" :loading="table.loading.value" :pagination="table.pagination.value" :scroll="{ x: 1140 }" @change="table.onChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
          <template v-else-if="column.key === 'action'">
            <a-tooltip :title="record.action"><a-tag :color="actionColor(record.action)">{{ actionLabels[record.action] ?? record.action }}</a-tag></a-tooltip>
          </template>
          <template v-else-if="column.key === 'detail'"><a-tooltip v-if="record.detail" :title="record.detail">{{ record.detail }}</a-tooltip><span v-else class="muted">—</span></template>
          <template v-else-if="column.key === 'department'">{{ record.departmentName ?? (record.departmentId ? `#${record.departmentId}（已删除）` : '—') }}</template>
          <template v-else-if="column.key === 'application'">{{ record.applicationName ?? (record.applicationId ? `#${record.applicationId}（已删除）` : '—') }}</template>
          <template v-else-if="column.key === 'sourceIp'">{{ record.sourceIp || '—' }}</template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="verifyOpen" title="校验审计哈希链" :footer="null" destroy-on-close width="560px">
      <p class="muted">记录按封存顺序编号（序号）。默认从链头校验到链尾，并核对链尾与封存记录一致。</p>
      <a-form layout="inline" class="filter-form" @submit.prevent="runVerify">
        <a-form-item label="起始序号"><a-input-number v-model:value="verifyQuery.fromSeq" :min="1" :precision="0" placeholder="从头" class="filter-mid" /></a-form-item>
        <a-form-item label="最多校验"><a-input-number v-model:value="verifyQuery.limit" :min="1" :max="50000" :precision="0" placeholder="默认 5000" class="filter-mid" /></a-form-item>
        <a-form-item><a-button type="primary" html-type="submit" :loading="verifying">开始校验</a-button></a-form-item>
      </a-form>
      <template v-if="verifyResult && verifyAlert">
        <a-alert :type="verifyAlert.type" show-icon class="block-gap" :message="verifyAlert.text">
          <template v-if="verifyResult.nextSeq" #action><a-button size="small" :loading="verifying" @click="continueVerify">继续校验</a-button></template>
        </a-alert>
        <a-descriptions size="small" bordered :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item label="校验条数">{{ verifyResult.checked }}</a-descriptions-item>
          <a-descriptions-item label="序号范围">{{ verifyResult.firstSeq ?? '—' }} ~ {{ verifyResult.lastSeq ?? '—' }}</a-descriptions-item>
          <a-descriptions-item label="链尾序号">{{ verifyResult.seal.lastSeq }}</a-descriptions-item>
          <a-descriptions-item label="保留期清理">{{ verifyResult.seal.anchorSeq ? `已清理序号 ${verifyResult.seal.anchorSeq} 及之前` : '未清理' }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-modal>
  </div>
</template>
