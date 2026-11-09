<template>
  <a-drawer
    v-model:open="visible"
    :title="`执行日志追踪: ${pipelineInfo.pipelineName || ''}`"
    width="800"
    :destroy-on-close="true"
  >
    <div class="log-drawer-body">
      <div class="instance-list-pane">
        <div class="pane-header">
          <span>历史运行批次</span>
          <a-button type="link" size="small" @click="fetchInstances">刷新</a-button>
        </div>
        <a-list
          :data-source="instances"
          :loading="loading"
          size="small"
        >
          <template #renderItem="{ item }">
            <a-list-item
              class="instance-item"
              :class="{ selected: selectedInstance?.id === item.id }"
              @click="selectInstance(item)"
            >
              <div class="item-header">
                <a-tag :color="getStatusColor(item.taskStatus)">
                  {{ item.taskStatus }}
                </a-tag>
                <span class="item-time">{{ formatTime(item.startTime) }}</span>
              </div>
              <div class="item-metrics">
                <span>读取: {{ item.readCount || 0 }} 行</span>
                <span>写入: {{ item.writeCount || 0 }} 行</span>
                <span>耗时: {{ item.durationMs ? (item.durationMs / 1000).toFixed(2) + 's' : '-' }}</span>
              </div>
            </a-list-item>
          </template>
        </a-list>
      </div>

      <div class="log-detail-pane">
        <div v-if="selectedInstance" class="detail-content">
          <div class="detail-header">
            <h4>批次号: {{ selectedInstance.id }}</h4>
            <div class="tags">
              <a-tag color="blue">触发: {{ selectedInstance.triggerType }}</a-tag>
              <a-tag :color="getStatusColor(selectedInstance.taskStatus)">
                {{ selectedInstance.taskStatus }}
              </a-tag>
            </div>
          </div>

          <div v-if="selectedInstance.errorMessage" class="error-box">
            <strong>异常报错摘要:</strong>
            <pre>{{ selectedInstance.errorMessage }}</pre>
          </div>

          <div class="log-console-container">
            <div class="console-title">全链路执行控制台追踪日志:</div>
            <pre class="log-console">{{ selectedInstance.logDetail || '暂无详细日志输出' }}</pre>
          </div>
        </div>
        <a-empty v-else description="请选择左侧批次查看详细日志" />
      </div>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import dayjs from 'dayjs'
import { getInstancePage } from '../api/instance'
import type { EtlPipeline, EtlTaskInstance } from '../types'

const visible = ref(false)
const loading = ref(false)
const pipelineInfo = ref<Partial<EtlPipeline>>({})
const instances = ref<EtlTaskInstance[]>([])
const selectedInstance = ref<EtlTaskInstance | null>(null)

const open = (pipeline: EtlPipeline) => {
  pipelineInfo.value = pipeline
  visible.value = true
  fetchInstances()
}

const fetchInstances = async () => {
  if (!pipelineInfo.value.id) return
  loading.value = true
  try {
    const res = await getInstancePage({
      current: 1,
      size: 20,
      query: {
        filters: [{ fieldName: 'pipelineId', condition: 'eq', value: String(pipelineInfo.value.id) }],
        softs: [{ fieldName: 'startTime', orderCode: 'desc' }]
      }
    })
    instances.value = res.list || []
    if (instances.value.length > 0) {
      selectedInstance.value = instances.value[0]
    } else {
      selectedInstance.value = null
    }
  } finally {
    loading.value = false
  }
}

const selectInstance = (item: EtlTaskInstance) => {
  selectedInstance.value = item
}

const getStatusColor = (status: string) => {
  switch (status) {
    case 'SUCCESS': return 'green'
    case 'FAILED': return 'red'
    case 'RUNNING': return 'processing'
    case 'TIMEOUT': return 'orange'
    case 'CANCELED': return 'default'
    default: return 'default'
  }
}

const formatTime = (time?: string) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

defineExpose({ open })
</script>

<style scoped>
.log-drawer-body {
  display: flex;
  height: 100%;
  gap: 16px;
}
.instance-list-pane {
  width: 260px;
  border-right: 1px solid #f0f0f0;
  padding-right: 12px;
  overflow-y: auto;
}
.pane-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}
.instance-item {
  cursor: pointer;
  padding: 8px !important;
  border-radius: 4px;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  border-bottom: 1px solid #f5f5f5;
}
.instance-item:hover {
  background: #fafafa;
}
.instance-item.selected {
  background: #e6f4ff;
  border-color: #91caff;
}
.item-header {
  display: flex;
  justify-content: space-between;
  width: 100%;
  margin-bottom: 4px;
}
.item-time {
  font-size: 11px;
  color: #8c8c8c;
}
.item-metrics {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #595959;
}
.log-detail-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.detail-content {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.error-box {
  background: #fff2f0;
  border: 1px solid #ffccc7;
  padding: 8px 12px;
  border-radius: 4px;
  color: #cf1322;
  font-size: 12px;
  margin-bottom: 12px;
}
.error-box pre {
  margin: 4px 0 0;
  white-space: pre-wrap;
  word-break: break-all;
}
.log-console-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.console-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
  color: #595959;
}
.log-console {
  flex: 1;
  background: #1e1e1e;
  color: #00ff66;
  padding: 12px;
  border-radius: 6px;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
