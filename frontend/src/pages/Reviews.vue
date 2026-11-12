<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { FormInstance, TableColumnsType } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { get, send } from '../api'
import { attempt, useList } from '../composables'
import { fmt, reviewStatus } from '../labels'
import { useHub } from '../store'
import type { AccessReview } from '../types'
import PageHead from '../components/PageHead.vue'
import ReviewDrawer from '../components/ReviewDrawer.vue'
import GuardedButton from '../components/GuardedButton.vue'

const hub = useHub()
const filters = reactive<{ status?: string; applicationId?: number }>({ applicationId: hub.applicationId || undefined })
const list = useList<AccessReview>(() => get<AccessReview[]>('/admin/reviews', { status: filters.status, applicationId: filters.applicationId }))
list.load()

const columns: TableColumnsType<AccessReview> = [
  { title: '名称', key: 'name', ellipsis: true },
  { title: '范围', key: 'scope', width: 230, ellipsis: true },
  { title: '状态', key: 'status', width: 90 },
  { title: '条目', dataIndex: 'items', width: 70, align: 'right' },
  { title: '未决', dataIndex: 'pending', key: 'pending', width: 70, align: 'right' },
  { title: '保留', dataIndex: 'kept', width: 70, align: 'right' },
  { title: '回收', dataIndex: 'revoked', key: 'revoked', width: 70, align: 'right' },
  { title: '截止时间', dataIndex: 'dueAt', key: 'dueAt', width: 165 },
  { title: '创建', key: 'created', width: 190, ellipsis: true },
  { title: '操作', key: 'actions', width: 90, fixed: 'right' },
]

const createOpen = ref(false), saving = ref(false), formRef = ref<FormInstance>()
const form = reactive<{ name: string; applicationId?: number; departmentId?: number; dueDays: number | null }>({ name: '', dueDays: null })
// An administrator limited to some departments reviews one of them at a time; reviews over every department need all departments.
const rules = computed(() => ({
  name: [{ required: true, whitespace: true, max: 100, message: '请输入复核名称（最多 100 字）' }],
  applicationId: [{ required: true, message: '请选择软件' }],
  departmentId: hub.me?.allDepartments ? [] : [{ required: true, message: '请选择要复核的部门' }],
}))
function create() {
  Object.assign(form, { name: '', applicationId: hub.applicationId || undefined, departmentId: undefined, dueDays: null })
  createOpen.value = true
}
async function save() {
  await formRef.value?.validate()
  const ok = await attempt(saving, () => send('POST', '/admin/reviews', {
    name: form.name.trim(), applicationId: form.applicationId, departmentId: form.departmentId ?? undefined, dueDays: form.dueDays ?? undefined,
  }), '复核已发起')
  if (ok) { createOpen.value = false; list.load() }
}

const drawerOpen = ref(false), current = ref<AccessReview>()
function detail(row: AccessReview) { current.value = row; drawerOpen.value = true }
const createableApps = computed(() => hub.applicationOptions.filter(o => hub.can('review.manage', o.value)))
</script>

<template>
  <div class="page">
    <PageHead title="权限复核" description="跨部门查看复核，使用下方的软件和状态筛选。每次复核的部门范围在创建时指定。">
      <a-button :loading="list.loading.value" @click="list.load()"><ReloadOutlined />刷新</a-button>
      <GuardedButton fn="review.manage" :allowed="createableApps.length > 0" type="primary" @click="create"><PlusOutlined />发起复核</GuardedButton>
    </PageHead>
    <a-card :bordered="false">
      <div class="toolbar">
        <a-select v-model:value="filters.status" placeholder="全部状态" allow-clear class="toolbar-select-sm"
                  :options="Object.entries(reviewStatus).map(([value, s]) => ({ value, label: s.text }))" @change="list.load()" />
        <a-select v-model:value="filters.applicationId" :options="hub.applicationOptions" placeholder="全部软件" allow-clear show-search
                  option-filter-prop="label" class="toolbar-select" @change="list.load()" />
        <span class="muted">共 {{ list.rows.value.length }} 次复核</span>
      </div>
      <a-table :columns="columns" :data-source="list.rows.value" row-key="id" size="small" :loading="list.loading.value" :scroll="{ x: 1300 }"
               :pagination="{ size: 'small', defaultPageSize: 20, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <div class="cell-main">{{ record.name }}</div>
            <div v-if="record.summary" class="cell-sub">{{ record.summary }}</div>
          </template>
          <template v-else-if="column.key === 'scope'">{{ record.applicationName }}<span class="muted"> × </span>{{ record.departmentName ?? '全部部门' }}</template>
          <template v-else-if="column.key === 'status'"><a-tag :color="reviewStatus[record.status]?.color">{{ reviewStatus[record.status]?.text ?? record.status }}</a-tag></template>
          <template v-else-if="column.key === 'pending'"><span :class="{ 'warn-text': record.status === 'OPEN' && record.pending }">{{ record.pending }}</span></template>
          <template v-else-if="column.key === 'revoked'"><span :class="{ 'warn-text': record.revoked }">{{ record.revoked }}</span></template>
          <template v-else-if="column.key === 'dueAt'">{{ record.dueAt ? fmt(record.dueAt) : '—' }}</template>
          <template v-else-if="column.key === 'created'">
            <div class="cell-main">{{ record.createdBy }}</div>
            <div class="cell-sub">{{ fmt(record.createdAt) }}</div>
          </template>
          <template v-else-if="column.key === 'actions'"><a @click="detail(record)">{{ record.status === 'OPEN' && hub.can('review.manage', record.applicationId, record.departmentId) ? '处理' : '明细' }}</a></template>
        </template>
        <template #emptyText><a-empty description="还没有复核记录" :image-style="{ height: '40px' }" /></template>
      </a-table>
    </a-card>

    <a-modal v-model:open="createOpen" title="发起权限复核" :confirm-loading="saving" ok-text="发起" destroy-on-close :mask-closable="false" width="520px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-form-item label="复核名称" name="name"><a-input v-model:value="form.name" :maxlength="100" placeholder="例如：2026Q4 季度复核" /></a-form-item>
        <a-form-item label="软件" name="applicationId"><a-select v-model:value="form.applicationId" :options="createableApps" placeholder="选择软件" show-search option-filter-prop="label" /></a-form-item>
        <a-form-item label="部门" name="departmentId" :extra="hub.me?.allDepartments ? '不选则覆盖该软件在全部部门的授权' : '只能复核你管理的部门'">
          <a-select v-model:value="form.departmentId" :options="hub.departmentOptions" placeholder="全部部门" allow-clear show-search option-filter-prop="label" />
        </a-form-item>
        <a-form-item label="截止天数" name="dueDays" extra="留空则不设截止时间">
          <a-input-number v-model:value="form.dueDays" :min="1" :max="365" :precision="0" placeholder="不设截止" class="grow" />
        </a-form-item>
      </a-form>
    </a-modal>

    <ReviewDrawer v-model:open="drawerOpen" :review="current" @changed="list.load()" />
  </div>
</template>
