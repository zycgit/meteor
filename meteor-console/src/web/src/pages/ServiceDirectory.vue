<script setup>
import { computed, inject, onMounted, ref } from 'vue';
import WorkspaceTabs from '../components/WorkspaceTabs.vue';
import ProtocolBadges from '../components/ProtocolBadges.vue';
import StatusBadge from '../components/StatusBadge.vue';
import Feedback from '../components/Feedback.vue';
import { useDirectory } from '../useDirectory';
import DirectoryPager from '../components/DirectoryPager.vue';
const props = defineProps({ scope: Object });
const api = inject('api');
const navigate = inject('navigate');
const search = ref('');
const directory = useDirectory(
  () => 'services',
  () => ({ ...props.scope, filter: search.value })
);
const { items: filtered, busy, error, refresh } = directory;
function open(service) {
  navigate({ view: 'service', clusterId: props.scope.clusterId, serviceId: service.info.serviceId });
}
</script>
<template>
  <WorkspaceTabs active="services">
    <input
      v-model="search"
      type="search"
      aria-label="搜索服务 ID"
      placeholder="搜索服务 ID"
    />
    <button
      :disabled="busy"
      @click="refresh"
    >
      刷新
    </button>
  </WorkspaceTabs>
  <Feedback :error="error" />
  <div class="table-scroll">
    <table>
      <thead>
        <tr>
          <th>服务 ID</th>
          <th>支持协议</th>
          <th>提供者</th>
          <th>消费者</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="service in filtered"
          :key="service.info.serviceId"
        >
          <td>
            <button
              class="text-button"
              @click="open(service)"
            >
              <code>{{ service.info.serviceId }}</code>
            </button>
          </td>
          <td><ProtocolBadges :protocols="service.info.protocols" /></td>
          <td>{{ service.info.providerCount }}</td>
          <td>{{ service.info.consumerCount }}</td>
          <td><StatusBadge :enabled="service.enabled" /></td>
        </tr>
      </tbody>
    </table>
  </div>
  <p
    v-if="!filtered.length"
    class="empty"
  >
    {{ busy ? '正在读取…' : '没有匹配的服务' }}
  </p>
  <DirectoryPager
    :count="filtered.length"
    label="服务"
    :busy="busy"
    :previous="directory.history.value.length > 0"
    :next="!!directory.nextCursor.value"
    @previous="directory.previous"
    @next="directory.next"
  />
</template>
