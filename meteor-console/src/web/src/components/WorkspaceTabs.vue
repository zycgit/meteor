<script setup>
import { inject } from 'vue';
const route = inject('route');
const navigate = inject('navigate');
defineProps({ active: String });
const tabs = [
  ['applications', '应用列表'],
  ['services', '服务目录'],
  ['global', '全局配置']
];
</script>
<template>
  <div class="tabbar">
    <nav
      class="tabs"
      aria-label="服务治理"
    >
      <button
        v-for="[key, title] in tabs"
        :key="key"
        :class="{ active: active === key }"
        @click="navigate({ view: key, clusterId: route.clusterId })"
      >
        {{ title }}
      </button>
    </nav>
    <div
      class="toolbar"
      id="page-actions"
    >
      <slot />
    </div>
  </div>
</template>
