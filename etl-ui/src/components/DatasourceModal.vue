<template>
  <a-modal
    v-model:open="visible"
    title="连接管理（数据库 / 金蝶 / 云表）"
    width="900"
    :footer="null"
    destroy-on-close
  >
    <div style="margin-bottom: 12px; display: flex; justify-content: flex-end; align-items: center">
      <a-button type="primary" size="small" @click="openEdit({})">
        <plus-outlined /> 新增连接
      </a-button>
    </div>

    <a-table
      :data-source="list"
      :columns="columns"
      row-key="id"
      size="small"
      :loading="loading"
      :pagination="false"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'dsName'">
          <span style="font-weight: 600">{{ record.dsName }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'connType'">
          <a-tag :color="connTypeColor(record.connType)">{{ connTypeLabel(record.connType) }}</a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'connectionTarget'">
          <span class="target-text">{{ record.connectionTarget || '—' }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'account'">
          <span>{{ record.account || '-' }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确定删除该连接？" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="editModalVisible"
      :title="isEdit ? '编辑连接' : '新增连接'"
      width="680"
      @ok="handleSave"
      :confirm-loading="saving"
      destroy-on-close
    >
      <a-form layout="vertical" class="datasource-config-form">
        <a-row :gutter="16">
          <a-col :span="14">
            <a-form-item label="连接名称" required>
              <a-input v-model:value="editModel.dsName" placeholder="例如：数仓原始层、金蝶（生产）、云表直连（dev）" />
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="连接类型" required>
              <a-select v-model:value="editModel.connType" @change="onConnTypeChange">
                <a-select-option value="MYSQL">MySQL 数据库</a-select-option>
                <a-select-option value="POSTGRESQL">PostgreSQL 数据库</a-select-option>
                <a-select-option value="KINGDEE_K3">金蝶云星空</a-select-option>
                <a-select-option value="IYUNBIAO">云表 OpenAPI</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <!-- ===== JDBC 数据库（MySQL / PostgreSQL） ===== -->
        <template v-if="isJdbcType">
          <div class="section-divider">
            <span class="section-title">基础配置</span>
            <span class="section-line"></span>
          </div>

          <a-row :gutter="16">
            <a-col v-if="editModel.connType === 'MYSQL'" :span="14">
              <a-form-item label="用途" required>
                <a-radio-group v-model:value="editModel.dsType" button-style="solid">
                  <a-radio-button value="MYSQL">业务源库</a-radio-button>
                  <a-radio-button value="MYSQL_DW">目标数仓</a-radio-button>
                </a-radio-group>
              </a-form-item>
            </a-col>
            <a-col v-if="editModel.connType === 'MYSQL'" :span="10">
              <a-form-item label="编码" required>
                <a-select v-model:value="editModel.encoding">
                  <a-select-option value="UTF-8">UTF-8</a-select-option>
                  <a-select-option value="GBK">GBK</a-select-option>
                  <a-select-option value="gb2312">gb2312</a-select-option>
                  <a-select-option value="latin1">latin1</a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col v-else :span="10">
              <a-form-item label="模式 (schema)">
                <a-input v-model:value="pgSchema" placeholder="public" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item v-if="editModel.connType === 'MYSQL'" label="驱动" required>
            <a-select v-model:value="editModel.driverClass">
              <a-select-option value="com.mysql.cj.jdbc.Driver">com.mysql.cj.jdbc.Driver (MySQL 8.0+ 推荐)</a-select-option>
              <a-select-option value="com.mysql.jdbc.Driver">com.mysql.jdbc.Driver (MySQL 5.7 旧版兼容)</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-else label="驱动" required>
            <a-input value="org.postgresql.Driver" disabled />
          </a-form-item>

          <a-form-item label="数据库名称" required>
            <a-input v-model:value="editModel.databaseName" :placeholder="editModel.connType === 'POSTGRESQL' ? '例如：nocodb、postgres' : '例如：ods、dhp_etl、erp_db'" />
          </a-form-item>

          <a-row :gutter="16">
            <a-col :span="16">
              <a-form-item label="主机 (Host / IP)" required>
                <a-input v-model:value="editModel.host" placeholder="例如：127.0.0.1、10.10.12.6" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="端口" required>
                <a-input-number
                  v-model:value="editModel.port"
                  :min="1"
                  :max="65535"
                  style="width: 100%"
                  :placeholder="editModel.connType === 'POSTGRESQL' ? '5432' : '3306'"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="用户名" required>
                <a-input v-model:value="editModel.username" :placeholder="editModel.connType === 'POSTGRESQL' ? 'postgres' : 'root'" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="密码">
                <a-input-password
                  v-model:value="editModel.passwordCipher"
                  :placeholder="isEdit ? '留空表示不修改已有密码' : '请输入密码'"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <div class="jdbc-preview-box">
            <div class="preview-label">自动生成 JDBC 连接串预览:</div>
            <code class="preview-url">{{ previewJdbcUrl }}</code>
          </div>
        </template>

        <!-- ===== 金蝶 ===== -->
        <template v-else-if="editModel.connType === 'KINGDEE_K3'">
          <div class="section-divider">
            <span class="section-title">凭证与登录地址</span>
            <span class="section-line"></span>
          </div>
          <a-form-item label="登录接口地址 (ValidateUser)" required>
            <a-input v-model:value="apiConfig.loginUrl" placeholder="http://host/K3Cloud/.../AuthService.ValidateUser.common.kdsvc" />
          </a-form-item>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="账套 ID" required>
                <a-input v-model:value="apiConfig.acctID" placeholder="acctID" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="登录账号" required>
                <a-input v-model:value="apiConfig.userName" placeholder="bi" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="登录密码">
                <a-input-password v-model:value="apiConfig.password" :placeholder="isEdit ? '留空表示不修改' : '请输入密码'" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="语言 ID">
                <a-input-number v-model:value="apiConfig.lcid" :min="1" style="width: 100%" placeholder="2052" />
              </a-form-item>
            </a-col>
          </a-row>
        </template>

        <!-- ===== 云表 ===== -->
        <template v-else>
          <div class="section-divider">
            <span class="section-title">凭证与连接参数</span>
            <span class="section-line"></span>
          </div>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="服务地址（含应用 ID）" required>
                <a-input v-model:value="apiConfig.baseUrl" placeholder="http://yunbiao.xxx.cn/10001" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="应用名称 (AppName)" required>
                <a-input v-model:value="apiConfig.appName" placeholder="x-eversheet-application-name" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="AppKey" required>
                <a-input-password v-model:value="apiConfig.appKey" :placeholder="isEdit ? '留空表示不修改' : '用于计算请求签名'" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="登录账号" required>
                <a-input v-model:value="apiConfig.userName" placeholder="test3" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="登录密码" required>
                <a-input-password v-model:value="apiConfig.password" :placeholder="isEdit ? '留空表示不修改' : '明文，提交时按 MD5 大写加密'" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="请求超时（毫秒）">
                <a-input-number v-model:value="apiConfig.timeoutMs" :min="1000" :step="1000" style="width: 100%" placeholder="15000" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="备注 / 实例说明">
            <a-input v-model:value="editModel.remark" placeholder="例如：云表直连（dev）；不填会自动用服务地址填充，列表里便于识别" />
          </a-form-item>
        </template>

        <div style="margin-top: 8px; display: flex; align-items: center">
          <a-button :loading="testing" @click="testConn">
            <api-outlined /> {{ isJdbcType ? '测试连通性' : '测试登录' }}
          </a-button>
          <span v-if="testResult" style="margin-left: 12px; font-size: 13px" :style="{ color: testSuccess ? '#52c41a' : '#ff4d4f' }">
            {{ testResult }}
          </span>
        </div>
      </a-form>
    </a-modal>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { PlusOutlined, ApiOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import {
  getConnectionList,
  getDatasourceConfig,
  saveDatasource,
  updateDatasource,
  deleteDatasource,
  testDatasourceConnection
} from '../api/datasource'
import type { ConnectionSummary } from '../api/datasource'
import type { EtlDatasource } from '../types'

const visible = ref(false)
const loading = ref(false)
// 列表来自 /connections：比实体多「连接目标 + 账号」（地址在加密配置里，实体上取不到）
const list = ref<(Partial<EtlDatasource> & Partial<ConnectionSummary>)[]>([])

const editModalVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref('')
const testSuccess = ref(false)
const editModel = ref<Partial<EtlDatasource>>({})

/** API 类连接的明文配置（提交时序列化进 configCipher） */
const apiConfig = ref<Record<string, any>>({})

const columns = [
  { title: '连接名称', dataIndex: 'dsName', width: 170 },
  { title: '类型', dataIndex: 'connType', width: 100 },
  { title: '连接目标', dataIndex: 'connectionTarget', ellipsis: true },
  { title: '账号', dataIndex: 'account', width: 100 },
  { title: '操作', dataIndex: 'action', width: 100 }
]

const connTypeLabel = (type?: string) =>
  type === 'KINGDEE_K3' ? '金蝶' : type === 'IYUNBIAO' ? '云表' : type === 'POSTGRESQL' ? 'PostgreSQL' : 'MySQL'

const connTypeColor = (type?: string) =>
  type === 'KINGDEE_K3' ? 'purple' : type === 'IYUNBIAO' ? 'green' : type === 'POSTGRESQL' ? 'geekblue' : 'blue'

/** JDBC 类连接（有主机/端口/库名，可当数据库源；目标数仓仍只允许 MySQL） */
const isJdbcType = computed(
  () => editModel.value.connType === 'MYSQL' || editModel.value.connType === 'POSTGRESQL'
)

/** PostgreSQL 模式（schema），只回填到连接串，不单独占表字段 */
const pgSchema = ref('public')

const previewJdbcUrl = computed(() => {
  const host = editModel.value.host?.trim() || '127.0.0.1'
  const db = editModel.value.databaseName?.trim() || 'database'
  const port = editModel.value.port || (editModel.value.connType === 'POSTGRESQL' ? 5432 : 3306)
  if (editModel.value.connType === 'POSTGRESQL') {
    const schema = pgSchema.value?.trim() || 'public'
    return `jdbc:postgresql://${host}:${port}/${db}?currentSchema=${schema}&sslmode=disable&ApplicationName=dhp-etl`
  }
  const enc = editModel.value.encoding || 'UTF-8'
  return `jdbc:mysql://${host}:${port}/${db}?useUnicode=true&characterEncoding=${enc}&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`
})

const open = () => {
  visible.value = true
  fetchList()
}

const fetchList = async () => {
  loading.value = true
  try {
    const rows = await getConnectionList()
    // 兜底：历史数据 conn_type 可能为空，按驱动名归位，避免一律显示成 MySQL
    list.value = (rows || []).map((row) => {
      if (!row.connType && (row.driverClass || '').toLowerCase().includes('postgresql')) {
        return { ...row, connType: 'POSTGRESQL' as const }
      }
      return row
    })
  } finally {
    loading.value = false
  }
}

/** 各类型连接的默认配置：服务地址/账套等能给默认值的先填上，减少手输入错 */
const defaultApiConfig = (connType: string) => {
  if (connType === 'IYUNBIAO') {
    return { baseUrl: '', appName: '', appKey: '', userName: '', password: '', timeoutMs: 15000 }
  }
  return {
    loginUrl: 'http://39.98.122.240/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.ValidateUser.common.kdsvc',
    acctID: '',
    userName: '',
    password: '',
    lcid: 2052
  }
}

const openEdit = async (record: Partial<EtlDatasource>) => {
  isEdit.value = !!record.id
  const connType = (record.connType as string) || 'MYSQL'
  const isPg = connType === 'POSTGRESQL'
  editModel.value = {
    dsType: isPg ? 'POSTGRESQL' : 'MYSQL',
    driverClass: isPg ? 'org.postgresql.Driver' : 'com.mysql.cj.jdbc.Driver',
    host: '127.0.0.1',
    port: isPg ? 5432 : 3306,
    databaseName: '',
    encoding: 'UTF-8',
    username: isPg ? 'postgres' : 'root',
    ...record,
    connType: connType as any,
    passwordCipher: ''
  }
  pgSchema.value = 'public'
  apiConfig.value = defaultApiConfig(connType)
  testResult.value = ''

  if (record.jdbcUrl && (!record.host || !record.databaseName)) {
    try {
      const pgMatch = record.jdbcUrl.match(/jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/)
      if (pgMatch) {
        editModel.value.host = pgMatch[1]
        editModel.value.port = pgMatch[2] ? parseInt(pgMatch[2]) : 5432
        editModel.value.databaseName = decodeURIComponent(pgMatch[3])
      } else {
        const match = record.jdbcUrl.match(/jdbc:mysql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/)
        if (match) {
          editModel.value.host = match[1]
          editModel.value.port = match[2] ? parseInt(match[2]) : 3306
          editModel.value.databaseName = match[3]
        }
      }
    } catch (e) {}
  }
  if (isPg && record.jdbcUrl) {
    const schemaMatch = record.jdbcUrl.match(/[?&]currentSchema=([^&]+)/)
    if (schemaMatch) pgSchema.value = decodeURIComponent(schemaMatch[1])
  }

  // 回显配置：密码/AppKey 后端已打码，提交时原样回传表示不修改
  if (record.id && connType !== 'MYSQL' && connType !== 'POSTGRESQL') {
    try {
      const res = await getDatasourceConfig(record.id)
      apiConfig.value = { ...apiConfig.value, ...(res.config || {}) }
    } catch (e) {
      // 读取失败不阻塞编辑，用户重填即可
    }
  }
  editModalVisible.value = true
}

const onConnTypeChange = (value: any) => {
  apiConfig.value = defaultApiConfig(String(value))
  testResult.value = ''
  if (value === 'POSTGRESQL') {
    editModel.value.port = 5432
    editModel.value.driverClass = 'org.postgresql.Driver'
    editModel.value.dsType = 'POSTGRESQL'
    pgSchema.value = 'public'
  } else if (value === 'MYSQL') {
    editModel.value.port = 3306
    editModel.value.driverClass = 'com.mysql.cj.jdbc.Driver'
    editModel.value.dsType = 'MYSQL'
  }
}

const testConn = async () => {
  testing.value = true
  testResult.value = ''
  try {
    const isApi = !isJdbcType.value
    let res: string
    if (isApi) {
      res = await testDatasourceConnection({
        id: editModel.value.id,
        connType: editModel.value.connType,
        configJson: JSON.stringify(apiConfig.value)
      })
    } else {
      if (!editModel.value.host || !editModel.value.databaseName || !editModel.value.username) {
        message.warning('请先完整填写主机、数据库名称和用户名')
        return
      }
      res = await testDatasourceConnection({
        id: editModel.value.id,
        connType: editModel.value.connType,
        driverClass: editModel.value.driverClass,
        host: editModel.value.host,
        port: editModel.value.port,
        databaseName: editModel.value.databaseName,
        encoding: editModel.value.encoding,
        username: editModel.value.username,
        password: editModel.value.passwordCipher
      })
    }
    testSuccess.value = true
    testResult.value = res
  } catch (e: any) {
    testSuccess.value = false
    testResult.value = e.message || '连接失败'
  } finally {
    testing.value = false
  }
}

const handleSave = async () => {
  const isApi = !isJdbcType.value
  if (!editModel.value.dsName) {
    message.warning('请填写连接名称')
    return
  }
  if (!isApi && (!editModel.value.host || !editModel.value.databaseName || !editModel.value.username)) {
    message.warning('请将主机、数据库名称及用户名填写完整')
    return
  }
  if (isApi) {
    if (editModel.value.connType === 'IYUNBIAO') {
      if (!apiConfig.value.baseUrl || !apiConfig.value.appName || !apiConfig.value.userName) {
        message.warning('请填写服务地址、应用名称与登录账号')
        return
      }
      if (!editModel.value.remark) {
        editModel.value.remark = String(apiConfig.value.baseUrl)
      }
    } else if (!apiConfig.value.loginUrl || !apiConfig.value.userName) {
      message.warning('请填写登录接口地址与登录账号')
      return
    }
    editModel.value.configCipher = JSON.stringify(apiConfig.value)
  }

  saving.value = true
  try {
    if (!isApi) {
      editModel.value.jdbcUrl = previewJdbcUrl.value
    }
    if (isEdit.value && editModel.value.id) {
      await updateDatasource(editModel.value.id, editModel.value)
      message.success('连接已更新')
    } else {
      await saveDatasource(editModel.value)
      message.success('连接已新增')
    }
    editModalVisible.value = false
    await fetchList()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (id?: string) => {
  if (!id) return
  await deleteDatasource(id)
  message.success('连接已删除')
  await fetchList()
}

defineExpose({ open })
</script>

<style scoped>
.section-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0 16px;
}
.section-title {
  font-weight: 600;
  font-size: 14px;
  color: #1f1f1f;
  white-space: nowrap;
}
.section-line {
  flex: 1;
  height: 1px;
  background: #f0f0f0;
}
.jdbc-preview-box {
  background: #f5f5f5;
  border: 1px dashed #d9d9d9;
  border-radius: 4px;
  padding: 8px 12px;
  margin-top: 8px;
}
.preview-label {
  font-size: 11px;
  color: #8c8c8c;
  margin-bottom: 4px;
}
.preview-url {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 12px;
  color: #0958d9;
  word-break: break-all;
}
</style>
