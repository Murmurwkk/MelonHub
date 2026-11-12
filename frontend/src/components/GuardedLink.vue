<script setup lang="ts">
import { computed } from 'vue'
import { useHub } from '../store'
import type { AdminFunctionCode } from '../types'

defineOptions({ inheritAttrs: false })
/** A table action link that needs a console function; without it the text is greyed out and explains why on hover. */
const props = withDefaults(defineProps<{ fn: AdminFunctionCode; app?: number; allowed?: boolean; reason?: string }>(), { allowed: undefined })
const hub = useHub()
const permitted = computed(() => props.allowed ?? hub.can(props.fn, props.app))
const why = computed(() => permitted.value ? undefined : props.reason ?? (hub.denial(props.fn, props.app) || '当前账号不能管理所选软件'))
</script>

<template>
  <a v-if="permitted" v-bind="$attrs"><slot /></a>
  <a-tooltip v-else :title="why"><span class="link-disabled"><slot /></span></a-tooltip>
</template>
