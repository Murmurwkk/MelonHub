<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Modal, message, type TableColumnsType } from 'ant-design-vue'
import { errorText, get, send } from '../api'
import { expiringSoon, fmt } from '../labels'
import { useHub } from '../store'
import type { AccessDomain, ActiveContext, UserAccess } from '../types'
import GuardedButton from './GuardedButton.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ userId?: number }>()
const emit = defineEmits<{ (e: 'changed'): void }>()
const hub = useHub()
const data = ref<UserAccess | null>(null), loading = ref(false)
let sequence = 0

async function load() {
  if (!props.userId) return
  const current = ++sequence
  loading.value = true
  try {
    const result = await get<UserAccess>(`/admin/users/${props.userId}/access`)
    if (current === sequence) data.value = result
  } catch (e) {
    if (current === sequence) message.error(errorText(e))
  } finally {
    if (current === sequence) loading.value = false
  }
}
watch(() => [open.value, props.userId], () => { if (open.value) { data.value = null; load() } }, { immediate: true })

const validContexts = computed(() => data.value?.contexts.filter(c => c.valid).length ?? 0)
const domainColumns: TableColumnsType<AccessDomain> = [
  { title: '部门', dataIndex: 'departmentName', width: 130, ellipsis: true },
  { title: '软件', dataIndex: 'applicationName', key: 'application', width: 150, ellipsis: true },
  { title: '角色', key: 'roles' },
  { title: '有效权限', key: 'permissions' },
]
const contextColumns: TableColumnsType<ActiveContext> = [
  { title: '部门', dataIndex: 'departmentName', ellipsis: true },
  { title: '软件', dataIndex: 'applicationName', ellipsis: true },
  { title: '激活角色数', dataIndex: 'activeRoleCount', width: 96, align: 'right' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160 },
  { title: '过期时间', dataIndex: 'expiresAt', key: 'expiresAt', width: 160 },
  { title: '状态', key: 'valid', width: 80 },
]

function revoke() {
  const user = data.value?.user
  if (!user) return
  Modal.confirm({
    title: `强制“${user.name}”下线？`,
    content: '该用户在所有软件中的权限上下文立即失效，业务系统需要重新为其创建上下文。',
    okText: '强制下线', okType: 'danger', cancelText: '取消',
    async onOk() {
      try {
        const result = await send<{ revoked: number }>('DELETE', `/admin/users/${user.id}/contexts`)
        message.success(`已注销 ${result.revoked} 个权限上下文`)
        emit('changed')
        await load()
      } catch (e) { message.error(errorText(e)) }
    },
  })
}
</script>

<template>
  <a-drawer v-model:open="open" :title="data ? `授权详情：${data.user.name}` : '授权详情'" width="920" destroy-on-close>
    <template #extra>
      <a-space>
        <a-button @click="load">刷新</a-button>
        <GuardedButton fn="user.manage" :allowed="!data || hub.canManageUser(data.user.departmentIds)"
                       :reason="hub.denial('user.manage') || '该用户还属于你管理范围以外的部门'" danger :disabled="!data" @click="revoke">强制下线</GuardedButton>
      </a-space>
    </template>
    <a-spin :spinning="loading">
      <template v-if="data">
        <a-descriptions size="small" bordered :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item label="姓名">{{ data.user.name }}</a-descriptions-item>
          <a-descriptions-item label="外部标识"><code>{{ data.user.externalId }}</code></a-descriptions-item>
          <a-descriptions-item label="邮箱">{{ data.user.email || '—' }}</a-descriptions-item>
          <a-descriptions-item label="状态"><a-badge :status="data.user.enabled ? 'success' : 'default'" :text="data.user.enabled ? '启用' : '停用'" /></a-descriptions-item>
          <a-descriptions-item label="所属部门">
            <a-tag v-for="id in data.user.departmentIds" :key="id">{{ hub.departmentName(id) }}</a-tag>
            <span v-if="!data.user.departmentIds.length" class="muted">未加入部门</span>
          </a-descriptions-item>
          <a-descriptions-item label="当前部门">{{ hub.departmentName(data.user.activeDepartmentId) }}</a-descriptions-item>
        </a-descriptions>

        <h4 class="section-title">各授权域的角色与权限</h4>
        <a-table :columns="domainColumns" :data-source="data.domains" :row-key="(r: AccessDomain) => `${r.departmentId}:${r.applicationId}`" size="small" :pagination="false">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'application'">
              {{ record.applicationName }} <a-tag v-if="!record.applicationEnabled" color="orange">已停用</a-tag>
            </template>
            <template v-else-if="column.key === 'roles'">
              <span v-if="!record.roles.length" class="muted">未分配</span>
              <span v-for="r in record.roles" :key="r.id" class="holder-role">
                <a-tag :color="expiringSoon(r.expiresAt) ? 'orange' : r.enabled ? 'blue' : 'default'">{{ r.name }}{{ r.enabled ? '' : '（停用）' }}</a-tag>
                <span v-if="r.expiresAt || r.grantedBy" class="cell-sub">{{ r.expiresAt ? `至 ${fmt(r.expiresAt)}` : '永久' }}<template v-if="r.grantedBy"> · {{ r.grantedBy }} 授予</template></span>
              </span>
            </template>
            <template v-else-if="column.key === 'permissions'">
              <span v-if="!record.permissions.length" class="muted">无</span>
              <a-tag v-for="p in record.permissions.slice(0, 12)" :key="p" class="code-tag">{{ p }}</a-tag>
              <a-tooltip v-if="record.permissions.length > 12" :title="record.permissions.slice(12).join('、')"><a-tag>+{{ record.permissions.length - 12 }}</a-tag></a-tooltip>
            </template>
          </template>
          <template #emptyText><a-empty description="该用户还没有任何角色" :image-style="{ height: '40px' }" /></template>
        </a-table>

        <h4 class="section-title">未过期的权限上下文 <span class="muted">（有效 {{ validContexts }} 个）</span></h4>
        <a-table :columns="contextColumns" :data-source="data.contexts" :row-key="(r: ActiveContext) => `${r.applicationId}:${r.createdAt}`" size="small" :pagination="false">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
            <template v-else-if="column.key === 'expiresAt'">{{ fmt(record.expiresAt) }}</template>
            <template v-else-if="column.key === 'valid'"><a-badge :status="record.valid ? 'success' : 'default'" :text="record.valid ? '有效' : '已失效'" /></template>
          </template>
          <template #emptyText><a-empty description="当前没有未过期的上下文" :image-style="{ height: '40px' }" /></template>
        </a-table>
      </template>
    </a-spin>
  </a-drawer>
</template>
