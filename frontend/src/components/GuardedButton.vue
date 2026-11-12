<script setup lang="ts">
import { computed, useAttrs } from 'vue'
import { useHub } from '../store'
import type { AdminFunctionCode } from '../types'

defineOptions({ inheritAttrs: false })
/**
 * A button for an operation that needs a console function. Without the function (or the application) the button
 * stays visible but greyed out, and its tooltip says what is missing. allowed replaces the check when the
 * condition is wider than one function in one application; fn still names the function in the tooltip.
 */
const props = withDefaults(defineProps<{ fn: AdminFunctionCode; app?: number; allowed?: boolean; reason?: string }>(), { allowed: undefined })
const attrs = useAttrs()
const hub = useHub()
const permitted = computed(() => props.allowed ?? hub.can(props.fn, props.app))
const why = computed(() => permitted.value ? undefined : props.reason ?? (hub.denial(props.fn, props.app) || '当前账号不能管理所选软件'))
const disabled = computed(() => !permitted.value || (attrs.disabled !== undefined && attrs.disabled !== false))
</script>

<template>
  <a-tooltip :title="why">
    <a-button v-bind="attrs" :disabled="disabled">
      <template v-if="$slots.icon" #icon><slot name="icon" /></template>
      <slot />
    </a-button>
  </a-tooltip>
</template>
