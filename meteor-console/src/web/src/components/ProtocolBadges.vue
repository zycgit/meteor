<script setup>
defineProps({ protocols: { default: () => [] } });
</script>
<template>
  <span class="protocols">
    <span
      v-for="protocol in [...new Set(protocols)]"
      :key="protocol"
      class="badge"
    >
      {{ protocol }}
    </span>
    <span
      v-if="!protocols.length"
      class="muted"
    >
      —
    </span>
  </span>
</template>
