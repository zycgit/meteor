<script setup>
defineProps({ instance: Object, showInstanceId: Boolean });
</script>

<template>
  <span class="instance-identity">
    <span class="instance-primary">
      {{ instance?.ipAddress || '—' }} · PID {{ instance?.processId ?? '—' }}
    </span>
    <span
      v-if="showInstanceId"
      class="instance-id"
      :title="instance?.instanceId"
    >
      {{ instance?.instanceId || '—' }}
    </span>
  </span>
</template>
