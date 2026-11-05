<script setup>
import { usePageTab } from '../navigation';
import { computed, inject, onMounted, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import Feedback from './Feedback.vue';
import MemberTable from './MemberTable.vue';
import RuleEditor from './RuleEditor.vue';
import RouteEditor from './RouteEditor.vue';
import MetadataEditor from './MetadataEditor.vue';
import StatusBadge from './StatusBadge.vue';
const props = defineProps({ scope: Object });
const api = inject('api');
const navigate = inject('navigate');
const localUnit = inject('localUnit');
const data = ref(null);
const units = ref([]);
const scopedToInstance = computed(() => !!props.scope.instanceId || props.scope.mode === 'direct');
const instance = ref(null);
const allowedTabs = scopedToInstance.value
  ? ['info', 'flow', 'route', 'metadata']
  : ['info', 'providers', 'consumers', 'flow', 'route', 'metadata'];
const { selected: tab, select } = usePageTab('info', allowedTabs);
const targetUnit = ref('ALL');
const { busy, error, success, run } = useFeedback();
const tabs = [
  ['info', '服务信息'],
  ['providers', '提供者'],
  ['consumers', '消费者'],
  ['flow', '流量控制'],
  ['route', '路由策略'],
  ['metadata', '元信息']
].filter(([key]) => allowedTabs.includes(key));
const query = computed(() => ({ ...props.scope, scope: 'service' }));
const info = computed(() => data.value?.info);
const serviceRole = computed(() => ({ Provider: '提供者', Consumer: '消费者' })[info.value?.role] || '—');
async function load() {
  const [service, snapshot] = await Promise.all([
    api('service', props.scope),
    scopedToInstance.value ? api('instance', props.scope) : Promise.resolve(null)
  ]);
  data.value = service;
  instance.value = snapshot?.instance;
}
function toggle() {
  run(async () => {
    const enabled = !data.value.enabled;
    await api(
      'service/enabled',
      {
        ...props.scope,
        targetUnit: scopedToInstance.value ? undefined : targetUnit.value,
        localUnit: localUnit.value
      },
      'PUT',
      { enabled }
    );
    data.value.enabled = enabled;
    if (targetUnit.value === 'ALL') await load();
  }, '已更新');
}
onMounted(() =>
  run(async () => {
    await load();
    if (props.scope.mode === 'registry' && !scopedToInstance.value) {
      units.value = await api('units', props.scope);
    }
  })
);
</script>
<template>
  <div class="breadcrumbs">
    <template v-if="scopedToInstance">
      <template v-if="scope.mode === 'registry'">
        <button
          class="text-button"
          @click="navigate({ view: 'applications', clusterId: scope.clusterId })"
        >
          应用列表
        </button>
        <span>/</span>
        <button
          v-if="instance?.applicationName"
          class="text-button"
          @click="
            navigate({
              view: 'application',
              clusterId: scope.clusterId,
              application: instance.applicationName
            })
          "
        >
          {{ instance.applicationName }}
        </button>
        <span v-if="instance?.applicationName">/</span>
      </template>
      <template v-else>
        <button
          class="text-button"
          @click="navigate({ view: 'direct' })"
        >
          直连实例
        </button>
        <span>/</span>
      </template>
      <button
        class="text-button instance-primary"
        :title="scope.instanceId"
        @click="
          navigate({
            view: 'instance',
            clusterId: scope.clusterId,
            target: scope.target,
            instanceId: scope.instanceId || instance?.instanceId,
            application: instance?.applicationName,
            tab: 'services'
          })
        "
      >
        {{ instance?.ipAddress || '—' }} ({{ instance?.processId ?? '—' }})
      </button>
      <span>/</span>
    </template>
    <template v-else>
      <button
        class="text-button"
        @click="navigate({ view: 'services', clusterId: scope.clusterId })"
      >
        服务目录
      </button>
      <span>/</span>
    </template>
    <strong :title="scope.serviceId">{{ info?.serviceId || scope.serviceId }}</strong>
    <span
      v-if="scope.mode === 'direct'"
      class="badge"
    >
      直连
    </span>
  </div>
  <div class="tabbar detail-tabbar">
    <nav
      class="tabs"
      aria-label="服务详情"
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
      id="page-actions"
    >
      <button
        v-if="tab === 'info'"
        :disabled="busy"
        @click="run(load)"
      >
        刷新
      </button>
    </div>
  </div>
  <Feedback
    :error="error"
    :success="success"
  />
  <template v-if="data">
    <template v-if="tab === 'info'">
      <section class="info-panel service-info-panel">
        <dl class="details-grid service-info-fields">
          <div>
            <dt>服务 ID</dt>
            <dd>{{ info.serviceId }}</dd>
          </div>
          <div>
            <dt>分组</dt>
            <dd>{{ info.group || '—' }}</dd>
          </div>
          <div>
            <dt>名称</dt>
            <dd>{{ info.name }}</dd>
          </div>
          <div>
            <dt>版本</dt>
            <dd>{{ info.version || '—' }}</dd>
          </div>
          <div v-if="scopedToInstance && data.description?.javaType">
            <dt>接口</dt>
            <dd>
              <code>{{ data.description.javaType }}</code>
            </dd>
          </div>
          <div v-if="scopedToInstance">
            <dt>身份</dt>
            <dd>{{ serviceRole }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd><StatusBadge :enabled="data.enabled" /></dd>
          </div>
        </dl>
      </section>
      <section
        class="info-panel service-control-panel"
        aria-label="服务启用操作"
      >
        <div class="actions governance-actions">
          <label class="switch-label">
            <span>{{ data.enabled ? '已启用' : '已禁用' }}</span>
            <button
              class="switch"
              role="switch"
              aria-label="启用服务"
              :class="{ checked: data.enabled }"
              :aria-checked="data.enabled"
              :disabled="busy"
              @click="toggle"
            >
              <span />
            </button>
          </label>
          <label
            v-if="scope.mode === 'registry' && !scope.instanceId"
            class="service-target-scope"
          >
            <span>目标范围</span>
            <select
              v-model="targetUnit"
              aria-label="目标范围"
            >
              <option value="ALL">全部</option>
              <option value="LOCAL">本单元</option>
              <option
                v-for="unit in units"
                :key="unit"
              >
                {{ unit }}
              </option>
            </select>
          </label>
        </div>
      </section>
    </template>
    <MemberTable
      v-else-if="tab === 'providers' || tab === 'consumers'"
      :key="tab"
      :scope="query"
      :role="tab"
      :protocols="data?.info.protocols || []"
    />
    <MetadataEditor
      v-else-if="tab === 'metadata'"
      :scope="query"
    />
    <RuleEditor
      v-else-if="tab === 'flow'"
      type="FLOW_CONTROL"
      :scope="query"
      :units="units"
      :local-unit="localUnit"
    />
    <RouteEditor
      v-else
      :scope="query"
      :units="units"
      :local-unit="localUnit"
    />
  </template>
  <p
    v-else-if="busy"
    class="empty"
  >
    正在读取…
  </p>
</template>
