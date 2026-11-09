<template>
  <a-modal
    v-model:open="visible"
    title="数据源管理 (MySQL & 数仓)"
    width="860"
    :footer="null"
    destroy-on-close
  >
    <div style="margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center">
      <span style="color: #666">管理系统对接的业务数据源与数仓连接：</span>
      <a-button type="primary" size="small" @click="openEdit({})">
        <plus-outlined /> 新增数据源
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
        <template v-else-if="column.dataIndex === 'dsType'">
          <a-tag :color="record.dsType === 'MYSQL' ? 'blue' : 'purple'">
            {{ record.dsType === 'MYSQL' ? '业务源库' : '目标数仓' }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'connectionInfo'">
          <span>{{ record.host || '-' }}:{{ record.port || 3306 }} / <strong>{{ record.databaseName || '-' }}</strong></span>
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确定删除该数据源？" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 子表单弹窗: 模仿图1结构化配置 -->
    <a-modal
      v-model:open="editModalVisible"
      :title="isEdit ? '编辑数据源连接' : '新增数据源连接'"
      width="640"
      @ok="handleSave"
      :confirm-loading="saving"
      destroy-on-close
    >
      <a-form layout="vertical" class="datasource-config-form">
        <!-- 头部信息 -->
        <a-row :gutter="16">
          <a-col :span="14">
            <a-form-item label="数据源名称" required>
              <a-input v-model:value="editModel.dsName" placeholder="例如：数仓原始层、ERP生产业务库" />
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="数据源用途类型" required>
              <a-radio-group v-model:value="editModel.dsType" button-style="solid">
                <a-radio-button value="MYSQL">业务源库</a-radio-button>
                <a-radio-button value="MYSQL_DW">目标数仓</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>

        <!-- 基础配置分界线 (仿图1) -->
        <div class="section-divider">
          <span class="section-title">基础配置</span>
          <span class="section-line"></span>
        </div>

        <!-- 驱动 -->
        <a-form-item label="驱动" required>
          <a-select v-model:value="editModel.driverClass">
            <a-select-option value="com.mysql.cj.jdbc.Driver">com.mysql.cj.jdbc.Driver (MySQL 8.0+ 推荐)</a-select-option>
            <a-select-option value="com.mysql.jdbc.Driver">com.mysql.jdbc.Driver (MySQL 5.7 旧版兼容)</a-select-option>
          </a-select>
        </a-form-item>

        <!-- 数据库名称 -->
        <a-form-item label="数据库名称" required>
          <a-input v-model:value="editModel.databaseName" placeholder="例如：ods、dhp_etl、erp_db" />
        </a-form-item>

        <!-- 主机与端口 -->
        <a-row :gutter="16">
          <a-col :span="16">
            <a-form-item label="主机 (Host / IP)" required>
              <a-input v-model:value="editModel.host" placeholder="例如：127.0.0.1、10.10.12.3" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="端口" required>
              <a-input-number v-model:value="editModel.port" :min="1" :max="65535" style="width: 100%" placeholder="3306" />
            </a-form-item>
          </a-col>
        </a-row>

        <!-- 用户名与密码 -->
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="用户名" required>
              <a-input v-model:value="editModel.username" placeholder="root" />
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

        <!-- 编码 -->
        <a-form-item label="编码" required>
          <a-select v-model:value="editModel.encoding">
            <a-select-option value="UTF-8">UTF-8</a-select-option>
            <a-select-option value="GBK">GBK</a-select-option>
            <a-select-option value="gb2312">gb2312</a-select-option>
            <a-select-option value="latin1">latin1</a-select-option>
          </a-select>
        </a-form-item>

        <!-- 自动合成的 JDBC 连接预览 -->
        <div class="jdbc-preview-box">
          <div class="preview-label">自动生成 JDBC 连接串预览:</div>
          <code class="preview-url">{{ previewJdbcUrl }}</code>
        </div>

        <div style="margin-top: 16px; display: flex; align-items: center">
          <a-button :loading="testing" @click="testConn">
            <api-outlined /> 测试连通性
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
  getDatasourceList,
  saveDatasource,
  updateDatasource,
  deleteDatasource,
  testDatasourceConnection
} from '../api/datasource'
import type { EtlDatasource } from '../types'

const visible = ref(false)
const loading = ref(false)
const list = ref<EtlDatasource[]>([])

const editModalVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref('')
const testSuccess = ref(false)
const editModel = ref<Partial<EtlDatasource>>({})

const columns = [
  { title: '数据源名称', dataIndex: 'dsName', width: 140 },
  { title: '类型', dataIndex: 'dsType', width: 90 },
  { title: '连接目标 (主机:端口/库名)', dataIndex: 'connectionInfo', ellipsis: true },
  { title: '用户名', dataIndex: 'username', width: 90 },
  { title: '编码', dataIndex: 'encoding', width: 80 },
  { title: '操作', dataIndex: 'action', width: 110 }
]

// 根据主机、端口、库名、编码动态拼接 JDBC URL 预览
const previewJdbcUrl = computed(() => {
  const host = editModel.value.host?.trim() || '127.0.0.1'
  const port = editModel.value.port || 3306
  const db = editModel.value.databaseName?.trim() || 'database'
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
    list.value = await getDatasourceList()
  } finally {
    loading.value = false
  }
}

const openEdit = (record: Partial<EtlDatasource>) => {
  isEdit.value = !!record.id
  editModel.value = {
    dsType: 'MYSQL',
    driverClass: 'com.mysql.cj.jdbc.Driver',
    host: '127.0.0.1',
    port: 3306,
    databaseName: '',
    encoding: 'UTF-8',
    username: 'root',
    ...record,
    passwordCipher: ''
  }
  // 如果已有记录没有单独拆分字段但有 jdbcUrl，尝试从 jdbcUrl 解析
  if (record.jdbcUrl && (!record.host || !record.databaseName)) {
    try {
      const match = record.jdbcUrl.match(/jdbc:mysql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/)
      if (match) {
        editModel.value.host = match[1]
        editModel.value.port = match[2] ? parseInt(match[2]) : 3306
        editModel.value.databaseName = match[3]
      }
    } catch (e) {}
  }

  testResult.value = ''
  editModalVisible.value = true
}

const testConn = async () => {
  if (!editModel.value.host || !editModel.value.databaseName || !editModel.value.username) {
    message.warning('请先完整填写主机、数据库名称和用户名')
    return
  }
  testing.value = true
  testResult.value = ''
  try {
    const res = await testDatasourceConnection({
      id: editModel.value.id,
      driverClass: editModel.value.driverClass,
      host: editModel.value.host,
      port: editModel.value.port,
      databaseName: editModel.value.databaseName,
      encoding: editModel.value.encoding,
      username: editModel.value.username,
      password: editModel.value.passwordCipher
    })
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
  if (!editModel.value.dsName || !editModel.value.host || !editModel.value.databaseName || !editModel.value.username) {
    message.warning('请将数据源名称、主机、数据库名称及用户名填写完整')
    return
  }
  saving.value = true
  try {
    // 确保合成的 jdbcUrl 同步保存
    editModel.value.jdbcUrl = previewJdbcUrl.value

    if (isEdit.value && editModel.value.id) {
      await updateDatasource(editModel.value.id, editModel.value)
      message.success('更新数据源成功')
    } else {
      await saveDatasource(editModel.value)
      message.success('新增数据源成功')
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
  message.success('数据源已删除')
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
