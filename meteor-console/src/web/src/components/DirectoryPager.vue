<script setup>
defineProps({ count: Number, label: String, busy: Boolean, previous: Boolean, next: Boolean });
defineEmits(['previous', 'next']);
</script>
<template>
  <div class="directory-pager">
    <span class="muted">本页 {{ count }} 个{{ label }}</span>
    <div class="toolbar">
      <button
        :disabled="busy || !previous"
        @click="$emit('previous')"
      >
        上一页
      </button>
      <button
        :disabled="busy || !next"
        @click="$emit('next')"
      >
        下一页
      </button>
    </div>
  </div>
</template>
