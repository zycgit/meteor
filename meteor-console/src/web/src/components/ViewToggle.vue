<script setup>
defineProps({ modelValue: String });
defineEmits(['update:modelValue']);
</script>
<template>
  <button
    class="icon-button"
    :aria-label="modelValue === 'list' ? '切换为卡片' : '切换为列表'"
    :title="modelValue === 'list' ? '切换为卡片' : '切换为列表'"
    @click="$emit('update:modelValue', modelValue === 'list' ? 'cards' : 'list')"
  >
    <span
      :class="modelValue === 'list' ? 'grid-icon' : 'list-icon'"
      aria-hidden="true"
    >
      <i
        v-for="n in 4"
        :key="n"
      />
    </span>
  </button>
</template>
