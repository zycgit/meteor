<script setup>
import { inject, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import Feedback from '../components/Feedback.vue';
const props = defineProps({ checking: Boolean, connectionError: String });
const emit = defineEmits(['login']);
const api = inject('api');
const username = ref('');
const password = ref('');
const { busy, error, run } = useFeedback();
function login() {
  if (props.checking) return;
  run(async () => {
    const session = await api('auth/login', {}, 'POST', {
      username: username.value,
      password: password.value
    });
    password.value = '';
    emit('login', session);
  });
}
</script>
<template>
  <main class="login-page">
    <a
      class="login-brand"
      href="#"
    >
      Meteor
    </a>
    <form
      class="login-form"
      @submit.prevent="login"
    >
      <h1>登录控制台</h1>
      <input
        v-model="username"
        aria-label="账号"
        placeholder="账号"
        autocomplete="username"
        required
        maxlength="100"
        autofocus
      />
      <input
        v-model="password"
        aria-label="密码"
        placeholder="密码"
        type="password"
        autocomplete="current-password"
        required
        maxlength="1024"
      />
      <Feedback :error="error || connectionError" />
      <button
        class="primary"
        :disabled="busy || checking"
      >
        {{ checking ? '检查登录…' : busy ? '登录中…' : '登录' }}
      </button>
    </form>
  </main>
</template>
