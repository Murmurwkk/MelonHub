<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Modal, message, type TableColumnsType } from 'ant-design-vue'
import { errorText, get, send, type PageResult } from '../api'
import { attempt, usePaged } from '../composables'
import { fmt, reviewDecision } from '../labels'
import type { AccessReview, ReviewItem } from '../types'
import GuardedButton from './GuardedButton.vue'
import GuardedLink from './GuardedLink.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ review?: AccessReview }>()
const emit = defineEmits<{ (e: 'changed'): void }>()
// The row passed in is a snapshot from the list; the drawer keeps its own copy current after every change.
const summary = ref<AccessReview>()
watch(() => props.review, value => { summary.value = value }, { immediate: true })
const isOpen = computed(() => summary.value?.status === 'OPEN')
async function refreshSummary() {
  const id = props.review?.id
  if (!id) return summary.value
  const latest = await get<AccessReview>(`/admin/reviews/${id}`)
  if (props.review?.id === id) summary.value = latest
  return latest
}

const decision = ref<string>()
const items = usePaged<ReviewItem>((page, size) => get<PageResult<ReviewItem>>(`/admin/reviews/${props.review!.id}/items`, { decision: decision.value, page, size }))
watch(() => [open.value, props.review?.id], () => {
  if (open.value && props.review) { decision.value = undefined; items.load(true); refreshSummary().catch(() => undefined) }
  else if (!open.value) items.clear()
}, { immediate: true })
function reload() { items.load(); refreshSummary().catch(e => message.error(errorText(e))) }

const columns: TableColumnsType<ReviewItem> = [
  { title: '用户', key: 'user', width: 180, ellipsis: true },
  { title: '角色', key: 'role', width: 200, ellipsis: true },
  { title: '部门', dataIndex: 'departmentName', width: 120, ellipsis: true },
  { title: '结论', key: 'decision', width: 90 },
  { title: '备注', dataIndex: 'comment', key: 'comment', ellipsis: true },
  { title: '决定人 / 时间', key: 'decided', width: 200, ellipsis: true },
  { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]

const decideOpen = ref(false), decideBusy = ref(false), decideTarget = ref<ReviewItem | null>(null)
const decideForm = reactive<{ decision: 'KEEP' | 'REVOKE'; comment: string }>({ decision: 'KEEP', comment: '' })
function decide(row: ReviewItem, value: 'KEEP' | 'REVOKE') {
  decideTarget.value = row
  Object.assign(decideForm, { decision: value, comment: row.comment ?? '' })
  decideOpen.value = true
}
async function saveDecision() {
  const ok = await attempt(decideBusy, () =>
    send('PUT', `/admin/reviews/${props.review!.id}/items/${decideTarget.value!.id}`, { decision: decideForm.decision, comment: decideForm.comment.trim() || undefined }),
    decideForm.decision === 'KEEP' ? '已标记保留' : '已标记回收，关闭复核时实际解除授权')
  if (ok) { decideOpen.value = false; reload(); emit('changed') }
}

const closing = ref(false)
async function close() {
  closing.value = true
  let latest: AccessReview | undefined
  try { latest = await refreshSummary() } catch (e) { message.error(errorText(e)) } finally { closing.value = false }
  if (!latest) return
  if (latest.status !== 'OPEN') { message.warning('该复核已被关闭'); items.load(); emit('changed'); return }
  const target = latest
  Modal.confirm({
    title: `关闭复核“${target.name}”并应用结论？`,
    content: `标记为“回收”的条目会立即解除对应用户的角色分配；复核期间已被调整、续期或移除的分配不会回收。仍为“未决”的条目视为保留。关闭后复核不可再修改。当前：未决 ${target.pending}、保留 ${target.kept}、回收 ${target.revoked}。`,
    okText: '关闭并应用', okType: 'danger', cancelText: '取消',
    async onOk() {
      closing.value = true
      try {
        const closed = await send<AccessReview>('POST', `/admin/reviews/${target.id}/close`)
        message.success(closed?.skipped ? `复核已关闭，${closed.skipped} 条因分配已变更未回收` : '复核已关闭，回收结论已应用')
        emit('changed')
        open.value = false
      } catch (e) { message.error(errorText(e)); reload() }
      finally { closing.value = false }
    },
  })
}
</script>

<template>
  <a-drawer v-model:open="open" :title="`复核明细：${summary?.name ?? ''}`" width="1080" destroy-on-close>
    <template #extra>
      <a-space>
        <a-button @click="reload">刷新</a-button>
        <GuardedButton v-if="isOpen" fn="review.manage" :app="summary?.applicationId" :dept="summary?.departmentId" danger :loading="closing" @click="close">关闭并应用</GuardedButton>
      </a-space>
    </template>
    <template v-if="summary">
      <a-alert v-if="summary.status === 'OPEN'" type="info" show-icon class="block-gap"
               :message="`进行中：共 ${summary.items} 条授权，未决 ${summary.pending}、保留 ${summary.kept}、回收 ${summary.revoked}${summary.dueAt ? `；截止 ${fmt(summary.dueAt)}` : ''}。关闭后回收结论生效，未决视为保留。`" />
      <a-alert v-else type="success" show-icon class="block-gap"
               :message="`已关闭：保留 ${summary.kept}、回收 ${summary.revoked}${summary.skipped ? `（其中 ${summary.skipped} 条已变更，未回收）` : ''}${summary.closedBy ? `；由 ${summary.closedBy} 于 ${fmt(summary.closedAt)} 关闭` : ''}。${summary.summary ?? ''}`" />
    </template>
    <div class="toolbar">
      <a-radio-group v-model:value="decision" option-type="button" button-style="solid"
                     :options="[{ value: '', label: '全部' }, { value: 'PENDING', label: '未决' }, { value: 'KEEP', label: '保留' }, { value: 'REVOKE', label: '回收' }]"
                     @change="items.load(true)" />
    </div>
    <a-table :columns="columns" :data-source="items.rows.value" row-key="id" size="small" :loading="items.loading.value"
             :pagination="items.pagination.value" :scroll="{ x: 1100 }" @change="items.onChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'user'">
          <div class="cell-main">{{ record.userName }}</div>
          <div class="cell-sub"><code>{{ record.externalId }}</code></div>
        </template>
        <template v-else-if="column.key === 'role'">{{ record.roleName }} <code>{{ record.roleCode }}</code></template>
        <template v-else-if="column.key === 'decision'">
          <a-tag :color="reviewDecision[record.decision]?.color">{{ reviewDecision[record.decision]?.text ?? record.decision }}</a-tag>
          <a-tooltip v-if="record.applied && record.skipped" title="复核期间该分配已被调整、续期或移除，关闭时未回收"><a-tag>已跳过</a-tag></a-tooltip>
          <a-tooltip v-else-if="record.applied" title="回收已实际执行"><a-tag color="red">已回收</a-tag></a-tooltip>
        </template>
        <template v-else-if="column.key === 'comment'">{{ record.comment || '—' }}</template>
        <template v-else-if="column.key === 'decided'">
          <template v-if="record.decidedBy">{{ record.decidedBy }} · {{ fmt(record.decidedAt) }}</template>
          <span v-else class="muted">—</span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space v-if="isOpen" :size="4">
            <GuardedLink fn="review.manage" :app="summary?.applicationId" :dept="summary?.departmentId" @click="decide(record, 'KEEP')">保留</GuardedLink>
            <a-divider type="vertical" />
            <GuardedLink fn="review.manage" :app="summary?.applicationId" :dept="summary?.departmentId" class="danger-link" @click="decide(record, 'REVOKE')">回收</GuardedLink>
          </a-space>
        </template>
      </template>
      <template #emptyText><a-empty description="该筛选下没有条目" :image-style="{ height: '40px' }" /></template>
    </a-table>

    <a-modal v-model:open="decideOpen" :title="decideForm.decision === 'KEEP' ? '标记保留' : '标记回收'" :confirm-loading="decideBusy" ok-text="保存" destroy-on-close @ok="saveDecision">
      <a-alert v-if="decideForm.decision === 'REVOKE'" type="warning" show-icon class="block-gap"
               :message="`关闭复核时将解除 ${decideTarget?.userName} 的「${decideTarget?.roleName}」角色分配。`" />
      <a-form layout="vertical">
        <a-form-item label="备注（可选）"><a-textarea v-model:value="decideForm.comment" :maxlength="500" :rows="3" show-count /></a-form-item>
      </a-form>
    </a-modal>
  </a-drawer>
</template>
