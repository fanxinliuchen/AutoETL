<template>
  <div class="folder-tree-container">
    <div class="folder-header">
      <span class="folder-title">业务目录分类</span>
      <a-button type="link" size="small" @click="openAddModal(0)">
        <plus-outlined /> 新增根目录
      </a-button>
    </div>

    <div class="folder-search">
      <a-input-search
        v-model:value="searchValue"
        placeholder="搜索目录..."
        size="small"
        allow-clear
      />
    </div>

    <div class="all-node" :class="{ active: selectedKey === '' }" @click="selectRoot">
      <folder-outlined /> 全部流程
    </div>

    <div class="tree-wrapper">
      <a-tree
        v-if="treeData.length > 0"
        :tree-data="filteredTreeData"
        :selected-keys="selectedKey ? [selectedKey] : []"
        :default-expand-all="true"
        @select="onSelect"
      >
        <template #title="{ key, title }">
          <div class="tree-node-title">
            <span class="node-text">{{ title }}</span>
            <span class="node-actions" @click.stop>
              <a-dropdown :trigger="['click']">
                <more-outlined class="action-btn" />
                <template #overlay>
                  <a-menu>
                    <a-menu-item @click="openAddModal(key)">
                      <plus-outlined /> 新增子目录
                    </a-menu-item>
                    <a-menu-item @click="openEditModal(key, title)">
                      <edit-outlined /> 重命名
                    </a-menu-item>
                    <a-menu-item danger @click="handleDelete(key)">
                      <delete-outlined /> 删除
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </span>
          </div>
        </template>
      </a-tree>
      <a-empty v-else description="暂无目录" :image="simpleImage" />
    </div>

    <!-- 目录编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="modalType === 'add' ? '新增目录' : '编辑目录'"
      @ok="handleModalSubmit"
      :confirm-loading="modalSubmitting"
      destroy-on-close
    >
      <a-form :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
        <a-form-item label="目录名称" required>
          <a-input v-model:value="formModel.folderName" placeholder="例如：财务中心、ERP物料" />
        </a-form-item>
        <a-form-item label="排序序号">
          <a-input-number v-model:value="formModel.folderSort" :min="0" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  FolderOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  MoreOutlined
} from '@ant-design/icons-vue'
import { Empty, message, Modal } from 'ant-design-vue'
import { getFolderTree, saveFolder, updateFolder, deleteFolder } from '../api/folder'

const emit = defineEmits<{
  (e: 'select', folderId: string): void
}>()

const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE
const treeData = ref<any[]>([])
const selectedKey = ref<string>('')
const searchValue = ref<string>('')

// 弹窗表单状态
const modalVisible = ref(false)
const modalSubmitting = ref(false)
const modalType = ref<'add' | 'edit'>('add')
const currentParentId = ref<string>('0')
const currentEditId = ref<string>('')
const formModel = ref({
  folderName: '',
  folderSort: 1
})

const loadTree = async () => {
  try {
    const list = await getFolderTree()
    treeData.value = list
  } catch (e) {
    // handled in interceptor
  }
}

onMounted(() => {
  loadTree()
})

const selectRoot = () => {
  selectedKey.value = ''
  emit('select', '')
}

const onSelect = (keys: any[]) => {
  if (keys && keys.length > 0) {
    selectedKey.value = String(keys[0])
    emit('select', selectedKey.value)
  }
}

const filteredTreeData = computed(() => {
  if (!searchValue.value) return treeData.value
  const filterNodes = (nodes: any[]): any[] => {
    return nodes
      .map(n => {
        const match = n.title.toLowerCase().includes(searchValue.value.toLowerCase())
        const sub = n.children ? filterNodes(n.children) : []
        if (match || sub.length > 0) {
          return { ...n, children: sub }
        }
        return null
      })
      .filter(Boolean)
  }
  return filterNodes(treeData.value)
})

const openAddModal = (parentId: any) => {
  modalType.value = 'add'
  currentParentId.value = String(parentId)
  formModel.value = { folderName: '', folderSort: 1 }
  modalVisible.value = true
}

const openEditModal = (id: any, title: string) => {
  modalType.value = 'edit'
  currentEditId.value = String(id)
  formModel.value = { folderName: title, folderSort: 1 }
  modalVisible.value = true
}

const handleModalSubmit = async () => {
  if (!formModel.value.folderName.trim()) {
    message.warning('请输入目录名称')
    return
  }
  modalSubmitting.value = true
  try {
    if (modalType.value === 'add') {
      await saveFolder({
        parentId: currentParentId.value,
        folderName: formModel.value.folderName,
        folderSort: formModel.value.folderSort
      })
      message.success('新增目录成功')
    } else {
      await updateFolder(currentEditId.value, {
        folderName: formModel.value.folderName,
        folderSort: formModel.value.folderSort
      })
      message.success('更新目录成功')
    }
    modalVisible.value = false
    await loadTree()
  } finally {
    modalSubmitting.value = false
  }
}

const handleDelete = (id: any) => {
  Modal.confirm({
    title: '确认删除该目录？',
    content: '删除后该目录下的流程将被移入无目录分组。',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await deleteFolder(String(id))
      message.success('目录已删除')
      if (selectedKey.value === String(id)) {
        selectRoot()
      }
      await loadTree()
    }
  })
}
</script>

<style scoped>
.folder-tree-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-right: 1px solid #f0f0f0;
  padding: 16px 12px;
}
.folder-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.folder-title {
  font-weight: 600;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.85);
}
.folder-search {
  margin-bottom: 12px;
}
.all-node {
  padding: 8px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(0, 0, 0, 0.85);
  transition: all 0.2s;
}
.all-node:hover {
  background: #f5f5f5;
}
.all-node.active {
  background: #e6f4ff;
  color: #1677ff;
  font-weight: 600;
}
.tree-wrapper {
  flex: 1;
  overflow-y: auto;
}
.tree-node-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding-right: 8px;
}
.node-actions {
  display: none;
}
.tree-node-title:hover .node-actions {
  display: inline-block;
}
.action-btn {
  color: #8c8c8c;
  padding: 2px 4px;
}
.action-btn:hover {
  color: #1677ff;
}
</style>
