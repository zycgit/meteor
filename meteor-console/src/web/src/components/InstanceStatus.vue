<script setup>
import { computed } from 'vue';

const props = defineProps({ online: Boolean, blocked: Boolean });
const status = computed(() => {
  if (!props.online) return { label: '离线', color: 'negative' };
  if (props.blocked) return { label: '阻断', color: 'warning' };
  return { label: '在线', color: 'positive' };
});
</script>

<template>
  <span
    class="status"
    :class="status.color"
  >
    {{ status.label }}
  </span>
</template>
