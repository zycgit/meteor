<script setup>
import { usePageTab } from '../navigation';
import { computed, inject, onMounted, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import { useDirectory } from '../useDirectory';
import DirectoryPager from './DirectoryPager.vue';
import Feedback from './Feedback.vue';
import RuleEditor from './RuleEditor.vue';
import RouteEditor from './RouteEditor.vue';
import ProtocolBadges from './ProtocolBadges.vue';
import StatusBadge from './StatusBadge.vue';
const props = defineProps({ scope: Object });
const api = inject('api');
const navigate = inject('navigate');
const data = ref(null);
const { selected: tab, select } = usePageTab('instance', ['instance', 'services', 'flow', 'route']);
const units = ref([]);
const search = ref('');
const field = ref('id');
const { busy, error, success, run } = useFeedback();
const tabs = [
  ['instance', '实例'],
  ['services', '服务清单'],
  ['flow', '流量控制'],
  ['route', '路由策略']
];
const instance = computed(() => data.value?.instance);
const query = computed(() => ({ ...props.scope, scope: 'instance' }));
const directory = useDirectory(
  () => 'services',
  () => ({
    ...props.scope,
    filter: search.value,
    field: field.value
  }),
  () => tab.value === 'services'
);
const { items: filtered } = directory;
async function load() {
  try {
    const result = await api('instance', props.scope);
    // 离线后保留上次读取的身份信息，在线状态以本次查询为准。
    if (!result.online) {
      result.instance = { ...data.value?.instance, ...result.instance };
    }
    data.value = result;
  } catch (failure) {
    if (data.value) data.value.online = null;
    throw failure;
  }
}
function toggle() {
  run(async () => {
    await api('instance/block', props.scope, 'PUT', { blocked: !data.value.blocked });
    await load();
  }, '已更新');
}
function service(info) {
  navigate({
    view: 'service',
    ...props.scope,
    serviceId: info.serviceId,
    application: instance.value.applicationName
  });
}
onMounted(() =>
  run(async () => {
    await load();
    if (props.scope.mode === 'registry') {
      const names = await api('units', {
        ...props.scope,
        applicationName: instance.value.applicationName
      });
      units.value = names;
    }
  })
);
</script>
<template>
  <div class="breadcrumbs">
    <template v-if="scope.mode === 'registry'">
      <button
        class="text-button"
        @click="navigate({ view: 'applications', clusterId: scope.clusterId })"
      >
        应用列表
      </button>
      <span>/</span>
      <button
        class="text-button"
        @click="
          navigate({
            view: 'application',
            clusterId: scope.clusterId,
            application: instance?.applicationName
          })
        "
      >
        {{ instance?.applicationName || '应用' }}
      </button>
    </template>
    <button
      v-else
      class="text-button"
      @click="navigate({ view: 'direct' })"
    >
      直连实例
    </button>
    <span>/</span>
    <strong
      v-if="instance"
      class="instance-primary"
    >
      {{ instance.ipAddress || '—' }} ({{ instance.processId ?? '—' }})
    </strong>
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
      aria-label="实例详情"
    >
      <button
        v-for="[key, name] in tabs"
        :key="key"
        :class="{ active: tab === key }"
        :disabled="!data"
        @click="select(key)"
      >
        {{ name }}
      </button>
    </nav>
    <div
      class="toolbar"
      id="page-actions"
    >
      <template v-if="tab === 'services'">
        <select
          v-model="field"
          aria-label="搜索条件"
        >
          <option value="id">服务 ID</option>
          <option value="role">关系</option>
          <option value="protocol">协议</option>
        </select>
        <input
          v-model="search"
          aria-label="搜索服务"
          type="search"
          placeholder="搜索"
        />
      </template>
      <button
        v-if="tab === 'services' || tab === 'instance'"
        :disabled="busy"
        @click="tab === 'services' ? directory.refresh() : run(load)"
      >
        刷新
      </button>
    </div>
  </div>
  <Feedback
    :error="error || directory.error.value"
    :success="success"
  />
  <template v-if="data">
    <template v-if="tab === 'instance'">
      <section class="info-panel">
        <dl class="details-grid">
          <div>
            <dt>实例 IP</dt>
            <dd>{{ instance.ipAddress || '未上报' }}</dd>
          </div>
          <div>
            <dt>进程 ID</dt>
            <dd>{{ instance.processId ?? '未上报' }}</dd>
          </div>
          <div>
            <dt>实例 ID</dt>
            <dd>{{ instance.instanceId }}</dd>
          </div>
          <div>
            <dt>应用</dt>
            <dd>{{ instance.applicationName }}</dd>
          </div>
          <div>
            <dt>单元</dt>
            <dd>{{ instance.unitName || '—' }}</dd>
          </div>
          <div>
            <dt>在线状态</dt>
            <dd>
              <StatusBadge
                v-if="data.online !== null"
                :enabled="data.online"
                on="在线"
                off="离线"
              />
              <span v-else>未知</span>
            </dd>
          </div>
        </dl>
        <div
          v-if="scope.mode === 'registry' || data.online"
          class="instance-control"
        >
          <span>阻断调用</span>
          <label class="switch-label">
            <span>{{ data.blocked ? '已阻断' : '已放行' }}</span>
            <button
              role="switch"
              aria-label="阻断调用"
              class="switch"
              :class="{ checked: data.blocked }"
              :aria-checked="data.blocked"
              :disabled="busy || (scope.mode === 'direct' && data.online !== true)"
              @click="toggle"
            >
              <span />
            </button>
          </label>
        </div>
      </section>
      <section
        v-if="data.online"
        class="endpoint-section"
      >
        <h2>调用端点</h2>
        <div class="table-scroll">
          <table class="endpoint-table">
            <thead>
              <tr>
                <th>协议</th>
                <th>监听地址</th>
                <th>管理 RPC 地址</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="protocol in [
                  ...new Set([
                    ...Object.keys(instance.listenAddresses),
                    ...Object.keys(instance.managementAddress)
                  ])
                ]"
                :key="protocol"
              >
                <td><ProtocolBadges :protocols="[protocol]" /></td>
                <td>
                  <code>{{ instance.listenAddresses[protocol] || '仅出站' }}</code>
                </td>
                <td>
                  <code>{{ instance.managementAddress[protocol] || '—' }}</code>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p
          v-if="!Object.keys(instance.listenAddresses).length"
          class="empty"
        >
          此实例仅发起出站调用
        </p>
      </section>
    </template>
    <template v-else-if="tab === 'services'">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>服务 ID</th>
              <th>关系</th>
              <th>支持协议</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="info in filtered"
              :key="info.serviceId"
            >
              <td>
                <button
                  class="text-button"
                  @click="service(info)"
                >
                  <code>{{ info.serviceId }}</code>
                </button>
              </td>
              <td>{{ info.role === 'Provider' ? '发布' : '订阅' }}</td>
              <td><ProtocolBadges :protocols="info.protocols" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p
        v-if="!filtered.length"
        class="empty"
      >
        没有匹配的服务
      </p>
      <DirectoryPager
        :count="filtered.length"
        label="服务"
        :busy="directory.busy.value"
        :previous="directory.history.value.length > 0"
        :next="!!directory.nextCursor.value"
        @previous="directory.previous"
        @next="directory.next"
      />
    </template>
    <RuleEditor
      v-else-if="tab === 'flow'"
      :scope="query"
      type="FLOW_CONTROL"
      :units="units"
      :local-unit="instance.unitName"
    />
    <RouteEditor
      v-else
      :scope="query"
    />
  </template>
  <p
    v-else-if="busy"
    class="empty"
  >
    正在读取…
  </p>
</template>
