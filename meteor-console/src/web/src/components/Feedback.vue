<script setup>
defineProps({ error: String, success: String });
</script>
<template>
  <p
    v-if="error"
    class="notice error"
    role="alert"
  >
    {{ error }}
  </p>
  <p
    v-if="success"
    class="notice success"
    role="status"
  >
    {{ success }}
  </p>
</template>
