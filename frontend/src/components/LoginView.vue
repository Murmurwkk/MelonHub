<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ApiError, errorText, send, session } from '../api'
import type { Principal } from '../types'
import ThemeToggle from './ThemeToggle.vue'

defineProps<{ notice?: string }>()
const emit = defineEmits<{ (e: 'signed-in', principal: Principal): void }>()
const form = reactive({ username: '', password: '' })
const mfaForm = reactive({ code: '' })
const loading = ref(false), error = ref(''), mfaRequired = ref(false)

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const body: Record<string, string> = { username: form.username.trim(), password: form.password }
    if (mfaRequired.value) body.mfaCode = mfaForm.code.trim()
    const result = await send<Principal & { token: string }>('POST', '/auth/login', body)
    session.save(result.token)
    form.password = ''
    emit('signed-in', {
      username: result.username, displayName: result.displayName, role: result.role, roleName: result.roleName, functions: result.functions,
      root: result.root, kind: result.kind, allDepartments: result.allDepartments, departmentIds: result.departmentIds,
      allApplications: result.allApplications, applicationIds: result.applicationIds, mfaEnabled: result.mfaEnabled,
    })
  } catch (e) {
    if (e instanceof ApiError && e.code === 'MFA_REQUIRED') {
      mfaRequired.value = true
      mfaForm.code = ''
    } else if (e instanceof ApiError && e.code === 'MFA_INVALID') {
      error.value = '口令不正确或已使用，请等认证器刷新后再输入'
      mfaForm.code = ''
    } else {
      error.value = errorText(e)
    }
  } finally {
    loading.value = false
  }
}
function back() { mfaRequired.value = false; mfaForm.code = ''; error.value = '' }
</script>

<template>
  <div class="login">
    <div class="login-theme"><ThemeToggle /></div>
    <main class="login-box">
      <img class="login-logo" src="/brand.png" alt="MelonHub" />
      <h1>{{ mfaRequired ? '两步验证' : '登录 MelonHub' }}</h1>
      <p class="login-sub">{{ mfaRequired ? `输入 ${form.username} 的身份验证器上的 6 位口令` : '使用管理员账号登录控制台' }}</p>
      <a-alert v-if="error" type="error" :message="error" show-icon class="login-alert" />
      <a-alert v-else-if="notice" type="warning" :message="notice" show-icon class="login-alert" />
      <a-form v-if="!mfaRequired" layout="vertical" :model="form" :required-mark="false" @finish="submit">
        <a-form-item label="账号" name="username" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="form.username" size="large" autocomplete="username" :maxlength="100" autofocus />
        </a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" size="large" autocomplete="current-password" :maxlength="100" />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">登录</a-button>
      </a-form>
      <a-form v-else layout="vertical" :model="mfaForm" :required-mark="false" @finish="submit">
        <a-form-item label="动态口令" name="code" :rules="[{ required: true, pattern: /^\d{6}$/, message: '请输入 6 位数字' }]">
          <a-input v-model:value="mfaForm.code" size="large" :maxlength="6" autocomplete="one-time-code" inputmode="numeric" autofocus />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">验证</a-button>
        <a-button block type="text" class="login-back" @click="back">换一个账号</a-button>
      </a-form>
      <p class="login-hint">忘记密码请联系超级管理员重置。</p>
    </main>
  </div>
</template>
