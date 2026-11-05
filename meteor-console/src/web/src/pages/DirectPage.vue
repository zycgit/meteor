<script setup>
import { inject, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import Feedback from '../components/Feedback.vue';
const target = ref('');
const managementToken = ref('');
const api = inject('api');
const directCredentials = inject('directCredentials');
const navigate = inject('navigate');
const { busy, error, run } = useFeedback();
function connect() {
  run(async () => {
    const info = await api('instance', { mode: 'direct', target: target.value }, 'GET', undefined, {
      managementToken: managementToken.value
    });
    directCredentials.set(target.value, managementToken.value);
    navigate({
      view: 'instance',
      target: target.value,
      application: info.instance.applicationName,
      instanceId: info.instance.instanceId
    });
  });
}
</script>
<template>
  <div class="tabbar"><h1>直连实例</h1></div>
  <form
    class="connection-form"
    @submit.prevent="connect"
  >
    <label class="field">
      管理 RPC 地址
      <input
        v-model="target"
        required
        placeholder="rsf://host:port/default"
      />
    </label>
    <label class="field">
      管理凭据
      <input
        v-model="managementToken"
        type="password"
        autocomplete="off"
        required
        maxlength="4096"
      />
    </label>
    <Feedback :error="error" />
    <div class="actions">
      <button
        class="primary"
        :disabled="busy"
      >
        {{ busy ? '连接中…' : '连接' }}
      </button>
    </div>
  </form>
</template>
