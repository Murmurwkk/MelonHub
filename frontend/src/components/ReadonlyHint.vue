<script setup lang="ts">
import { useAuthorizationScope } from '../authorizationScope'
/** editable: whether the operator may change what this page shows in the selected domain. */
defineProps<{ editable: boolean }>()
const scope = useAuthorizationScope()
</script>

<template>
  <a-alert v-if="scope.hasDomain && !editable" type="info" show-icon class="block-gap"
           :message="`你在软件“${scope.application?.name}”中只能查看此页内容；修改需要管理员角色具有相应功能，且账号可管理该软件。`" />
</template>
