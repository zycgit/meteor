import { inject, onMounted, onBeforeUnmount, ref, watch } from 'vue';

// 游标随搜索条件重置；旧请求不能覆盖用户刚切换到的新查询。
export function useDirectory(endpoint, parameters, enabled = () => true) {
  const api = inject('api');
  const items = ref([]);
  const busy = ref(false);
  const error = ref('');
  const cursor = ref(null);
  const nextCursor = ref(null);
  const history = ref([]);
  let generation = 0;
  let timer;

  async function load(next = null) {
    if (!enabled()) return false;
    const current = ++generation;
    busy.value = true;
    error.value = '';
    try {
      const page = await api(endpoint(), { ...parameters(), cursor: next || undefined, limit: 100 });
      if (current !== generation) return false;
      items.value = page.items;
      cursor.value = next;
      nextCursor.value = page.nextCursor;
      return true;
    } catch (failure) {
      if (current === generation) {
        items.value = [];
        nextCursor.value = null;
        error.value = failure.message || '读取目录失败';
      }
      return false;
    } finally {
      if (current === generation) busy.value = false;
    }
  }

  async function refresh() {
    history.value = [];
    return load();
  }
  async function next() {
    if (busy.value || !nextCursor.value) return;
    const previous = cursor.value;
    if (await load(nextCursor.value)) history.value.push(previous);
  }
  async function previous() {
    if (busy.value || !history.value.length) return;
    const previous = history.value.at(-1);
    if (await load(previous)) history.value.pop();
  }
  watch(
    () => [parameters(), enabled()],
    () => {
      generation++;
      busy.value = false;
      nextCursor.value = null;
      clearTimeout(timer);
      timer = setTimeout(refresh, 200);
    },
    { deep: true }
  );
  onMounted(refresh);
  onBeforeUnmount(() => {
    generation++;
    clearTimeout(timer);
  });
  return { items, busy, error, history, nextCursor, refresh, next, previous };
}
