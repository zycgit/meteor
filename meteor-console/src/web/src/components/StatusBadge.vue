<script setup>
defineProps({ enabled: Boolean, on: { default: '启用' }, off: { default: '禁用' } });
</script>
<template>
  <span
    class="status"
    :class="enabled ? 'positive' : 'negative'"
  >
    {{ enabled ? on : off }}
  </span>
</template>
