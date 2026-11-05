<script setup>
import { computed, inject, onMounted, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import { trackDraft } from '../useNavigationGuard';
import Feedback from './Feedback.vue';
const props = defineProps({ scope: Object, type: String, units: { default: () => [] }, localUnit: String });
const api = inject('api');
const { busy, error, success, run } = useFeedback();
const loaded = ref(null);
const rule = ref('');
const original = ref('');
const targetUnit = ref('ALL');
const dirty = computed(() => rule.value !== original.value);
trackDraft(() => dirty.value);
const query = computed(() => ({ ...props.scope, type: props.type }));
const writeQuery = computed(() => ({
  ...query.value,
  targetUnit: canTarget.value ? targetUnit.value : undefined,
  localUnit: props.localUnit
}));
const canTarget = computed(() => {
  if (props.scope.mode !== 'registry') return false;
  if (props.scope.scope === 'instance') return true;
  return props.scope.scope === 'service' && !props.scope.instanceId;
});
async function load() {
  loaded.value = await api('rule', query.value);
  rule.value = loaded.value.rule ?? '';
  original.value = rule.value;
}
function save() {
  run(async () => {
    const result = await api('rule', writeQuery.value, 'PUT', { rule: rule.value });
    original.value = rule.value;
    success.value = result.affected ? `已保存到 ${result.affected} 个实例` : '已保存';
  });
}
function deleteRule() {
  if (!window.confirm('删除当前规则？将清除目标范围内已保存的规则正文。')) return;
  run(async () => {
    await api('rule', writeQuery.value, 'DELETE');
    if (targetUnit.value === 'ALL') await load();
    else {
      rule.value = '';
      original.value = '';
    }
    success.value = '已删除';
  });
}
function toggle() {
  run(async () => {
    await api('rule/enabled', writeQuery.value, 'PUT', { enabled: !loaded.value.enabled });
    loaded.value.enabled = !loaded.value.enabled;
    success.value = loaded.value.enabled ? '已启用' : '已禁用';
  });
}
onMounted(() => run(load));
</script>
<template>
  <section class="rule-editor">
    <Feedback
      :error="error"
      :success="success"
    />
    <div
      v-if="!loaded"
      class="empty"
    >
      <span v-if="busy">正在读取…</span>
      <button
        v-else
        @click="run(load)"
      >
        重新读取
      </button>
    </div>
    <template v-else>
      <textarea
        v-model="rule"
        aria-label="规则正文"
        class="code-editor"
        spellcheck="false"
        :disabled="busy"
        :placeholder="
          type === 'FLOW_CONTROL'
            ? '<controlSet/>'
            : `<route>\n  <return value=&quot;addresses&quot;/>\n</route>`
        "
      />
      <div class="actions governance-actions">
        <button
          class="primary"
          :disabled="busy"
          @click="save"
        >
          保存
        </button>
        <button
          :disabled="busy"
          @click="deleteRule"
        >
          删除
        </button>
        <label
          v-if="loaded.switchSupported"
          class="switch-label"
        >
          <span>{{ loaded.enabled ? '已启用' : '已禁用' }}</span>
          <button
            class="switch"
            role="switch"
            aria-label="启用规则"
            :aria-checked="loaded.enabled"
            :class="{ checked: loaded.enabled }"
            :disabled="busy"
            @click="toggle"
          >
            <span />
          </button>
        </label>
        <span
          v-if="dirty"
          class="muted small"
        >
          未保存
        </span>
        <select
          v-if="canTarget"
          v-model="targetUnit"
          aria-label="目标单元"
          class="push-right"
          :disabled="busy"
        >
          <option value="ALL">全部</option>
          <option
            v-if="localUnit"
            value="LOCAL"
          >
            本单元
          </option>
          <option
            v-for="unit in units"
            :key="unit"
            :value="unit"
          >
            {{ unit }}
          </option>
        </select>
      </div>
    </template>
  </section>
</template>
