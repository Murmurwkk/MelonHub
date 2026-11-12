<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { PlusOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { errorText, get, send, type PageResult } from '../api'
import { attempt, useList, usePaged } from '../composables'
import { deliveryStatus, fmt, fromNow, webhookEventLabels, webhookEventOptions } from '../labels'
import { useHub } from '../store'
import type { Application, Webhook, WebhookDelivery } from '../types'
import SecretModal from './SecretModal.vue'
import GuardedButton from './GuardedButton.vue'
import GuardedLink from './GuardedLink.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ application?: Application }>()
const hub = useHub()
const hooks = useList<Webhook>(() => get<Webhook[]>(`/admin/applications/${props.application!.id}/webhooks`))
const canWrite = computed(() => !!props.application && hub.can('application.credentials', props.application.id))
watch(() => [open.value, props.application?.id], () => { if (open.value && props.application) hooks.load() }, { immediate: true })

const columns: TableColumnsType<Webhook> = [
  { title: '名称', dataIndex: 'name', width: 140, ellipsis: true },
  { title: '回调地址', dataIndex: 'url', key: 'url', ellipsis: true },
  { title: '订阅事件', key: 'events', width: 200, ellipsis: true },
  { title: '状态', key: 'enabled', width: 70 },
  { title: '待投递', dataIndex: 'pending', width: 76, align: 'right' },
  { title: '失败', dataIndex: 'failed', key: 'failed', width: 64, align: 'right' },
  { title: '最近送达', dataIndex: 'lastDeliveredAt', key: 'lastDeliveredAt', width: 110 },
  { title: '操作', key: 'actions', width: 170 },
]

const editOpen = ref(false), saving = ref(false), editing = ref<Webhook | null>(null), formRef = ref<FormInstance>()
const form = reactive<{ name: string; url: string; secretMode: 'auto' | 'manual' | 'keep'; secret: string; events: string[]; enabled: boolean }>({
  name: '', url: '', secretMode: 'auto', secret: '', events: ['*'], enabled: true,
})
const rules = {
  name: [{ required: true, whitespace: true, max: 100, message: '请输入名称（最多 100 字）' }],
  url: [{ required: true, pattern: /^https?:\/\/\S+$/, message: '请输入 http(s) 回调地址' }],
  // Same rule as the server: 16-100 printable ASCII characters that neither start nor end with a space.
  secret: [{ validator: (_: unknown, value: string) => form.secretMode !== 'manual' || /^[!-~][ -~]{14,98}[!-~]$/.test(value ?? '')
    ? Promise.resolve() : Promise.reject('16-100 位可见 ASCII 字符，首尾不能是空格') }],
  events: [{ required: true, type: 'array', min: 1, message: '至少选择一个事件' }],
}
function edit(row?: Webhook) {
  editing.value = row ?? null
  Object.assign(form, {
    name: row?.name ?? '', url: row?.url ?? '', secretMode: row ? 'keep' : 'auto', secret: '',
    events: [...(row?.events ?? ['*'])], enabled: row?.enabled ?? true,
  })
  editOpen.value = true
}
function onEventsChange(values: unknown) {
  const list = (values as (string | number)[]).map(String)
  // “全部事件”与其他事件互斥，以最后选择的一项为准。
  const lastPicked = list[list.length - 1]
  form.events = lastPicked === '*' ? ['*'] : list.filter(v => v !== '*')
}

const secretOpen = ref(false), secret = ref('')
watch(secretOpen, value => { if (!value) secret.value = '' })

async function save() {
  await formRef.value?.validate()
  const body = {
    name: form.name.trim(), url: form.url.trim(), events: form.events, enabled: form.enabled,
    ...(form.secretMode === 'manual' ? { secret: form.secret } : {}),
  }
  let createdSecret = ''
  const ok = await attempt(saving, async () => {
    if (editing.value) await send('PUT', `/admin/webhooks/${editing.value.id}`, body)
    else createdSecret = (await send<{ id: number; secret: string }>('POST', `/admin/applications/${props.application!.id}/webhooks`, body)).secret
  }, editing.value ? 'Webhook 已更新' : 'Webhook 已创建')
  if (!ok) return
  editOpen.value = false
  if (createdSecret) { secret.value = createdSecret; secretOpen.value = true }
  await hooks.load()
}
async function remove(row: Webhook) {
  try { await send('DELETE', `/admin/webhooks/${row.id}`); message.success('Webhook 已删除'); await hooks.load() }
  catch (e) { message.error(errorText(e)) }
}
async function test(row: Webhook) {
  try { await send('POST', `/admin/webhooks/${row.id}/test`); message.success('测试事件已入队，稍后在投递记录中查看结果') }
  catch (e) { message.error(errorText(e)) }
}

const deliveriesOpen = ref(false), deliveriesHook = ref<Webhook>()
const deliveries = usePaged<WebhookDelivery>((page, size) => get<PageResult<WebhookDelivery>>(`/admin/webhooks/${deliveriesHook.value!.id}/deliveries`, { page, size }))
function showDeliveries(row: Webhook) { deliveriesHook.value = row; deliveriesOpen.value = true }
watch(deliveriesOpen, value => { if (value) deliveries.load(true); else deliveries.clear() })
const deliveryColumns: TableColumnsType<WebhookDelivery> = [
  { title: '事件', dataIndex: 'eventType', key: 'eventType', width: 170, ellipsis: true },
  { title: '状态', key: 'status', width: 84 },
  { title: '尝试', dataIndex: 'attempts', width: 60, align: 'right' },
  { title: 'HTTP', dataIndex: 'lastStatus', key: 'lastStatus', width: 64, align: 'right' },
  { title: '错误', dataIndex: 'lastError', key: 'lastError', ellipsis: true },
  { title: '下次重试', dataIndex: 'nextAttemptAt', key: 'nextAttemptAt', width: 150 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 150 },
  { title: '送达时间', dataIndex: 'deliveredAt', key: 'deliveredAt', width: 150 },
]
</script>

<template>
  <a-drawer v-model:open="open" :title="`Webhook：${application?.name ?? ''}`" width="1080" destroy-on-close>
    <template #extra>
      <GuardedButton fn="application.credentials" :allowed="canWrite" type="primary" @click="edit()"><PlusOutlined />新建 Webhook</GuardedButton>
    </template>
    <a-table :columns="columns" :data-source="hooks.rows.value" row-key="id" size="small" :loading="hooks.loading.value" :pagination="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'url'"><code>{{ record.url }}</code></template>
        <template v-else-if="column.key === 'events'">
          <a-tag v-for="e in record.events.slice(0, 3)" :key="e" :color="e === '*' ? 'purple' : 'blue'">{{ webhookEventLabels[e] ?? e }}</a-tag>
          <a-tooltip v-if="record.events.length > 3" :title="record.events.map((e: string) => webhookEventLabels[e] ?? e).join('、')"><a-tag>+{{ record.events.length - 3 }}</a-tag></a-tooltip>
        </template>
        <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
        <template v-else-if="column.key === 'failed'"><span :class="{ 'warn-text': record.failed }">{{ record.failed }}</span></template>
        <template v-else-if="column.key === 'lastDeliveredAt'"><a-tooltip :title="fmt(record.lastDeliveredAt)">{{ record.lastDeliveredAt ? fromNow(record.lastDeliveredAt) : '从未送达' }}</a-tooltip></template>
        <template v-else-if="column.key === 'actions'">
          <a-space :size="4">
            <a @click="showDeliveries(record)">投递记录</a>
            <a-divider type="vertical" />
            <GuardedLink fn="application.credentials" :allowed="canWrite" @click="test(record)">测试</GuardedLink>
            <a-divider type="vertical" />
            <GuardedLink fn="application.credentials" :allowed="canWrite" @click="edit(record)">编辑</GuardedLink>
            <a-divider type="vertical" />
            <a-popconfirm :disabled="!canWrite" title="删除后不再接收该软件的事件通知，确定删除？" ok-text="删除" cancel-text="取消" :ok-button-props="{ danger: true }" @confirm="remove(record)">
              <GuardedLink fn="application.credentials" :allowed="canWrite" class="danger-link">删除</GuardedLink>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
      <template #emptyText><a-empty description="还没有 Webhook" :image-style="{ height: '40px' }" /></template>
    </a-table>

    <a-modal v-model:open="editOpen" :title="editing ? `编辑 Webhook：${editing.name}` : '新建 Webhook'" :confirm-loading="saving" ok-text="保存"
             destroy-on-close :mask-closable="false" width="560px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-form-item label="名称" name="name"><a-input v-model:value="form.name" :maxlength="100" placeholder="例如：订单服务-生产" /></a-form-item>
        <a-form-item label="回调地址" name="url" extra="平台以 POST JSON 推送事件，并携带签名请求头供业务侧校验">
          <a-input v-model:value="form.url" :maxlength="500" placeholder="https://example.com/hooks/melonhub" />
        </a-form-item>
        <a-form-item label="订阅事件" name="events">
          <a-select :value="form.events" mode="multiple" :options="webhookEventOptions" option-filter-prop="label" placeholder="选择事件类型" @change="onEventsChange" />
        </a-form-item>
        <a-form-item v-if="!editing" label="签名密钥" name="secret" extra="用于校验推送请求的签名。选择自动生成时，密钥只在创建成功后显示一次；手动填写需 16-100 位可见 ASCII 字符。">
          <a-radio-group v-model:value="form.secretMode" :options="[{ value: 'auto', label: '自动生成' }, { value: 'manual', label: '手动填写' }]" option-type="button" button-style="solid" />
          <a-input v-if="form.secretMode === 'manual'" v-model:value="form.secret" class="mfa-confirm" :maxlength="100" placeholder="自定义签名密钥" autocomplete="off" />
        </a-form-item>
        <a-form-item v-else label="签名密钥" name="secret" extra="选择保持不变则沿用现有密钥；新密钥需 16-100 位可见 ASCII 字符。">
          <a-radio-group v-model:value="form.secretMode" :options="[{ value: 'keep', label: '保持不变' }, { value: 'manual', label: '更换密钥' }]" option-type="button" button-style="solid" />
          <a-input v-if="form.secretMode === 'manual'" v-model:value="form.secret" class="mfa-confirm" :maxlength="100" placeholder="新的签名密钥" autocomplete="off" />
        </a-form-item>
        <a-form-item label="启用" name="enabled"><a-switch v-model:checked="form.enabled" /></a-form-item>
      </a-form>
    </a-modal>

    <a-drawer v-model:open="deliveriesOpen" :title="`投递记录：${deliveriesHook?.name ?? ''}`" width="980" destroy-on-close>
      <template #extra>
        <a-space>
          <GuardedButton fn="application.credentials" :allowed="canWrite" size="small" @click="deliveriesHook && test(deliveriesHook)"><ThunderboltOutlined />发送测试事件</GuardedButton>
          <a-button size="small" @click="deliveries.load()">刷新</a-button>
        </a-space>
      </template>
      <a-table :columns="deliveryColumns" :data-source="deliveries.rows.value" row-key="id" size="small" :loading="deliveries.loading.value"
               :pagination="deliveries.pagination.value" :scroll="{ x: 1000 }" @change="deliveries.onChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'eventType'">
            <a-tooltip :title="record.eventId"><code>{{ record.eventType }}</code></a-tooltip>
            <div class="cell-sub">{{ webhookEventLabels[record.eventType] ?? '' }}</div>
          </template>
          <template v-else-if="column.key === 'status'"><a-tag :color="deliveryStatus[record.status]?.color">{{ deliveryStatus[record.status]?.text ?? record.status }}</a-tag></template>
          <template v-else-if="column.key === 'lastStatus'">{{ record.lastStatus ?? '—' }}</template>
          <template v-else-if="column.key === 'lastError'"><a-tooltip v-if="record.lastError" :title="record.lastError"><span class="warn-text">{{ record.lastError }}</span></a-tooltip><span v-else class="muted">—</span></template>
          <template v-else-if="column.key === 'nextAttemptAt'">{{ record.status === 'PENDING' || record.status === 'SENDING' ? fmt(record.nextAttemptAt) : '—' }}</template>
          <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
          <template v-else-if="column.key === 'deliveredAt'">{{ record.deliveredAt ? fmt(record.deliveredAt) : '—' }}</template>
        </template>
        <template #emptyText><a-empty description="还没有投递记录" :image-style="{ height: '40px' }" /></template>
      </a-table>
    </a-drawer>

    <SecretModal v-model:open="secretOpen" title="Webhook 签名密钥" :secret="secret" />
  </a-drawer>
</template>

<style scoped>
.mfa-confirm { margin-top: 8px; }
</style>
