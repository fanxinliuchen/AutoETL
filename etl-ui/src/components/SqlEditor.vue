<template>
  <div class="sql-editor-container">
    <div class="editor-toolbar">
      <div class="var-chips">
        <span class="label">插入动态变量:</span>
        <a-tag color="blue" class="clickable-chip" @click="insertVar('${today}')">${today}</a-tag>
        <a-tag color="blue" class="clickable-chip" @click="insertVar('${yesterday}')">${yesterday}</a-tag>
        <a-tag color="blue" class="clickable-chip" @click="insertVar('${now}')">${now}</a-tag>
        <a-tag color="purple" class="clickable-chip" @click="insertVar('${stage_table}')">${stage_table}</a-tag>
        <a-tag color="purple" class="clickable-chip" @click="insertVar('${target_table}')">${target_table}</a-tag>
        <a-tag color="cyan" class="clickable-chip" @click="insertVar('${batch_id}')">${batch_id}</a-tag>
      </div>
      <div class="toolbar-actions">
        <a-button size="small" :loading="checking" @click="checkSafety">
          <safety-certificate-outlined /> SQL 语法与安全校验
        </a-button>
      </div>
    </div>

    <div class="editor-main">
      <textarea
        ref="textareaRef"
        v-model="sqlValue"
        class="code-textarea"
        :placeholder="placeholderText"
        rows="10"
        spellcheck="false"
        @input="onInput"
      ></textarea>
    </div>

    <div v-if="validationMsg" class="validation-bar" :class="{ 'is-error': isError }">
      <check-circle-outlined v-if="!isError" />
      <close-circle-outlined v-else />
      <span>{{ validationMsg }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  SafetyCertificateOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { validateSql } from '../api/pipeline'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
  }>(),
  {
    modelValue: '',
    placeholder: '请输入 SQL 处理脚本，支持多条 SQL 语句以分号分隔...'
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', val: string): void
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const sqlValue = ref<string>(props.modelValue)
const checking = ref(false)
const validationMsg = ref('')
const isError = ref(false)

const placeholderText = props.placeholder

watch(
  () => props.modelValue,
  (val) => {
    sqlValue.value = val
  }
)

const onInput = () => {
  emit('update:modelValue', sqlValue.value)
  validationMsg.value = ''
}

const insertVar = (text: string) => {
  const el = textareaRef.value
  if (!el) return
  const start = el.selectionStart
  const end = el.selectionEnd
  const val = sqlValue.value
  sqlValue.value = val.substring(0, start) + text + val.substring(end)
  emit('update:modelValue', sqlValue.value)
  setTimeout(() => {
    el.focus()
    el.setSelectionRange(start + text.length, start + text.length)
  }, 0)
}

const checkSafety = async () => {
  if (!sqlValue.value.trim()) {
    message.warning('请先输入 SQL 脚本')
    return
  }
  checking.value = true
  validationMsg.value = ''
  try {
    const res = await validateSql(sqlValue.value)
    isError.value = false
    validationMsg.value = res || 'SQL AST 语法校验通过，无高危拦截语句。'
  } catch (e: any) {
    isError.value = true
    validationMsg.value = e.message || 'SQL 语法或安全白名单校验未通过'
  } finally {
    checking.value = false
  }
}
</script>

<style scoped>
.sql-editor-container {
  display: flex;
  flex-direction: column;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  overflow: hidden;
  background: #1e1e1e;
}
.editor-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: #252526;
  border-bottom: 1px solid #333333;
}
.var-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.var-chips .label {
  font-size: 12px;
  color: #cccccc;
  margin-right: 4px;
}
.clickable-chip {
  cursor: pointer;
  user-select: none;
  font-family: monospace;
}
.editor-main {
  width: 100%;
}
.code-textarea {
  width: 100%;
  box-sizing: border-box;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 12px;
  border: none;
  outline: none;
  resize: vertical;
}
.validation-bar {
  padding: 6px 12px;
  background: #1f3322;
  color: #52c41a;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  border-top: 1px solid #333333;
}
.validation-bar.is-error {
  background: #3a1d1d;
  color: #ff4d4f;
}
</style>
