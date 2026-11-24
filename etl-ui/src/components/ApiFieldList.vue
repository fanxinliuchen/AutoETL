<template>
  <div>
    <div class="fk-toolbar">
      <span class="fk-count">共 {{ rows.length }} 个字段 · 启用 {{ enabledCount }}</span>
      <a-space :size="6" wrap>
        <a-button size="small" @click="emit('importExcel')">
          <file-excel-outlined /> Excel 导入
        </a-button>
        <a-button size="small" :disabled="!rows.length" @click="emit('exportExcel')">
          <download-outlined /> 导出
        </a-button>
        <a-button size="small" @click="emit('downloadTemplate')">
          <file-add-outlined /> 模板
        </a-button>

        <!-- 金蝶专属：拉字段 / 校验字段名 / 报表试取 -->
        <template v-if="showKingdeeTools">
          <a-button size="small" :loading="kingdeeLoading" @click="emit('pullKingdee')">
            <api-outlined /> 从金蝶拉字段
          </a-button>
          <a-tooltip
            title="用金蝶表单元数据核对字段名：能发现 l/I、1/I、0/O、n/r 这类手抄混淆，以及 .FNumber/.FName 后缀的大小写问题"
          >
            <a-button size="small" :loading="fieldCheckLoading" @click="emit('validateFields')">
              <safety-certificate-outlined /> 校验字段名
            </a-button>
          </a-tooltip>
          <a-tooltip
            v-if="showReportTools"
            title="报表没有列定义接口，这里用 GetReportData 试取 1 行，按返回的 key 反推字段"
          >
            <a-button size="small" :loading="reportFieldsLoading" @click="emit('detectReportFields')">
              <table-outlined /> 从报表试取字段
            </a-button>
          </a-tooltip>
        </template>

        <a-button size="small" @click="emit('inferTypes')">
          <bulb-outlined /> 推断类型
        </a-button>
        <a-button size="small" @click="emit('enableAll', true)">全启用</a-button>
        <a-button size="small" @click="emit('enableAll', false)">全停用</a-button>
        <a-button size="small" @click="emit('openPaste')">粘贴导入</a-button>
        <a-button size="small" type="link" @click="emit('update:textMode', !textMode)">
          {{ textMode ? '切换到表格' : '文本模式' }}
        </a-button>
      </a-space>
    </div>

    <div class="fk-panel">
      <div v-if="!textMode">
        <a-table
          :columns="columns"
          :data-source="rows"
          size="small"
          row-key="rid"
          :pagination="false"
          :scroll="{ y: 260 }"
          bordered
        >
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.dataIndex === 'idx'">{{ index + 1 }}</template>

            <template v-else-if="column.dataIndex === 'fieldKey'">
              <a-input
                v-model:value="record.fieldKey"
                size="small"
                :placeholder="keyPlaceholder"
                class="fk-input-mono"
                @change="emit('rowChanged')"
              />
              <div v-if="columnNameHint(record.fieldKey)" class="fk-col-hint">
                列名：{{ columnNameHint(record.fieldKey) }}
              </div>
            </template>

            <template v-else-if="column.dataIndex === 'label'">
              <a-input
                v-model:value="record.label"
                size="small"
                placeholder="选填"
                @change="emit('rowChanged')"
              />
            </template>

            <template v-else-if="column.dataIndex === 'column'">
              <a-input
                v-model:value="record.column"
                size="small"
                placeholder="留空自动换算"
                class="fk-input-mono"
                @change="emit('rowChanged')"
              />
            </template>

            <template v-else-if="column.dataIndex === 'type'">
              <a-tooltip
                title="保存后按该类型创建临时表列（未指定按 TEXT）；类型与数据不符会导致写入失败"
              >
                <a-select
                  v-model:value="record.type"
                  size="small"
                  :options="typeOptions"
                  style="width: 100%"
                  @change="emit('rowChanged')"
                />
              </a-tooltip>
            </template>

            <template v-else-if="column.dataIndex === 'enabled'">
              <a-switch
                v-model:checked="record.enabled"
                size="small"
                @change="emit('rowChanged')"
              />
            </template>

            <template v-else-if="column.dataIndex === 'action'">
              <a-space :size="0">
                <a-button
                  type="link"
                  size="small"
                  :disabled="index === 0"
                  @click="emit('move', index, -1)"
                >
                  上移
                </a-button>
                <a-button
                  type="link"
                  size="small"
                  :disabled="index === rows.length - 1"
                  @click="emit('move', index, 1)"
                >
                  下移
                </a-button>
                <a-button type="link" size="small" danger @click="emit('remove', index)">
                  删除
                </a-button>
              </a-space>
            </template>
          </template>
        </a-table>

        <div class="fk-foot">
          <a-button type="dashed" size="small" block @click="emit('add')">
            <plus-outlined /> 添加字段
          </a-button>
        </div>
      </div>

      <div v-else>
        <a-textarea
          :value="fieldKeysText"
          :rows="4"
          class="code-textarea"
          :placeholder="keyPlaceholder"
          @change="(e: any) => emit('update:fieldKeysText', e.target.value)"
        />
      </div>

      <div v-if="hintText" class="fk-hint">{{ hintText }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  ApiOutlined,
  BulbOutlined,
  DownloadOutlined,
  FileAddOutlined,
  FileExcelOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  TableOutlined
} from '@ant-design/icons-vue'

defineProps<{
  /** 字段行（对象引用，行内编辑会直接改到父组件的数据） */
  rows: any[]
  columns: any[]
  typeOptions: { label: string; value: string }[]
  enabledCount: number
  textMode: boolean
  fieldKeysText: string
  keyPlaceholder: string
  hintText: string
  /** 是否显示金蝶专属工具（拉字段 / 校验字段名 / 报表试取） */
  showKingdeeTools: boolean
  showReportTools: boolean
  kingdeeLoading: boolean
  fieldCheckLoading: boolean
  reportFieldsLoading: boolean
  columnNameHint: (key: string) => string
}>()

const emit = defineEmits<{
  (e: 'update:textMode', value: boolean): void
  (e: 'update:fieldKeysText', value: string): void
  (e: 'rowChanged'): void
  (e: 'add'): void
  (e: 'remove', index: number): void
  (e: 'move', index: number, delta: number): void
  (e: 'importExcel'): void
  (e: 'exportExcel'): void
  (e: 'downloadTemplate'): void
  (e: 'openPaste'): void
  (e: 'pullKingdee'): void
  (e: 'validateFields'): void
  (e: 'detectReportFields'): void
  (e: 'inferTypes'): void
  (e: 'enableAll', enabled: boolean): void
}>()
</script>

<style scoped>
.code-textarea {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  background: #fcfcfc;
  line-height: 1.5;
}

.fk-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.fk-count {
  font-size: 12px;
  color: #8c8c8c;
  white-space: nowrap;
}

.fk-panel {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  background: #fafafa;
  padding: 10px 12px 8px;
  margin-bottom: 16px;
  margin-top: 8px;
}

.fk-foot {
  margin-top: 8px;
}

.fk-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.6;
}

.fk-col-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.4;
}

.fk-input-mono :deep(input) {
  font-family: Consolas, Monaco, monospace;
}
</style>
