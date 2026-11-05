import { inject, onBeforeUnmount, provide } from 'vue';

export function provideNavigationGuard() {
  const drafts = new Set();
  const dirty = () => [...drafts].some((check) => check());
  const canLeave = () => !dirty() || window.confirm('有未保存的修改，确定离开并丢弃修改吗？');
  provide('canLeave', canLeave);
  provide('registerDraft', (check) => {
    drafts.add(check);
    return () => drafts.delete(check);
  });
  const unload = (event) => {
    if (dirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  };
  window.addEventListener('beforeunload', unload);
  onBeforeUnmount(() => window.removeEventListener('beforeunload', unload));
  return canLeave;
}

export function trackDraft(check) {
  const unregister = inject('registerDraft')(check);
  onBeforeUnmount(unregister);
}
