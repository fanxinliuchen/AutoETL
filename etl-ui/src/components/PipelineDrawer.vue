<template>
  <a-drawer
    v-model:open="visible"
    :title="isEdit ? '编辑 ETL 流程' : '新建 ETL 流程'"
    width="860"
    :destroy-on-close="true"
    @close="onClose"
  >
    <a-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      layout="vertical"
    >
      <!-- 1. 基础信息 -->
      <a-card title="1. 基础信息" size="small" class="form-section-card">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="所属目录" name="folderId">
              <a-tree-select
                v-model:value="formData.folderId"
                :tree-data="folderTree"
                :field-names="{ children: 'children', label: 'title', value: 'id', key: 'id' }"
                placeholder="请选择所属业务目录"
                allow-clear
                tree-default-expand-all
                tree-node-filter-prop="title"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="流程名称" name="pipelineName">
              <a-input v-model:value="formData.pipelineName" placeholder="例如：ERP物料主数据定时同步" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="数据源类型" name="sourceType">
              <a-radio-group v-model:value="formData.sourceType" button-style="solid">
                <a-radio-button value="MYSQL">MySQL 数据库</a-radio-button>
                <a-radio-button value="WEB_API">WebAPI 接口</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="流程说明">
              <a-input v-model:value="formData.description" placeholder="业务背景、同步频率、责任人等说明" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>

      <!-- 2. 数据输入抽取配置 -->
      <a-card title="2. 数据抽取配置 (Source)" size="small" class="form-section-card">
        <!-- MySQL 源模式 -->
        <div v-if="formData.sourceType === 'MYSQL'">
          <a-form-item label="来源 MySQL 数据库" name="sourceDsId">
            <a-select v-model:value="formData.sourceDsId" placeholder="选择抽取来源库" allow-clear>
              <a-select-option v-for="ds in datasourceList" :key="ds.id" :value="ds.id">
                {{ ds.dsName }} ({{ ds.host ? `${ds.host}:${ds.port || 3306}/${ds.databaseName || ''}` : ds.jdbcUrl }})
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="抽取 SQL 查询语句" name="sourceQuerySql">
            <SqlEditor
              v-model="formData.sourceQuerySql"
              placeholder="SELECT id, material_code, name, price, update_time FROM t_material WHERE update_time >= '${yesterday}'"
            />
          </a-form-item>
        </div>

        <!-- WebAPI 源模式 -->
        <div v-else>
          <a-row :gutter="16">
            <a-col :span="6">
              <a-form-item label="请求方式" name="apiMethod">
                <a-select v-model:value="formData.apiMethod">
                  <a-select-option value="GET">GET</a-select-option>
                  <a-select-option value="POST">POST</a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col :span="18">
              <a-form-item label="接口请求 URL" name="apiUrl">
                <a-input v-model:value="formData.apiUrl" placeholder="https://api.example.com/v1/materials/list" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="请求头 Headers (JSON格式)">
                <a-textarea v-model:value="formData.apiHeaders" :rows="3" placeholder='{"Authorization": "Bearer xxx"}' />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="请求体 Body (POST时)">
                <a-textarea v-model:value="formData.apiBody" :rows="3" placeholder='{"page": 1, "pageSize": 100}' />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="结果提取 JsonPath" name="apiDataPath">
            <a-input v-model:value="formData.apiDataPath" placeholder="例如：$.data.records 或 $.data.list" />
          </a-form-item>
        </div>
      </a-card>

      <!-- 3. SQL 转换处理脚本 -->
      <a-card title="3. SQL 处理与转换脚本 (Transform)" size="small" class="form-section-card">
        <div class="field-hint">
          💡 抽取数据将首先暂存入当前流程专属临时表 <code>${stage_table}</code>。您可编写原生 SQL 进行清洗、关联或聚合后写入最终数仓表 <code>${target_table}</code>。
        </div>
        <a-form-item name="transformSql">
          <SqlEditor
            v-model="formData.transformSql"
            placeholder="-- 清空临时表或直接转换写入
INSERT INTO ${target_table} (id, material_code, material_name, sync_date)
SELECT id, material_code, name, '${today}'
FROM ${stage_table}
ON DUPLICATE KEY UPDATE material_name = VALUES(material_name);"
          />
        </a-form-item>
      </a-card>

      <!-- 4. 数仓输出端配置 -->
      <a-card title="4. 目标数仓输出 (Sink)" size="small" class="form-section-card">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="目标 MySQL 数仓" name="targetDsId">
              <a-select v-model:value="formData.targetDsId" placeholder="选择目标数仓连接" allow-clear>
                <a-select-option v-for="ds in datasourceList" :key="ds.id" :value="ds.id">
                  {{ ds.dsName }} ({{ ds.host ? `${ds.host}:${ds.port || 3306}/${ds.databaseName || ''}` : ds.jdbcUrl }})
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="目标表名" name="targetTable">
              <a-input v-model:value="formData.targetTable" placeholder="例如：dwd_erp_material_info" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="写入模式">
              <a-select v-model:value="formData.writeMode">
                <a-select-option value="APPEND">APPEND (直接追加插入)</a-select-option>
                <a-select-option value="OVERWRITE">OVERWRITE (清空全量覆盖)</a-select-option>
                <a-select-option value="UPSERT">UPSERT (存在依据主键更新)</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="12" v-if="formData.writeMode === 'UPSERT'">
            <a-form-item label="业务主键列 (逗号分隔)">
              <a-input v-model:value="formData.upsertKeys" placeholder="例如：id 或 material_code" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>

      <!-- 5. 调度与告警设置 -->
      <a-card title="5. 定时调度与告警通知" size="small" class="form-section-card">
        <a-row :gutter="16">
          <a-col :span="14">
            <a-form-item label="Cron 调度表达式">
              <a-input v-model:value="formData.cronExpr" placeholder="0 0 2 * * ? (每天凌晨2点)">
                <template #addonAfter>
                  <a-dropdown :trigger="['click']">
                    <a style="cursor: pointer">常用推荐 ▼</a>
                    <template #overlay>
                      <a-menu @click="selectPresetCron">
                        <a-menu-item key="0 */5 * * * ?">每 5 分钟 (0 */5 * * * ?)</a-menu-item>
                        <a-menu-item key="0 0 * * * ?">每小时整点 (0 0 * * * ?)</a-menu-item>
                        <a-menu-item key="0 0 2 * * ?">每天凌晨 2 点 (0 0 2 * * ?)</a-menu-item>
                        <a-menu-item key="0 0 6 * * ?">每天早晨 6 点 (0 0 6 * * ?)</a-menu-item>
                        <a-menu-item key="0 0 1 ? * MON">每周一凌晨 1 点 (0 0 1 ? * MON)</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </template>
              </a-input>
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="开启定时调度">
              <a-switch
                v-model:checked="scheduleChecked"
                checked-children="已启用"
                un-checked-children="停用"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="异常告警通道">
              <a-select v-model:value="formData.alertConfigId" placeholder="选择机器人或邮件告警通道" allow-clear>
                <a-select-option v-for="alt in alertList" :key="alt.id" :value="alt.id">
                  {{ alt.channelName }} ({{ alt.channelType }})
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="失败重试次数">
              <a-input-number v-model:value="formData.maxRetryCount" :min="0" :max="5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="超时时间 (秒)">
              <a-input-number v-model:value="formData.timeoutSeconds" :min="60" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>
    </a-form>

    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <a-button @click="visible = false">取消</a-button>
        <a-button type="primary" :loading="submitting" @click="onSubmit">保存流程</a-button>
      </div>
    </template>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { message } from 'ant-design-vue'
import SqlEditor from './SqlEditor.vue'
import { savePipeline, updatePipeline } from '../api/pipeline'
import { getDatasourceList } from '../api/datasource'
import { getAlertConfigList } from '../api/alert'
import { getFolderTree } from '../api/folder'
import type { EtlPipeline, EtlDatasource, EtlAlertConfig } from '../types'

const emit = defineEmits<{
  (e: 'success'): void
}>()

const visible = ref(false)
const submitting = ref(false)
const isEdit = ref(false)
const currentId = ref<string>('')
const formRef = ref()

const datasourceList = ref<EtlDatasource[]>([])
const alertList = ref<EtlAlertConfig[]>([])
const folderTree = ref<any[]>([])

const formData = ref<Partial<EtlPipeline>>({
  sourceType: 'MYSQL',
  apiMethod: 'GET',
  writeMode: 'APPEND',
  timeoutSeconds: 1800,
  maxRetryCount: 0,
  scheduleStatus: 0,
  apiDataPath: '$.data.records'
})

const scheduleChecked = computed({
  get: () => formData.value.scheduleStatus === 1,
  set: (val: boolean) => {
    formData.value.scheduleStatus = val ? 1 : 0
  }
})

const formRules = {
  pipelineName: [{ required: true, message: '请输入流程名称', trigger: 'blur' }],
  sourceType: [{ required: true, message: '请选择数据源类型' }],
  transformSql: [{ required: true, message: '请输入 SQL 处理脚本', trigger: 'blur' }],
  targetDsId: [{ required: true, message: '请选择目标数仓连接' }],
  targetTable: [{ required: true, message: '请输入目标表名', trigger: 'blur' }]
}

const open = async (record?: EtlPipeline, defaultFolderId?: string) => {
  visible.value = true
  // 加载选项数据
  try {
    const [ds, alt, ft] = await Promise.all([
      getDatasourceList(),
      getAlertConfigList(),
      getFolderTree()
    ])
    datasourceList.value = ds
    alertList.value = alt
    folderTree.value = ft
  } catch (e) {}

  if (record && record.id) {
    isEdit.value = true
    currentId.value = String(record.id)
    formData.value = {
      ...record,
      sourceDsId: record.sourceDsId ? String(record.sourceDsId) : undefined,
      targetDsId: record.targetDsId ? String(record.targetDsId) : undefined,
      alertConfigId: record.alertConfigId ? String(record.alertConfigId) : undefined,
      folderId: record.folderId ? String(record.folderId) : undefined
    }
  } else {
    isEdit.value = false
    currentId.value = ''
    formData.value = {
      folderId: defaultFolderId || undefined,
      sourceType: 'MYSQL',
      apiMethod: 'GET',
      writeMode: 'APPEND',
      timeoutSeconds: 1800,
      maxRetryCount: 0,
      scheduleStatus: 0,
      apiDataPath: '$.data.records',
      transformSql: ''
    }
  }
}

const selectPresetCron = ({ key }: { key: string }) => {
  formData.value.cronExpr = key
}

const onSubmit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (isEdit.value) {
      await updatePipeline(currentId.value, formData.value)
      message.success('更新 ETL 流程成功')
    } else {
      await savePipeline(formData.value)
      message.success('新建 ETL 流程成功')
    }
    visible.value = false
    emit('success')
  } finally {
    submitting.value = false
  }
}

const onClose = () => {
  visible.value = false
}

defineExpose({ open })
</script>

<style scoped>
.form-section-card {
  margin-bottom: 16px;
  border-radius: 6px;
}
.field-hint {
  font-size: 12px;
  color: #8c8c8c;
  margin-bottom: 12px;
  line-height: 1.5;
}
.field-hint code {
  color: #722ed1;
  background: #f9f0ff;
  padding: 1px 4px;
  border-radius: 2px;
}
</style>
