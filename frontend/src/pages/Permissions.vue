<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { ExportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { downloadCsv } from '../csv'
import { errorText, get, send } from '../api'
import { useList } from '../composables'
import { fmt } from '../labels'
import { useAuthorizationScope } from '../authorizationScope'
import type { Permission } from '../types'
import PageHead from '../components/PageHead.vue'
import DomainEmpty from '../components/DomainEmpty.vue'
import ReadonlyHint from '../components/ReadonlyHint.vue'
import PermissionHoldersDrawer from '../components/PermissionHoldersDrawer.vue'
import PermissionEditor from '../components/PermissionEditor.vue'
import GuardedButton from '../components/GuardedButton.vue'
import GuardedLink from '../components/GuardedLink.vue'

const scope = useAuthorizationScope()
defineProps<{ embedded?: boolean }>()
const list = useList<Permission>(() => get<Permission[]>('/admin/permissions', scope.domainQuery))
watch(() => scope.domainKey, key => { if (key) list.load(); else list.clear() }, { immediate: true })

const keyword = ref(''), resource = ref<string>()
const resources = computed(() => [...new Set(list.rows.value.map(p => p.resource))].sort().map(r => ({ value: r, label: r })))
const rows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return list.rows.value.filter(p => (!k || p.code.toLowerCase().includes(k) || p.name.toLowerCase().includes(k)) && (!resource.value || p.resource === resource.value))
})
const columns: TableColumnsType<Permission> = [
  { title: '权限编码', dataIndex: 'code', key: 'code', width: 220, ellipsis: true, sorter: (a, b) => a.code.localeCompare(b.code) },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '资源', dataIndex: 'resource', width: 150, ellipsis: true },
  { title: '动作', dataIndex: 'action', width: 110, ellipsis: true },
  { title: '关联角色', dataIndex: 'roleCount', width: 90, align: 'right' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 165 },
  { title: '操作', key: 'actions', width: 170, fixed: 'right' },
]

const open = ref(false), editing = ref<Permission | null>(null)
function edit(row?: Permission) { editing.value = row ?? null; open.value = true }
function saved() { list.load() }
async function remove(row: Permission) {
  try { await send('DELETE', `/admin/permissions/${row.id}`, undefined, scope.domainQuery); message.success('权限已删除'); list.load() }
  catch (e) { message.error(errorText(e)) }
}
const holdersOpen = ref(false), holdersTarget = ref<Permission>()
function showHolders(row: Permission) { holdersTarget.value = row; holdersOpen.value = true }
function exportRows() {
  downloadCsv('permissions', ['部门', '软件', '权限编码', '名称', '资源', '操作', '关联角色数'],
    rows.value.map(p => [scope.department?.name, scope.application?.name, p.code, p.name, p.resource, p.action, p.roleCount]))
}
</script>

<template>
  <div class="page">
    <PageHead :title="embedded ? undefined : '权限'">
      <a-button :disabled="!scope.hasDomain" @click="exportRows"><ExportOutlined />导出列表</a-button>
      <a-button :disabled="!scope.hasDomain" @click="list.load()"><ReloadOutlined />刷新</a-button>
      <GuardedButton v-if="scope.hasDomain" fn="domain.configure" :allowed="scope.canConfigure" type="primary" @click="edit()"><PlusOutlined />新建权限</GuardedButton>
    </PageHead>
    <DomainEmpty v-if="!scope.hasDomain" />
    <template v-else>
      <ReadonlyHint :editable="scope.canConfigure" />
      <a-card :bordered="false">
        <div class="toolbar">
          <a-input-search v-model:value="keyword" placeholder="搜索编码或名称" allow-clear class="toolbar-search" />
          <a-select v-model:value="resource" :options="resources" placeholder="全部资源" allow-clear show-search class="toolbar-select" />
        </div>
        <a-table :columns="columns" :data-source="rows" row-key="id" size="small" :loading="list.loading.value" :scroll="{ x: 1000 }"
                 :pagination="{ size: 'small', defaultPageSize: 50, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'code'"><code>{{ record.code }}</code></template>
            <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
            <template v-else-if="column.key === 'actions'">
              <a-space :size="4">
                <a @click="showHolders(record)">使用者</a>
              </a-space>
              <a-space :size="4">
                <a-divider type="vertical" />
                <GuardedLink fn="domain.configure" :allowed="scope.canConfigure" @click="edit(record)">编辑</GuardedLink>
                <a-divider type="vertical" />
                <a-popconfirm :disabled="!scope.canConfigure" :title="record.roleCount ? `有 ${record.roleCount} 个角色包含该权限，删除后它们立即失去该权限。确定删除？` : '确定删除该权限？'"
                              ok-text="删除" cancel-text="取消" :ok-button-props="{ danger: true }" @confirm="remove(record)">
                  <GuardedLink fn="domain.configure" :allowed="scope.canConfigure" class="danger-link">删除</GuardedLink>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-card>
    </template>

    <PermissionHoldersDrawer v-model:open="holdersOpen" :permission="holdersTarget" :application-id="scope.applicationId" :department-id="scope.departmentId" />

    <PermissionEditor v-model:open="open" :permission="editing" @saved="saved" />
  </div>
</template>
