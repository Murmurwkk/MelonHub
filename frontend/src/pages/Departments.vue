<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { ExportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { downloadCsv } from '../csv'
import { errorText, send } from '../api'
import { attempt } from '../composables'
import { fmt } from '../labels'
import { useHub } from '../store'
import type { Department } from '../types'
import PageHead from '../components/PageHead.vue'
import AuthorizationDrawer from '../components/AuthorizationDrawer.vue'
import GuardedButton from '../components/GuardedButton.vue'
import GuardedLink from '../components/GuardedLink.vue'

const hub = useHub(), router = useRouter(), route = useRoute()
const keyword = ref(''), loading = ref(false), saving = ref(false), open = ref(false)
watch(() => route.query.keyword, value => { keyword.value = typeof value === 'string' ? value : '' }, { immediate: true })
const editing = ref<Department | null>(null), formRef = ref<FormInstance>()
const form = reactive({ name: '', code: '' })
const rules = {
  name: [{ required: true, whitespace: true, max: 100, message: '请输入部门名称（最多 100 字）' }],
  code: [{ required: true, pattern: /^[a-zA-Z0-9_.-]{1,64}$/, message: '1-64 位字母、数字或 _ . -' }],
}

const rows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return k ? hub.departments.filter(d => d.name.toLowerCase().includes(k) || d.code.toLowerCase().includes(k)) : hub.departments
})
const columns: TableColumnsType<Department> = [
  { title: 'ID', dataIndex: 'id', width: 72 },
  { title: '部门名称', dataIndex: 'name', key: 'name', ellipsis: true },
  { title: '编码', dataIndex: 'code', key: 'code', ellipsis: true },
  { title: '成员数', dataIndex: 'memberCount', width: 100, align: 'right', sorter: (a, b) => a.memberCount - b.memberCount },
  { title: '角色数', dataIndex: 'roleCount', width: 100, align: 'right', sorter: (a, b) => a.roleCount - b.roleCount },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', key: 'actions', width: 200 },
]

async function reload() {
  loading.value = true
  try { await hub.loadLists() } catch (e) { message.error(errorText(e)) } finally { loading.value = false }
}
function edit(row?: Department) {
  editing.value = row ?? null
  Object.assign(form, { name: row?.name ?? '', code: row?.code ?? '' })
  open.value = true
}
async function save() {
  await formRef.value?.validate()
  const body = { name: form.name.trim(), code: form.code.trim() }
  let createdId = 0
  const ok = await attempt(saving, async () => {
    if (editing.value) await send('PUT', `/admin/departments/${editing.value.id}`, body)
    else createdId = (await send<{ id: number }>('POST', '/admin/departments', body)).id
  },
    editing.value ? '部门已更新' : '部门已创建')
  if (ok) {
    open.value = false
    await reload()
    if (createdId) { configurationId.value = createdId; configurationTab.value = 'members'; configurationOpen.value = true }
  }
}
function remove(row: Department) {
  Modal.confirm({
    title: `删除部门“${row.name}”？`,
    content: '只能删除没有成员、角色和权限的部门。删除后无法恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    async onOk() {
      try { await send('DELETE', `/admin/departments/${row.id}`); message.success('部门已删除'); await reload() }
      catch (e) { message.error(errorText(e)) }
    },
  })
}
const configurationOpen = ref(false), configurationId = ref<number>(), configurationTab = ref('assignments')
function manage(row: Department) { configurationId.value = row.id; configurationTab.value = 'assignments'; configurationOpen.value = true }
watch(() => [route.query.configure, hub.departments.length], () => {
  const row = hub.departments.find(d => d.id === Number(route.query.configure))
  if (row) manage(row)
}, { immediate: true })
watch(configurationOpen, value => { if (!value && route.query.configure) router.replace({ query: { ...route.query, configure: undefined } }) })
function exportRows() {
  downloadCsv('departments', ['ID', '部门名称', '编码', '成员数', '角色数', '创建时间'],
    rows.value.map(d => [d.id, d.name, d.code, d.memberCount, d.roleCount, fmt(d.createdAt)]))
}
</script>

<template>
  <div class="page">
    <PageHead title="部门" description="选中部门，在配置抽屉中管理软件、角色和成员授权。">
      <a-button @click="exportRows"><ExportOutlined />导出列表</a-button>
      <a-button :loading="loading" @click="reload"><ReloadOutlined />刷新</a-button>
      <GuardedButton fn="department.manage" type="primary" @click="edit()"><PlusOutlined />新建部门</GuardedButton>
    </PageHead>
    <a-card :bordered="false">
      <div class="toolbar">
        <a-input-search v-model:value="keyword" placeholder="搜索名称或编码" allow-clear class="toolbar-search" />
        <span class="muted">共 {{ rows.length }} 个部门</span>
      </div>
      <a-table :columns="columns" :data-source="rows" row-key="id" size="small" :loading="loading" :pagination="{ size: 'small', defaultPageSize: 20, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'"><a @click="manage(record)">{{ record.name }}</a></template>
          <template v-else-if="column.key === 'code'"><code>{{ record.code }}</code></template>
          <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
          <template v-else-if="column.key === 'actions'">
            <a-space :size="4" class="row-actions">
              <a @click="manage(record)">软件与授权</a>
              <a-divider type="vertical" />
              <GuardedLink fn="department.manage" @click="edit(record)">编辑</GuardedLink>
              <a-divider type="vertical" />
              <GuardedLink fn="department.manage" class="danger-link" @click="remove(record)">删除</GuardedLink>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="editing ? '编辑部门' : '新建部门'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-form-item label="部门名称" name="name"><a-input v-model:value="form.name" :maxlength="100" placeholder="例如：研发中心" /></a-form-item>
        <a-form-item label="编码" name="code" extra="全局唯一，业务系统可用它对照组织架构"><a-input v-model:value="form.code" :maxlength="64" placeholder="例如：rd-center" /></a-form-item>
      </a-form>
    </a-modal>
    <AuthorizationDrawer v-model:open="configurationOpen" :department-id="configurationId" :initial-tab="configurationTab" />
  </div>
</template>
