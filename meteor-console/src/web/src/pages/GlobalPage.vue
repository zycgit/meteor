<script setup>
import { usePageTab } from '../navigation';
import { inject, ref } from 'vue';
import WorkspaceTabs from '../components/WorkspaceTabs.vue';
import RuleEditor from '../components/RuleEditor.vue';
import RouteEditor from '../components/RouteEditor.vue';
import MetadataEditor from '../components/MetadataEditor.vue';
const props = defineProps({ scope: Object });
const { selected: tab, select } = usePageTab('flow', ['flow', 'route', 'metadata']);
const tabs = [
  ['flow', '流量控制'],
  ['route', '路由策略'],
  ['metadata', '元信息']
];
</script>
<template>
  <WorkspaceTabs active="global" />
  <div class="tabbar detail-tabbar">
    <nav
      class="tabs"
      aria-label="全局配置"
    >
      <button
        v-for="[key, name] in tabs"
        :key="key"
        :class="{ active: tab === key }"
        @click="select(key)"
      >
        {{ name }}
      </button>
    </nav>
    <div
      class="toolbar"
      id="global-actions"
    />
  </div>
  <MetadataEditor
    toolbar="#global-actions"
    v-if="tab === 'metadata'"
    :scope="{ ...scope, scope: 'cluster' }"
  />
  <RuleEditor
    v-else-if="tab === 'flow'"
    :scope="{ ...scope, scope: 'cluster' }"
    type="FLOW_CONTROL"
  />
  <RouteEditor
    v-else
    :scope="{ ...scope, scope: 'cluster' }"
  />
</template>
