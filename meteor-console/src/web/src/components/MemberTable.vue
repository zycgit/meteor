<script setup>
import { computed, inject, ref } from 'vue';
import { useDirectory } from '../useDirectory';
import DirectoryPager from './DirectoryPager.vue';
import Feedback from './Feedback.vue';
import ProtocolBadges from './ProtocolBadges.vue';
import StatusBadge from './StatusBadge.vue';
const props = defineProps({ scope: Object, role: String, protocols: Array });
const navigate = inject('navigate');
const field = ref('instance');
const search = ref('');
const protocol = ref('');
const directory = useDirectory(
  () => 'service/members',
  () => ({
    ...props.scope,
    role: props.role,
    field: field.value,
    filter: search.value,
    protocol: protocol.value
  })
);
const { items: members, busy, error, refresh } = directory;
const filtered = members;
function memberProtocols(member) {
  if (props.role === 'consumers') return member.usedProtocols || [];
  return member.endpoints.map((endpoint) => endpoint.protocolName);
}
const protocols = computed(() =>
  [
    ...new Set([
      ...(props.protocols || []),
      ...members.value.flatMap(memberProtocols),
      ...(protocol.value ? [protocol.value] : [])
    ])
  ].sort()
);
function openService(instance) {
  navigate({
    view: 'service',
    serviceId: props.scope.serviceId,
    clusterId: props.scope.clusterId,
    instanceId: instance.instanceId,
    application: instance.applicationName,
    target: props.scope.target
  });
}
</script>
<template>
  <Teleport to="#page-actions">
    <select
      v-model="field"
      aria-label="搜索条件"
    >
      <option value="instance">实例（IP / 进程 ID / 实例 ID）</option>
      <option value="application">应用</option>
      <option value="unit">单元</option>
      <option value="address">地址</option>
    </select>
    <input
      v-model="search"
      type="search"
      aria-label="搜索成员"
      placeholder="搜索"
    />
    <select
      v-model="protocol"
      aria-label="筛选协议"
    >
      <option value="">全部协议</option>
      <option
        v-for="item in protocols"
        :key="item"
      >
        {{ item }}
      </option>
    </select>
    <button
      :disabled="busy"
      @click="refresh"
    >
      刷新
    </button>
  </Teleport>
  <Feedback :error="error" />
  <div class="table-scroll">
    <table class="member-table">
      <thead>
        <tr>
          <th>实例</th>
          <th>进程</th>
          <th>应用</th>
          <th>单元</th>
          <th>{{ role === 'consumers' ? '使用协议' : '支持协议' }}</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="member in filtered"
          :key="member.instance.instanceId"
        >
          <td>
            <button
              class="text-button dark instance-link"
              @click="openService(member.instance)"
            >
              <span class="instance-primary">{{ member.instance.ipAddress || '—' }}</span>
            </button>
          </td>
          <td class="instance-pid">{{ member.instance.processId ?? '—' }}</td>
          <td>{{ member.instance.applicationName }}</td>
          <td>{{ member.instance.unitName || '—' }}</td>
          <td><ProtocolBadges :protocols="memberProtocols(member)" /></td>
          <td><StatusBadge :enabled="member.enabled" /></td>
        </tr>
      </tbody>
    </table>
  </div>
  <p
    v-if="!filtered.length"
    class="empty"
  >
    {{ busy ? '正在读取…' : '没有匹配的成员' }}
  </p>
  <DirectoryPager
    :count="filtered.length"
    :label="role === 'providers' ? '提供者' : '消费者'"
    :busy="busy"
    :previous="directory.history.value.length > 0"
    :next="!!directory.nextCursor.value"
    @previous="directory.previous"
    @next="directory.next"
  />
</template>
