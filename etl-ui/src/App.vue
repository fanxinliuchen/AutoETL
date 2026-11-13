<template>
  <a-config-provider :theme="{ token: { colorPrimary: '#1677ff', borderRadius: 4 } }">
    <div class="etl-workbench-layout">
      <!-- 顶部 Header -->
      <header class="workbench-header">
        <div class="logo-area">
          <div class="logo-badge">ETL</div>
          <span class="system-title">轻量级 ETL 网页端数据集成与调度系统</span>
        </div>
        <div class="header-actions">
          <a-space>
            <a-button @click="openDatasourceModal">
              <database-outlined /> 数据源连接管理
            </a-button>
            <a-button @click="openAlertModal">
              <bell-outlined /> 告警通道管理
            </a-button>
            <a-button type="link" href="http://localhost:8388/swagger-ui.html" target="_blank">
              <api-outlined /> 后端 API 文档
            </a-button>
          </a-space>
        </div>
      </header>

      <!-- 主体：左树右列表 -->
      <div class="workbench-body">
        <!-- 左侧分类目录 (280px) -->
        <aside class="left-tree-pane">
          <FolderTree
            ref="folderTreeRef"
            @select="onFolderSelect"
            @select-pipeline="onPipelineNodeSelect"
          />
        </aside>

        <!-- 右侧流程列表与工作区 -->
        <main class="right-list-pane">
          <!-- 1. 流程列表视图 -->
          <div v-show="currentView === 'list'" class="view-panel">
            <!-- 搜索与筛选工具栏 -->
            <div class="filter-card">
              <a-form layout="inline" :model="queryForm">
                <a-form-item label="流程名称/编码">
                  <a-input
                    v-model:value="queryForm.keyword"
                    placeholder="输入名称或编码搜索..."
                    allow-clear
                    @pressEnter="fetchPipelines"
                  />
                </a-form-item>
                <a-form-item label="数据源类型">
                  <a-select v-model:value="queryForm.sourceType" placeholder="全部类型" allow-clear style="width: 140px">
                    <a-select-option value="MYSQL">MySQL 数据库</a-select-option>
                    <a-select-option value="WEB_API">WebAPI 接口</a-select-option>
                  </a-select>
                </a-form-item>
                <a-form-item>
                  <a-space>
                    <a-button type="primary" @click="fetchPipelines">
                      <search-outlined /> 查询
                    </a-button>
                    <a-button @click="resetQuery">
                      <redo-outlined /> 重置
                    </a-button>
                  </a-space>
                </a-form-item>
              </a-form>
            </div>

            <!-- 操作条与表格 -->
            <div class="table-card">
              <div class="table-toolbar">
                <span class="toolbar-title">业务流程</span>
                <a-space>
                  <a-button @click="fetchPipelines">
                    <sync-outlined :spin="loading" /> 刷新列表
                  </a-button>
                  <a-button type="primary" @click="openCreatePipeline">
                    <plus-outlined /> 新建 ETL 流程
                  </a-button>
                </a-space>
              </div>

              <a-table
                :columns="columns"
                :data-source="pipelineList"
                :loading="loading"
                row-key="id"
                :pagination="pagination"
                @change="handleTableChange"
              >
                <template #bodyCell="{ column, record }">
                  <!-- 流程名称 -->
                  <template v-if="column.dataIndex === 'pipelineName'">
                    <div class="pipeline-name-cell">
                      <div style="display: flex; align-items: center; gap: 6px">
                        <span class="p-name" @click="openEditPipeline(record)">{{ record.pipelineName }}</span>
                        <a-tag
                          v-if="!isPipelineRunnable(record)"
                          color="orange"
                          style="font-size: 11px; margin: 0; padding: 0 4px"
                        >
                          草稿 / 配置中
                        </a-tag>
                      </div>
                      <span class="p-code">{{ record.pipelineCode }}</span>
                    </div>
                  </template>

                  <!-- 源类型 -->
                  <template v-else-if="column.dataIndex === 'sourceType'">
                    <a-tag :color="record.sourceType === 'MYSQL' ? 'blue' : 'green'">
                      {{ record.sourceType === 'MYSQL' ? 'MySQL' : 'WebAPI' }}
                    </a-tag>
                  </template>

                  <!-- 目标表：上行表名（自动换行），下行目标数据源名称 -->
                  <template v-else-if="column.dataIndex === 'targetTable'">
                    <div class="target-table-cell">
                      <span class="t-name">{{ record.targetTable || '-' }}</span>
                      <span v-if="datasourceName(record.targetDsId)" class="t-ds">
                        {{ datasourceName(record.targetDsId) }}
                      </span>
                    </div>
                  </template>

                  <!-- Cron 调度：上行表达式，下行中文释义 -->
                  <template v-else-if="column.dataIndex === 'cronExpr'">
                    <div v-if="record.cronExpr" class="cron-cell">
                      <span class="cron-expr">{{ record.cronExpr }}</span>
                      <span v-if="describeCron(record.cronExpr)" class="cron-desc">
                        {{ describeCron(record.cronExpr) }}
                      </span>
                    </div>
                    <span v-else style="color: #bfbfbf">-</span>
                  </template>

                  <!-- 调度开关 -->
                  <template v-else-if="column.dataIndex === 'scheduleStatus'">
                    <a-switch
                      :checked="record.scheduleStatus === 1"
                      size="small"
                      checked-children="已开启"
                      un-checked-children="已停用"
                      @change="(val: boolean) => onToggleSchedule(record, val)"
                    />
                  </template>

                  <!-- 最近运行状态 -->
                  <template v-else-if="column.dataIndex === 'lastRunStatus'">
                    <a-tag v-if="record.lastRunStatus" :color="getStatusTagColor(record.lastRunStatus)">
                      {{ record.lastRunStatus }}
                    </a-tag>
                    <span v-else style="color: #bfbfbf">-</span>
                  </template>

                  <!-- 最近运行时间 -->
                  <template v-else-if="column.dataIndex === 'lastRunTime'">
                    <span>{{ formatTime(record.lastRunTime) }}</span>
                  </template>

                  <!-- 操作列 -->
                  <template v-else-if="column.dataIndex === 'action'">
                    <a-space>
                      <a-tooltip :title="!isPipelineRunnable(record) ? '该流程为草稿状态，请先编辑完善后再运行' : '立即手动运行一次'">
                        <a-popconfirm
                          :disabled="!isPipelineRunnable(record)"
                          title="确定立即触发运行该流程？"
                          @confirm="onRunPipeline(record.id)"
                        >
                          <a-button
                            type="link"
                            size="small"
                            :disabled="!isPipelineRunnable(record)"
                          >
                            <play-circle-outlined
                              :style="{
                                color: !isPipelineRunnable(record) ? '#bfbfbf' : '#52c41a',
                                fontSize: '15px'
                              }"
                            /> 运行
                          </a-button>
                        </a-popconfirm>
                      </a-tooltip>

                      <a-button type="link" size="small" @click="openEditPipeline(record)">
                        <edit-outlined /> 编辑
                      </a-button>

                      <a-button type="link" size="small" @click="onCopyPipeline(record)">
                        <copy-outlined /> 复制
                      </a-button>

                      <a-button type="link" size="small" @click="openLogDrawer(record)">
                        <file-text-outlined /> 日志
                      </a-button>

                      <a-popconfirm title="确定删除该流程配置？" @confirm="onDeletePipeline(record.id)">
                        <a-button type="link" size="small" danger>
                          <delete-outlined /> 删除
                        </a-button>
                      </a-popconfirm>
                    </a-space>
                  </template>
                </template>
              </a-table>
            </div>
          </div>

          <!-- 2. 流程配置设计器视图 (5大子Tab独立拆分主页展示) -->
          <div v-show="currentView === 'editor'" class="view-panel">
            <PipelineEditor
              ref="pipelineEditorRef"
              @back="currentView = 'list'"
              @success="onEditorSuccess"
            />
          </div>
        </main>
      </div>

      <!-- 弹窗/抽屉组件 -->
      <InstanceLogDrawer ref="instanceLogDrawerRef" />
      <DatasourceModal ref="datasourceModalRef" />
      <AlertModal ref="alertModalRef" />
    </div>
  </a-config-provider>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, nextTick } from 'vue'
import {
  DatabaseOutlined,
  BellOutlined,
  ApiOutlined,
  SearchOutlined,
  RedoOutlined,
  PlusOutlined,
  SyncOutlined,
  PlayCircleOutlined,
  EditOutlined,
  CopyOutlined,
  FileTextOutlined,
  DeleteOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import FolderTree from './components/FolderTree.vue'
import PipelineEditor from './components/PipelineEditor.vue'
import InstanceLogDrawer from './components/InstanceLogDrawer.vue'
import DatasourceModal from './components/DatasourceModal.vue'
import AlertModal from './components/AlertModal.vue'
import {
  getPipelinePage,
  getPipelineDetail,
  copyPipeline,
  deletePipeline,
  runPipeline,
  togglePipelineSchedule
} from './api/pipeline'
import type { EtlPipeline } from './types'
import { describeCron } from './utils/cronDesc'
import { getDatasourceList } from './api/datasource'

const currentView = ref<'list' | 'editor'>('list')
const pipelineEditorRef = ref()
const folderTreeRef = ref()
const instanceLogDrawerRef = ref()
const datasourceModalRef = ref()
const alertModalRef = ref()

const currentFolderId = ref<string>('')
const loading = ref(false)
const pipelineList = ref<EtlPipeline[]>([])
/** 数据源 id -> 名称，目标表列第二行展示用 */
const datasourceNames = ref<Record<string, string>>({})

const queryForm = reactive({
  keyword: '',
  sourceType: undefined as string | undefined
})

const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
  showTotal: (total: number) => `共 ${total} 条流程`
})

const columns = [
  { title: '流程名称 / 编码', dataIndex: 'pipelineName', width: 220 },
  { title: '源类型', dataIndex: 'sourceType', width: 90 },
  { title: '目标表', dataIndex: 'targetTable', width: 240 },
  { title: 'Cron 调度', dataIndex: 'cronExpr', width: 160 },
  { title: '调度开关', dataIndex: 'scheduleStatus', width: 90 },
  { title: '最近状态', dataIndex: 'lastRunStatus', width: 90 },
  { title: '最近执行时间', dataIndex: 'lastRunTime', width: 160 },
  { title: '操作', dataIndex: 'action', width: 300, fixed: 'right' }
]

const onFolderSelect = (folderId: string) => {
  currentFolderId.value = folderId
  pagination.current = 1
  fetchPipelines()
}

const fetchPipelines = async () => {
  loading.value = true
  try {
    const filters: any[] = []
    if (currentFolderId.value) {
      filters.push({ fieldName: 'folderId', condition: 'eq', value: currentFolderId.value })
    }
    if (queryForm.keyword) {
      filters.push({ fieldName: 'pipelineName', condition: 'like', value: queryForm.keyword })
    }
    if (queryForm.sourceType) {
      filters.push({ fieldName: 'sourceType', condition: 'eq', value: queryForm.sourceType })
    }

    const [res] = await Promise.all([
      getPipelinePage({
        current: pagination.current,
        size: pagination.pageSize,
        query: {
          filters,
          softs: [{ fieldName: 'updateTime', orderCode: 'desc' }]
        }
      }),
      // 数据源名称只用于展示，拉不到也不该拦住列表
      loadDatasources()
    ])
    pipelineList.value = res.list || []
    pagination.total = res.total || 0
  } finally {
    loading.value = false
  }
}

const loadDatasources = async () => {
  try {
    const list = await getDatasourceList()
    const map: Record<string, string> = {}
    for (const ds of list || []) {
      if (ds.id) map[String(ds.id)] = ds.dsName
    }
    datasourceNames.value = map
  } catch (e) {
    // 名称缺失时目标表列只显示表名
  }
}

const datasourceName = (id?: string | number) =>
  id ? datasourceNames.value[String(id)] || '' : ''

const resetQuery = () => {
  queryForm.keyword = ''
  queryForm.sourceType = undefined
  pagination.current = 1
  fetchPipelines()
}

const handleTableChange = (pag: any) => {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  fetchPipelines()
}

const openCreatePipeline = () => {
  currentView.value = 'editor'
  nextTick(() => {
    pipelineEditorRef.value?.open(undefined, currentFolderId.value)
  })
}

const openEditPipeline = (record: EtlPipeline) => {
  currentView.value = 'editor'
  nextTick(() => {
    pipelineEditorRef.value?.open(record)
  })
}

/**
 * 点击左树流程叶子：与右侧「编辑」等价，直接打开该流程配置。
 * 树节点只带 id，详情从后端取，保证与列表行数据同一口径。
 */
const onPipelineNodeSelect = async (pipelineId: string) => {
  if (!pipelineId) return
  try {
    const detail = await getPipelineDetail(pipelineId)
    if (detail) openEditPipeline(detail)
  } catch (e) {
    // 错误已由请求拦截器统一提示
  }
}

/**
 * 整体复制流程：抽取 / 转换 / 目标 / 告警配置原样带走，编码与名称由后端生成。
 * 典型场景：周批次流程复制出月批次（跨月周无法合并，只能各跑各的），复制后改 Cron 与目标表即可。
 */
const onCopyPipeline = async (record: EtlPipeline) => {
  if (!record.id) return
  try {
    const copy = await copyPipeline(record.id)
    message.success(`已复制为「${copy.pipelineName}」，调度默认停用，改完 Cron 与目标表后再开启`)
    await fetchPipelines()
    folderTreeRef.value?.refresh()
  } catch (e) {
    // 错误已由请求拦截器统一提示
  }
}

/**
 * 可运行判定，与后端 PipelineExecutor 保持一致：
 * 只要求目标数仓 + 目标表；转换 SQL 允许为空（系统按目标表结构自动生成 INSERT）
 */
const isPipelineRunnable = (record: EtlPipeline) => !!(record.targetDsId && record.targetTable)

const onEditorSuccess = () => {
  currentView.value = 'list'
  fetchPipelines()
  // 流程新增/改名/换目录后，左树叶子同步刷新
  folderTreeRef.value?.refresh()
}

const openLogDrawer = (record: EtlPipeline) => {
  instanceLogDrawerRef.value?.open(record)
}

const openDatasourceModal = () => {
  datasourceModalRef.value?.open()
}

const openAlertModal = () => {
  alertModalRef.value?.open()
}

const onRunPipeline = async (id?: string) => {
  if (!id) return
  await runPipeline(id)
  message.success('已触发运行，可在日志抽屉中查看执行进度！')
  setTimeout(fetchPipelines, 1000)
}

const onToggleSchedule = async (record: EtlPipeline, enabled: boolean) => {
  if (!record.id) return
  await togglePipelineSchedule(record.id, enabled)
  message.success(`已${enabled ? '启用' : '停用'}该流程定时调度`)
  record.scheduleStatus = enabled ? 1 : 0
}

const onDeletePipeline = async (id?: string) => {
  if (!id) return
  await deletePipeline(id)
  message.success('流程已成功删除')
  await fetchPipelines()
  folderTreeRef.value?.refresh()
}

const getStatusTagColor = (status: string) => {
  switch (status) {
    case 'SUCCESS': return 'green'
    case 'FAILED': return 'red'
    case 'RUNNING': return 'processing'
    default: return 'default'
  }
}

const formatTime = (time?: string) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

onMounted(() => {
  fetchPipelines()
})
</script>

<style>
body {
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background-color: #f0f2f5;
}
.etl-workbench-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.workbench-header {
  height: 52px;
  background: #ffffff;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
}
.logo-area {
  display: flex;
  align-items: center;
  gap: 10px;
}
.logo-badge {
  background: #1677ff;
  color: #ffffff;
  font-weight: 700;
  font-size: 13px;
  padding: 2px 8px;
  border-radius: 4px;
}
.system-title {
  font-size: 15px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
}
.workbench-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}
.left-tree-pane {
  width: 280px;
  flex-shrink: 0;
  height: 100%;
}
.right-list-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 16px;
  gap: 16px;
}
.view-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex: 1;
}
.filter-card {
  background: #ffffff;
  padding: 16px;
  border-radius: 6px;
  border: 1px solid #f0f0f0;
}
.table-card {
  background: #ffffff;
  padding: 16px;
  border-radius: 6px;
  border: 1px solid #f0f0f0;
  flex: 1;
}
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}
.toolbar-title::before {
  content: '';
  width: 3px;
  height: 14px;
  background: #1677ff;
  border-radius: 2px;
}
.pipeline-name-cell {
  display: flex;
  flex-direction: column;
}
.pipeline-name-cell .p-name {
  font-weight: 600;
  color: #1677ff;
  cursor: pointer;
}
.pipeline-name-cell .p-code {
  font-size: 12px;
  color: #8c8c8c;
  font-family: monospace;
}
.target-table-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.45;
}
.target-table-cell .t-name {
  word-break: break-all;
  color: rgba(0, 0, 0, 0.75);
}
.target-table-cell .t-ds {
  font-size: 12px;
  color: #8c8c8c;
}
.cron-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.45;
}
.cron-cell .cron-expr {
  font-family: monospace;
  color: rgba(0, 0, 0, 0.85);
}
.cron-cell .cron-desc {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
