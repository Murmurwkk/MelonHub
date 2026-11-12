<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, type FormInstance, type TableColumnsType } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, get, send } from '../api'
import { attempt, useList } from '../composables'
import { fmt, roleColor } from '../labels'
import { useHub } from '../store'
import type { AdminFunctionCode, AdminFunctionDefinition, AdminRoleDefinition } from '../types'
import PageHead from '../components/PageHead.vue'

const hub = useHub()
const roles = useList<AdminRoleDefinition>(() => get<AdminRoleDefinition[]>('/admin/admin-roles'))
const functions = useList<AdminFunctionDefinition>(() => get<AdminFunctionDefinition[]>('/admin/admin-functions'))
onMounted(() => { roles.load(); functions.load() })
const canEdit = computed(() => hub.can('role.manage'))
const functionName = (code: string) => functions.rows.value.find(f => f.code === code)?.name ?? code
/** Functions grouped as the console menu groups them. */
const categories = computed(() => {
  const groups = new Map<string, AdminFunctionDefinition[]>()
  for (const fn of functions.rows.value) groups.set(fn.category, [...(groups.get(fn.category) ?? []), fn])
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})

const columns: TableColumnsType<AdminRoleDefinition> = [
  { title: '角色', key: 'name', width: 200, ellipsis: true },
  { title: '编码', dataIndex: 'code', key: 'code', width: 160 },
  { title: '功能', key: 'functions' },
  { title: '使用账号', dataIndex: 'accountCount', width: 100, align: 'right' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]

const open = ref(false), saving = ref(false), editing = ref<AdminRoleDefinition | null>(null), formRef = ref<FormInstance>()
const form = reactive<{ code: string; name: string; description: string; functions: AdminFunctionCode[] }>({ code: '', name: '', description: '', functions: [] })
const readonly = computed(() => !canEdit.value || !!editing.value?.builtin)
const rules = computed(() => ({
  code: editing.value ? [] : [{ required: true, pattern: /^[A-Z][A-Z0-9_]{1,31}$/, message: '2-32 位大写字母、数字或下划线，以字母开头' }],
  name: [{ required: true, whitespace: true, max: 100, message: '请输入角色名称（最多 100 字）' }],
  description: [{ max: 500, message: '最多 500 字' }],
  functions: [{ required: true, type: 'array', min: 1, message: '至少选择一项功能' }],
}))
function edit(row?: AdminRoleDefinition) {
  editing.value = row ?? null
  Object.assign(form, { code: row?.code ?? '', name: row?.name ?? '', description: row?.description ?? '', functions: [...(row?.functions ?? [])] })
  open.value = true
}
async function save() {
  if (readonly.value) { open.value = false; return }
  await formRef.value?.validate()
  const body = { name: form.name.trim(), description: form.description.trim(), functions: form.functions }
  const ok = await attempt(saving, () => editing.value
    ? send('PUT', `/admin/admin-roles/${editing.value.code}`, body)
    : send('POST', '/admin/admin-roles', { code: form.code.trim(), ...body }),
  editing.value ? '角色已更新，已登录的管理员下一次操作起按新功能生效' : '角色已创建')
  if (ok) { open.value = false; roles.load() }
}
async function remove(row: AdminRoleDefinition) {
  try { await send('DELETE', `/admin/admin-roles/${row.code}`); message.success('角色已删除'); roles.load() }
  catch (e) { message.error(errorText(e)) }
}
</script>

<template>
  <div class="page">
    <PageHead title="管理员角色" description="管理员角色是一组控制台功能；管理员账号选择一个角色，并单独设定可管理的软件。内置角色不可修改。">
      <a-button :loading="roles.loading.value" @click="roles.load()"><ReloadOutlined />刷新</a-button>
      <a-button v-if="canEdit" type="primary" @click="edit()"><PlusOutlined />新建角色</a-button>
    </PageHead>
    <a-card :bordered="false">
      <a-table :columns="columns" :data-source="roles.rows.value" row-key="code" size="small" :loading="roles.loading.value" :scroll="{ x: 1000 }" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <a-tag :color="roleColor(record.code)">{{ record.name }}</a-tag><a-tag v-if="record.builtin" class="title-tag">内置</a-tag>
            <div v-if="record.description" class="muted">{{ record.description }}</div>
          </template>
          <template v-else-if="column.key === 'code'"><code>{{ record.code }}</code></template>
          <template v-else-if="column.key === 'functions'">
            <a-tag v-for="fn in record.functions" :key="fn">{{ functionName(fn) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
          <template v-else-if="column.key === 'actions'">
            <a-space :size="4">
              <a @click="edit(record)">{{ canEdit && !record.builtin ? '编辑' : '查看' }}</a>
              <template v-if="canEdit && !record.builtin">
                <a-divider type="vertical" />
                <a-popconfirm title="确定删除该角色？" ok-text="删除" cancel-text="取消" :ok-button-props="{ danger: true }" :disabled="record.accountCount > 0" @confirm="remove(record)">
                  <a-tooltip :title="record.accountCount > 0 ? '仍有管理员账号使用该角色' : undefined">
                    <a :class="record.accountCount > 0 ? 'muted' : 'danger-link'">删除</a>
                  </a-tooltip>
                </a-popconfirm>
              </template>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="open" :title="editing ? `${readonly ? '查看' : '编辑'}角色：${editing.name}` : '新建角色'" :confirm-loading="saving"
             :ok-text="readonly ? '关闭' : '保存'" destroy-on-close :mask-closable="false" width="640px" @ok="save">
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12"><a-form-item label="编码" name="code"><a-input v-model:value="form.code" :disabled="!!editing" :maxlength="32" placeholder="例如：HELPDESK" /></a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="名称" name="name"><a-input v-model:value="form.name" :disabled="readonly" :maxlength="100" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="说明" name="description"><a-input v-model:value="form.description" :disabled="readonly" :maxlength="500" /></a-form-item>
        <a-form-item label="功能" name="functions" extra="只能授予你自己的角色拥有的功能。">
          <a-checkbox-group v-model:value="form.functions" :disabled="readonly" style="width: 100%">
            <div v-for="group in categories" :key="group.name" class="block-gap">
              <div class="muted">{{ group.name }}</div>
              <a-row>
                <a-col v-for="fn in group.items" :key="fn.code" :xs="24" :sm="12">
                  <a-checkbox :value="fn.code" :disabled="!hub.can(fn.code)">{{ fn.name }}</a-checkbox>
                </a-col>
              </a-row>
            </div>
          </a-checkbox-group>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
