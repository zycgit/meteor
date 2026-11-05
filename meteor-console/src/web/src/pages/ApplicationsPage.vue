<script setup>
import { inject, onMounted, ref } from 'vue';
import { useDirectory } from '../useDirectory';
import DirectoryPager from '../components/DirectoryPager.vue';
import { instanceRole } from '../display';
import Feedback from '../components/Feedback.vue';
import WorkspaceTabs from '../components/WorkspaceTabs.vue';
import ViewToggle from '../components/ViewToggle.vue';
import ProtocolBadges from '../components/ProtocolBadges.vue';
import InstanceStatus from '../components/InstanceStatus.vue';
const props = defineProps({ scope: Object, application: String });
const api = inject('api');
const navigate = inject('navigate');
const search = ref('');
const unit = ref('');
const display = ref(
  localStorage.getItem(props.application ? 'meteor.instances.view' : 'meteor.applications.view') ||
    (props.application ? 'list' : 'cards')
);
const units = ref([]);
const directory = useDirectory(
  () => (props.application ? 'instances' : 'applications'),
  () => ({ ...props.scope, applicationName: props.application, filter: search.value, unitName: unit.value })
);
const { items, busy, error, refresh } = directory;
const applications = items;
const filtered = items;
async function loadUnits() {
  if (props.application) {
    try {
      units.value = await api('units', { ...props.scope, applicationName: props.application });
    } catch (failure) {
      error.value = failure.message;
    }
  }
}
function viewMode(value) {
  display.value = value;
  localStorage.setItem(props.application ? 'meteor.instances.view' : 'meteor.applications.view', value);
}
function openApp(name) {
  navigate({ view: 'application', clusterId: props.scope.clusterId, application: name });
}
function openInstance(instance) {
  navigate({
    view: 'instance',
    clusterId: props.scope.clusterId,
    instanceId: instance.instanceId,
    application: props.application
  });
}
onMounted(loadUnits);
</script>
<template>
  <div
    v-if="application"
    class="breadcrumbs"
  >
    <button
      class="text-button"
      @click="navigate({ view: 'applications', clusterId: scope.clusterId })"
    >
      应用列表
    </button>
    <span>/</span>
    <strong>{{ application }}</strong>
  </div>
  <component
    :is="application ? 'div' : WorkspaceTabs"
    :class="{ tabbar: application }"
    active="applications"
  >
    <template v-if="application">
      <h1>实例</h1>
      <div class="toolbar">
        <select
          v-model="unit"
          aria-label="筛选单元"
        >
          <option value="">全部单元</option>
          <option
            v-for="item in units"
            :key="item"
          >
            {{ item }}
          </option>
        </select>
        <input
          v-model="search"
          type="search"
          aria-label="搜索 IP、进程 ID 或实例 ID"
          placeholder="搜索 IP、进程 ID 或实例 ID"
        />
        <button
          :disabled="busy"
          @click="refresh"
        >
          刷新
        </button>
        <ViewToggle
          :model-value="display"
          @update:model-value="viewMode"
        />
      </div>
    </template>
    <template v-else>
      <input
        v-model="search"
        type="search"
        aria-label="搜索应用名称"
        placeholder="搜索应用名称"
      />
      <button
        :disabled="busy"
        @click="refresh"
      >
        刷新
      </button>
      <ViewToggle
        :model-value="display"
        @update:model-value="viewMode"
      />
    </template>
  </component>
  <Feedback :error="error" />
  <template v-if="!application">
    <div
      v-if="display === 'cards'"
      class="cards"
    >
      <button
        type="button"
        class="card application-card"
        v-for="app in applications"
        :key="app.name"
        :aria-label="app.name"
        @click="openApp(app.name)"
      >
        <strong
          class="application-title"
          :title="app.name"
        >
          {{ app.name }}
        </strong>
      </button>
    </div>
    <div
      v-else
      class="table-scroll"
    >
      <table>
        <thead>
          <tr>
            <th>应用名称</th>
            <th class="operation">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="app in applications"
            :key="app.name"
          >
            <td>
              <button
                class="text-button dark"
                @click="openApp(app.name)"
              >
                {{ app.name }}
              </button>
            </td>
            <td>
              <button
                class="text-button"
                @click="openApp(app.name)"
              >
                详情
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p
      v-if="!applications.length"
      class="empty"
    >
      {{ busy ? '正在读取…' : '没有匹配的应用' }}
    </p>
  </template>
  <template v-else>
    <div
      v-if="display === 'cards'"
      class="cards"
    >
      <button
        type="button"
        class="card instance-card"
        v-for="instance in filtered"
        :key="instance.instanceId"
        @click="openInstance(instance)"
      >
        <span class="card-heading">
          <span class="instance-identity">
            <strong class="instance-primary">{{ instance.ipAddress || '—' }}</strong>
            <span class="instance-process">PID {{ instance.processId ?? '—' }}</span>
          </span>
          <InstanceStatus
            :online="instance.online"
            :blocked="instance.block"
          />
        </span>
        <span class="card-detail">
          <span class="muted">单元</span>
          <span>{{ instance.unitName || '—' }}</span>
        </span>
        <span class="card-actions">
          <ProtocolBadges
            v-if="Object.keys(instance.listenAddresses || {}).length"
            :protocols="Object.keys(instance.listenAddresses || {})"
          />
          <span
            v-if="instance.roles?.length"
            class="badge instance-role"
          >
            {{ instanceRole(instance) }}
          </span>
        </span>
      </button>
    </div>
    <div
      v-else
      class="table-scroll"
    >
      <table>
        <thead>
          <tr>
            <th>实例</th>
            <th>PID</th>
            <th>状态</th>
            <th>单元</th>
            <th>协议入口</th>
            <th>身份</th>
            <th class="operation">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="instance in filtered"
            :key="instance.instanceId"
          >
            <td>
              <span class="instance-identity">
                <button
                  class="text-button dark instance-primary"
                  @click="openInstance(instance)"
                >
                  {{ instance.ipAddress || '—' }}
                </button>
              </span>
            </td>
            <td class="instance-pid">{{ instance.processId ?? '—' }}</td>
            <td>
              <!-- 实例档案持久保留，当前会话决定在线状态。 -->
              <InstanceStatus
                :online="instance.online"
                :blocked="instance.block"
              />
            </td>
            <td>{{ instance.unitName || '—' }}</td>
            <td><ProtocolBadges :protocols="Object.keys(instance.listenAddresses || {})" /></td>
            <td>
              <span
                v-if="instance.roles?.length"
                class="badge instance-role"
              >
                {{ instanceRole(instance) }}
              </span>
            </td>
            <td>
              <button
                class="text-button"
                @click="openInstance(instance)"
              >
                详情
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p
      v-if="!filtered.length"
      class="empty"
    >
      {{ busy ? '正在读取…' : '没有匹配的实例' }}
    </p>
  </template>
  <DirectoryPager
    :count="items.length"
    :label="application ? '实例' : '应用'"
    :busy="busy"
    :previous="directory.history.value.length > 0"
    :next="!!directory.nextCursor.value"
    @previous="directory.previous"
    @next="directory.next"
  />
</template>
