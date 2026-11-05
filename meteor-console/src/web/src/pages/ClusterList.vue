<script setup>
import { computed, inject, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import Feedback from '../components/Feedback.vue';
const props = defineProps({ clusters: Array });
const emit = defineEmits(['changed']);
const navigate = inject('navigate');
const api = inject('api');
const query = ref('');
const removal = ref(null);
const { busy, error, run } = useFeedback();
const types = { remote: 'Remote', nacos: 'Nacos', jdbc: 'JDBC', zookeeper: 'ZooKeeper' };
const filtered = computed(() =>
  props.clusters.filter((c) => `${c.name} ${c.address}`.toLowerCase().includes(query.value.toLowerCase()))
);
function remove() {
  run(async () => {
    await api('clusters', { id: removal.value.id }, 'DELETE');
    removal.value = null;
    emit('changed');
  });
}
</script>
<template>
  <div class="tabbar">
    <h1>集群连接</h1>
    <div class="toolbar">
      <input
        v-if="clusters.length"
        v-model="query"
        aria-label="搜索集群"
        placeholder="搜索集群"
        type="search"
      />
      <button
        class="primary"
        @click="navigate({ view: 'connection' })"
      >
        连接注册中心
      </button>
    </div>
  </div>
  <Feedback :error="error" />
  <div
    v-if="!clusters.length"
    class="empty-state"
  >
    <span
      class="empty-illustration"
      aria-hidden="true"
    >
      ◇
    </span>
    <h2>连接到注册中心</h2>
    <p>选择注册中心，开始管理服务。</p>
    <button
      class="primary"
      @click="navigate({ view: 'connection' })"
    >
      连接注册中心
    </button>
  </div>
  <div
    v-else
    class="cards cluster-cards"
  >
    <article
      v-for="cluster in filtered"
      :key="cluster.id"
      class="card"
    >
      <div class="card-heading">
        <strong>{{ cluster.name }}</strong>
        <span class="muted">{{ types[cluster.type] }}</span>
      </div>
      <p
        class="address"
        :title="cluster.address"
      >
        {{ cluster.address }}
      </p>
      <p class="small muted">
        {{
          cluster.type === 'nacos'
            ? [cluster.namespace || 'public', cluster.group || 'meteor'].join(' / ')
            : cluster.type === 'zookeeper'
              ? cluster.root || '/meteor'
              : cluster.database
        }}
      </p>
      <div class="card-actions">
        <button
          class="text-button"
          @click="navigate({ view: 'connection', id: cluster.id })"
        >
          编辑
        </button>
        <button
          class="text-button"
          @click="removal = cluster"
        >
          移除
        </button>
        <button
          class="text-button push-right"
          @click="navigate({ view: 'applications', clusterId: cluster.id })"
        >
          进入
        </button>
      </div>
    </article>
  </div>
  <p
    class="total"
    v-if="clusters.length"
  >
    共 {{ filtered.length }} 个集群
  </p>
  <div
    v-if="clusters.length && !filtered.length"
    class="empty"
  >
    没有匹配的集群
  </div>
  <div
    v-if="removal"
    class="dialog-backdrop"
    @click.self="removal = null"
    @keydown.esc="removal = null"
  >
    <section
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="remove-title"
    >
      <h2 id="remove-title">移除连接</h2>
      <p>移除“{{ removal.name }}”的本地连接？注册中心数据不受影响。</p>
      <div class="actions">
        <button
          :disabled="busy"
          @click="removal = null"
        >
          取消
        </button>
        <button
          class="danger"
          :disabled="busy"
          @click="remove"
        >
          移除
        </button>
      </div>
    </section>
  </div>
</template>
