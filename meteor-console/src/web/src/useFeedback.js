import { ref } from 'vue';

export function useFeedback() {
  const busy = ref(false);
  const error = ref('');
  const success = ref('');
  async function run(action, message = '') {
    if (busy.value) {
      return;
    }
    busy.value = true;
    error.value = '';
    success.value = '';
    try {
      await action();
      if (message) success.value = message;
    } catch (failure) {
      error.value = failure.message || '操作失败';
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, success, run };
}
