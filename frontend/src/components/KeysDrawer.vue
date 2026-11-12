<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Modal, message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { errorText, get, send } from '../api'
import { attempt, useList } from '../composables'
import { fmt, fromNow, keyStatus } from '../labels'
import { useHub } from '../store'
import type { ApiKey, Application } from '../types'
import SecretModal from './SecretModal.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ application?: Application }>()
const emit = defineEmits<{ (e: 'changed'): void }>()
const hub = useHub()
const keys = useList<ApiKey>(() => get<ApiKey[]>(`/admin/applications/${props.application!.id}/keys`))
const canWrite = computed(() => !!props.application && hub.can('application.credentials', props.application.id))
const activeCount = computed(() => keys.rows.value.filter(k => k.status === 'ACTIVE').length)
watch(() => [open.value, props.application?.id], () => { if (open.value && props.application) keys.load() }, { immediate: true })

const columns: TableColumnsType<ApiKey> = [
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '前缀', dataIndex: 'prefix', key: 'prefix', width: 130 },
  { title: '状态', key: 'status', width: 80 },
  { title: '创建人', dataIndex: 'createdBy', width: 100, ellipsis: true },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160 },
  { title: '过期时间', dataIndex: 'expiresAt', key: 'expiresAt', width: 160 },
  { title: '最近使用', dataIndex: 'lastUsedAt', key: 'lastUsedAt', width: 120 },
  { title: '操作', key: 'actions', width: 70 },
]

const createOpen = ref(false), saving = ref(false), formRef = ref<FormInstance>()
const form = reactive<{ name: string; expiresInDays: number }>({ name: '', expiresInDays: 0 })
const expiryOptions = [{ value: 0, label: '永不过期' }, { value: 30, label: '30 天' }, { value: 90, label: '90 天' }, { value: 180, label: '180 天' }, { value: 365, label: '1 年' }]
function create() { Object.assign(form, { name: '', expiresInDays: 0 }); createOpen.value = true }

const secretOpen = ref(false), secret = ref('')
function reveal(key: string) { secret.value = key; secretOpen.value = true }
watch(secretOpen, value => { if (!value) secret.value = '' })

async function save() {
  await formRef.value?.validate()
  let key = ''
  const ok = await attempt(saving, async () => {
    const result = await send<{ apiKey: string }>('POST', `/admin/applications/${props.application!.id}/keys`,
      { name: form.name.trim(), expiresInDays: form.expiresInDays || null })
    key = result.apiKey
  }, '密钥已创建')
  if (!ok) return
  createOpen.value = false
  reveal(key)
  await keys.load()
  emit('changed')
}
async function revoke(row: ApiKey) {
  try {
    await send('DELETE', `/admin/applications/${props.application!.id}/keys/${row.id}`)
    message.success('密钥已吊销，使用它的请求将立即被拒绝')
    await keys.load()
    emit('changed')
  } catch (e) { message.error(errorText(e)) }
}
function rotate() {
  Modal.confirm({
    title: '紧急轮换全部密钥？',
    content: `“${props.application!.name}”现有的全部密钥会立即吊销并生成一个新密钥。业务系统在换上新密钥之前的所有调用都会失败。仅在密钥泄露时使用。`,
    okText: '立即轮换', okType: 'danger', cancelText: '取消',
    async onOk() {
      try {
        const result = await send<{ apiKey: string }>('POST', `/admin/applications/${props.application!.id}/rotate-key`)
        reveal(result.apiKey)
        await keys.load()
        emit('changed')
      } catch (e) { message.error(errorText(e)) }
    },
  })
}
</script>

<template>
  <a-drawer v-model:open="open" :title="`API Key：${application?.name ?? ''}`" width="980" destroy-on-close>
    <template #extra>
      <a-space v-if="canWrite">
        <a-button danger @click="rotate">紧急轮换</a-button>
        <a-button type="primary" :disabled="activeCount >= 10" @click="create"><PlusOutlined />新建密钥</a-button>
      </a-space>
    </template>
    <p class="hint">轮换时先新建密钥并在业务后端替换，确认旧密钥的“最近使用”不再更新后再吊销。每个软件最多 10 个有效密钥。</p>
    <a-table :columns="columns" :data-source="keys.rows.value" row-key="id" size="small" :loading="keys.loading.value" :pagination="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'prefix'"><code>{{ record.prefix }}…</code></template>
        <template v-else-if="column.key === 'status'"><a-tag :color="keyStatus[record.status]?.color">{{ keyStatus[record.status]?.text ?? record.status }}</a-tag></template>
        <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
        <template v-else-if="column.key === 'expiresAt'">{{ record.expiresAt ? fmt(record.expiresAt) : '永不过期' }}</template>
        <template v-else-if="column.key === 'lastUsedAt'"><a-tooltip :title="fmt(record.lastUsedAt)">{{ record.lastUsedAt ? fromNow(record.lastUsedAt) : '从未使用' }}</a-tooltip></template>
        <template v-else-if="column.key === 'actions'">
          <a-popconfirm v-if="canWrite && record.status === 'ACTIVE'" title="吊销后使用该密钥的请求立即失败，确定吊销？" ok-text="吊销" cancel-text="取消" :ok-button-props="{ danger: true }" @confirm="revoke(record)">
            <a class="danger-link">吊销</a>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="createOpen" title="新建 API Key" :confirm-loading="saving" ok-text="创建" destroy-on-close :mask-closable="false" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form">
        <a-form-item label="名称" name="name" :rules="[{ required: true, whitespace: true, max: 100, message: '请输入名称（最多 100 字）' }]" extra="用于区分环境或用途，例如：生产环境-订单服务">
          <a-input v-model:value="form.name" :maxlength="100" />
        </a-form-item>
        <a-form-item label="有效期" name="expiresInDays"><a-select v-model:value="form.expiresInDays" :options="expiryOptions" /></a-form-item>
      </a-form>
    </a-modal>
    <SecretModal v-model:open="secretOpen" title="新的 API Key" :secret="secret" />
  </a-drawer>
</template>
