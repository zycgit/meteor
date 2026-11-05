import { computed, inject, onBeforeUnmount, ref } from 'vue';

// 页面状态进入 URL，刷新、前进和后退均保留当前治理范围。
export function useNavigation(canLeave) {
  const read = () => Object.fromEntries(new URLSearchParams(location.hash.slice(1)));
  const route = ref(read());
  let acceptedHash = location.hash;
  const apply = () => {
    if (!canLeave()) {
      history.replaceState(null, '', acceptedHash || '#view=clusters');
      return;
    }
    acceptedHash = location.hash;
    route.value = read();
  };
  window.addEventListener('hashchange', apply);
  onBeforeUnmount(() => window.removeEventListener('hashchange', apply));
  function navigate(next, replace = false) {
    if (!canLeave()) return;
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(next)) {
      if (value !== undefined && value !== null && value !== '') params.set(key, value);
    }
    const hash = `#${params}`;
    history[replace ? 'replaceState' : 'pushState'](null, '', hash);
    acceptedHash = hash;
    route.value = { ...next };
  }
  return { route, navigate };
}

export function usePageTab(defaultTab, allowed, parameter = 'tab') {
  const route = inject('route');
  const navigate = inject('navigate');
  const selected = computed(() =>
    allowed.includes(route.value[parameter]) ? route.value[parameter] : defaultTab
  );
  const select = (value) => navigate({ ...route.value, [parameter]: value });
  return { selected, select };
}
