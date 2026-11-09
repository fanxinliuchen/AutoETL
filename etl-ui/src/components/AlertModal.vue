<template>
  <a-modal
    v-model:open="visible"
    title="告警通道管理 (企微 / 钉钉 / 飞书)"
    width="750"
    :footer="null"
    destroy-on-close
  >
    <div style="margin-bottom: 12px; display: flex; justify-content: space-between">
      <span>已配置的机器人与邮件告警通道:</span>
      <a-button type="primary" size="small" @click="openEdit({})">
        <plus-outlined /> 新增告警通道
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
        <template v-if="column.dataIndex === 'channelType'">
          <a-tag :color="getTypeColor(record.channelType)">
            {{ record.channelType }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'status'">
          <a-badge :status="record.status === 1 ? 'success' : 'default'" :text="record.status === 1 ? '启用' : '禁用'" />
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确定删除该告警通道？" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 子表单弹窗 -->
    <a-modal
      v-model:open="editModalVisible"
      :title="isEdit ? '编辑告警通道' : '新增告警通道'"
      @ok="handleSave"
      :confirm-loading="saving"
    >
      <a-form layout="vertical">
        <a-form-item label="通道名称" required>
          <a-input v-model:value="editModel.channelName" placeholder="例如：数据仓库报警-企微群" />
        </a-form-item>
        <a-form-item label="通道类型" required>
          <a-select v-model:value="editModel.channelType">
            <a-select-option value="WECHAT_WORK">企业微信机器人 Webhook</a-select-option>
            <a-select-option value="DINGTALK">钉钉机器人 Webhook</a-select-option>
            <a-select-option value="FEISHU">飞书机器人 Webhook</a-select-option>
            <a-select-option value="EMAIL">SMTP 邮件通知</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="editModel.channelType !== 'EMAIL'" label="Webhook URL" required>
          <a-input v-model:value="editModel.webhookUrl" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx" />
        </a-form-item>
        <a-form-item v-else label="收件人列表 (逗号分隔)" required>
          <a-input v-model:value="editModel.emailReceivers" placeholder="dev@example.com, dba@example.com" />
        </a-form-item>
        <a-form-item label="状态">
          <a-switch
            :checked="editModel.status === 1"
            checked-children="正常"
            un-checked-children="停用"
            @change="(val: boolean) => editModel.status = val ? 1 : 0"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { getAlertConfigList, saveAlertConfig, updateAlertConfig, deleteAlertConfig } from '../api/alert'
import type { EtlAlertConfig } from '../types'

const visible = ref(false)
const loading = ref(false)
const list = ref<EtlAlertConfig[]>([])

const editModalVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const editModel = ref<Partial<EtlAlertConfig>>({})

const columns = [
  { title: '通道名称', dataIndex: 'channelName' },
  { title: '类型', dataIndex: 'channelType', width: 130 },
  { title: 'Webhook URL', dataIndex: 'webhookUrl', ellipsis: true },
  { title: '状态', dataIndex: 'status', width: 80 },
  { title: '操作', dataIndex: 'action', width: 110 }
]

const getTypeColor = (type: string) => {
  switch (type) {
    case 'WECHAT_WORK': return 'green'
    case 'DINGTALK': return 'blue'
    case 'FEISHU': return 'cyan'
    default: return 'orange'
  }
}

const open = () => {
  visible.value = true
  fetchList()
}

const fetchList = async () => {
  loading.value = true
  try {
    list.value = await getAlertConfigList()
  } finally {
    loading.value = false
  }
}

const openEdit = (record: Partial<EtlAlertConfig>) => {
  isEdit.value = !!record.id
  editModel.value = {
    channelType: 'WECHAT_WORK',
    status: 1,
    ...record
  }
  editModalVisible.value = true
}

const handleSave = async () => {
  if (!editModel.value.channelName) {
    message.warning('请输入通道名称')
    return
  }
  saving.value = true
  try {
    if (isEdit.value && editModel.value.id) {
      await updateAlertConfig(editModel.value.id, editModel.value)
      message.success('更新告警通道成功')
    } else {
      await saveAlertConfig(editModel.value)
      message.success('新增告警通道成功')
    }
    editModalVisible.value = false
    await fetchList()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (id?: string) => {
  if (!id) return
  await deleteAlertConfig(id)
  message.success('告警通道已删除')
  await fetchList()
}

defineExpose({ open })
</script>
