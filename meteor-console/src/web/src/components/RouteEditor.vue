<script setup>
import { usePageTab } from '../navigation';
import RuleEditor from './RuleEditor.vue';
const props = defineProps({ scope: Object, units: Array, localUnit: String });
const { selected: level, select } = usePageTab(
  'SERVICE_ROUTE',
  ['SERVICE_ROUTE', 'METHOD_ROUTE', 'ARGS_ROUTE'],
  'level'
);
const levels = [
  ['SERVICE_ROUTE', '服务级'],
  ['METHOD_ROUTE', '方法级'],
  ['ARGS_ROUTE', '参数级']
];
</script>
<template>
  <div class="route-layout">
    <nav
      class="route-tabs"
      aria-label="路由级别"
    >
      <button
        v-for="[key, name] in levels"
        :key="key"
        :class="{ active: level === key }"
        @click="select(key)"
      >
        {{ name }}
      </button>
    </nav>
    <RuleEditor
      :key="level"
      :scope="scope"
      :type="level"
      :units="units"
      :local-unit="localUnit"
    />
  </div>
</template>
