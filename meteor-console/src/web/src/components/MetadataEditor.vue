<script setup>
import { computed, inject, onMounted, ref } from 'vue';
import { useFeedback } from '../useFeedback';
import { trackDraft } from '../useNavigationGuard';
import Feedback from './Feedback.vue';

const props = defineProps({ scope: { type: Object, required: true }, toolbar: { default: '#page-actions' } });
const api = inject('api');
const metadata = ref(null);
const draft = ref(null);
const { busy, error, success, run } = useFeedback();
trackDraft(() => draft.value !== null);

const rows = computed(() => {
  const entries = Object.entries(metadata.value ?? {});
  entries.sort(([left], [right]) => left.localeCompare(right));
  const result = entries.map(([key, value]) => ({ key, value, isNew: false }));
  if (draft.value?.original === null) {
    result.push({ key: '', value: '', isNew: true });
  }
  return result;
});

async function load() {
  metadata.value = await api('metadata', props.scope);
}

function edit(row) {
  error.value = '';
  success.value = '';
  draft.value = {
    original: row ? row.key : null,
    key: row ? row.key : '',
    value: row ? JSON.stringify(row.value, null, 2) : '""'
  };
}

function isEditing(row) {
  return draft.value && (row.isNew || draft.value.original === row.key);
}

function cancel() {
  draft.value = null;
  error.value = '';
}

function save() {
  run(async () => {
    const creating = draft.value.original === null;
    const key = creating ? draft.value.key.trim() : draft.value.original;
    if (!key) {
      throw new Error('请填写元信息键。');
    }
    if (creating && Object.hasOwn(metadata.value, key)) {
      throw new Error('该键已存在，请编辑对应行。');
    }
    if (creating && Object.keys(metadata.value).length >= 1000) {
      throw new Error('最多保存 1000 个元信息键。');
    }

    let value;
    try {
      value = JSON.parse(draft.value.value);
    } catch {
      throw new Error('请输入有效 JSON 值，字符串需要双引号。');
    }
    if (value === null) {
      throw new Error('值不能为 null，请使用删除操作。');
    }

    // 每次只提交当前行，其他键仍由后端保留。
    await api('metadata', props.scope, 'PUT', { values: { [key]: value } });
    draft.value = null;
    await load();
  }, '已保存');
}

function remove(key) {
  if (!window.confirm(`删除元信息“${key}”？`)) {
    return;
  }
  run(async () => {
    await api('metadata', props.scope, 'DELETE', { keys: [key] });
    await load();
  }, '已保存');
}

onMounted(() => run(load));
</script>

<template>
  <section class="metadata-panel">
    <Teleport :to="toolbar">
      <button
        :disabled="busy || !!draft"
        @click="run(load)"
      >
        刷新
      </button>
      <button
        class="primary"
        :disabled="busy || !!draft || !metadata"
        @click="edit(null)"
      >
        新增
      </button>
    </Teleport>
    <Feedback
      :error="error"
      :success="success"
    />
    <div class="table-scroll">
      <table
        class="metadata-table"
        aria-label="元信息"
      >
        <colgroup>
          <col class="metadata-key" />
          <col />
          <col class="metadata-actions" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">键</th>
            <th scope="col">值（JSON）</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.isNew ? 'draft' : 'key:' + row.key"
          >
            <td>
              <input
                v-if="row.isNew"
                v-model="draft.key"
                aria-label="元信息键"
                placeholder="例如 region"
                :disabled="busy"
              />
              <code v-else>{{ row.key }}</code>
            </td>
            <td>
              <textarea
                v-if="isEditing(row)"
                v-model="draft.value"
                aria-label="元信息值（JSON）"
                rows="2"
                spellcheck="false"
                :disabled="busy"
              />
              <code
                v-else
                class="metadata-value"
              >
                {{ JSON.stringify(row.value) }}
              </code>
            </td>
            <td>
              <div
                v-if="isEditing(row)"
                class="actions"
              >
                <button
                  class="text-button"
                  :disabled="busy"
                  @click="save"
                >
                  保存
                </button>
                <button
                  class="text-button"
                  :disabled="busy"
                  @click="cancel"
                >
                  取消
                </button>
              </div>
              <div
                v-else
                class="actions"
              >
                <button
                  class="text-button"
                  :disabled="busy || !!draft"
                  @click="edit(row)"
                >
                  编辑
                </button>
                <button
                  class="text-button"
                  :disabled="busy || !!draft"
                  @click="remove(row.key)"
                >
                  删除
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td
              colspan="3"
              class="empty"
            >
              {{ busy ? '正在读取…' : metadata ? '暂无元信息' : '元信息尚未加载' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
