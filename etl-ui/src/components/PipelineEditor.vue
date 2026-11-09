<template>
  <div class="pipeline-editor-wrapper">
    <!-- 顶部工作台导航条 -->
    <div class="editor-header-bar">
      <div class="header-left">
        <a-button @click="handleBack">
          <arrow-left-outlined /> 返回流程列表
        </a-button>
        <div class="title-meta">
          <span class="editor-title">{{ isEdit ? '编辑 ETL 流程' : '新建 ETL 流程' }}</span>
          <a-tag v-if="formData.pipelineName" color="blue" class="title-tag">
            {{ formData.pipelineName }}
          </a-tag>
          <a-tag v-if="formData.pipelineCode" color="purple" class="title-tag">
            编码: {{ formData.pipelineCode }}
          </a-tag>
          <a-tag v-if="isDraft" color="orange" class="title-tag">
            草稿 / 配置中
          </a-tag>
          <a-tag v-else-if="formData.pipelineCode" color="green" class="title-tag">
            已就绪
          </a-tag>
        </div>
      </div>
      <div class="header-right">
        <a-space>
          <a-button @click="handleReset">重置</a-button>
          <a-button :loading="submitting" @click="handleSaveStep(false)">
            <save-outlined /> 保存当前进度 (草稿)
          </a-button>
          <a-button type="primary" :loading="submitting" @click="handleCompleteSave">
            <check-outlined /> 完成并保存全部
          </a-button>
        </a-space>
      </div>
    </div>

    <!-- 5大子Tab设计器主体 -->
    <div class="editor-body">
      <a-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        layout="vertical"
        class="editor-form"
      >
        <a-tabs v-model:activeKey="activeTab" type="card" class="editor-tabs">
          <!-- Tab 1: 基础信息 -->
          <a-tab-pane key="1" tab="📋 1. 基础信息">
            <div class="tab-pane-content">
              <a-card :bordered="false" class="pane-card">
                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="所属业务目录" name="folderId" required>
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
                    <a-form-item label="流程名称" name="pipelineName" required>
                      <a-input
                        v-model:value="formData.pipelineName"
                        placeholder="例如：金蝶ERP物料收发明细定时同步"
                      />
                    </a-form-item>
                  </a-col>
                </a-row>

                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="数据输入源类型" name="sourceType" required>
                      <a-radio-group v-model:value="formData.sourceType" button-style="solid">
                        <a-radio-button value="MYSQL">MySQL 数据库</a-radio-button>
                        <a-radio-button value="WEB_API">WebAPI 接口 (支持金蝶/ERP)</a-radio-button>
                      </a-radio-group>
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="流程说明">
                      <a-input
                        v-model:value="formData.description"
                        placeholder="业务背景、同步频率、责任人等说明"
                      />
                    </a-form-item>
                  </a-col>
                </a-row>
              </a-card>

              <!-- 底部导航条 -->
              <div class="tab-footer-nav">
                <div></div>
                <a-space>
                  <a-button :loading="submitting" @click="handleSaveStep(false)">
                    <save-outlined /> 保存当前进度
                  </a-button>
                  <a-button type="primary" @click="goToNextTab('2')">
                    下一步：配置数据抽取 <arrow-right-outlined />
                  </a-button>
                </a-space>
              </div>
            </div>
          </a-tab-pane>

          <!-- Tab 2: 数据抽取配置 (Source) - 强化金蝶等复杂WebAPI -->
          <a-tab-pane key="2" tab="📥 2. 数据抽取配置 (Source)">
            <div class="tab-pane-content">
              <!-- MySQL 源模式 -->
              <div v-if="formData.sourceType === 'MYSQL'">
                <a-card :bordered="false" class="pane-card">
                  <a-form-item label="来源 MySQL 业务数据库" name="sourceDsId" required>
                    <a-select v-model:value="formData.sourceDsId" placeholder="选择抽取来源库" allow-clear>
                      <a-select-option v-for="ds in datasourceList" :key="ds.id" :value="ds.id">
                        {{ ds.dsName }} ({{ ds.host ? `${ds.host}:${ds.port || 3306}/${ds.databaseName || ''}` : ds.jdbcUrl }})
                      </a-select-option>
                    </a-select>
                  </a-form-item>

                  <a-form-item label="抽取 SQL 查询语句" name="sourceQuerySql" required>
                    <SqlEditor
                      v-model="formData.sourceQuerySql"
                      placeholder="SELECT id, material_code, name, price, update_time FROM t_material WHERE update_time >= '${yesterday}'"
                    />
                  </a-form-item>
                </a-card>
              </div>

              <!-- WebAPI 源模式 (金蝶云星空 / 复杂两步会话鉴权) -->
              <div v-else>
                <!-- 顶部两步抽取流程图可视化 (流水线节点指示) -->
                <div class="api-flow-pipeline">
                  <div class="flow-step-node" :class="{ active: formData.apiAuthType !== 'NONE' }">
                    <div class="node-icon"><key-outlined /></div>
                    <div class="node-text">
                      <div class="node-title">步骤 1: 会话鉴权登录 (Auth)</div>
                      <div class="node-desc">
                        {{ formData.apiAuthType === 'KINGDEE_K3' ? '金蝶 ValidateUser 获取 KDS' : (formData.apiAuthType === 'CUSTOM_LOGIN' ? '通用前置登录获取凭证' : '无前置登录 / 直连') }}
                      </div>
                    </div>
                  </div>
                  <div class="flow-step-arrow">
                    <div class="arrow-line"></div>
                    <span class="arrow-badge">
                      {{ formData.apiAuthType === 'KINGDEE_K3' ? '自动捕获 Cookie (kdservice-sessionid)' : (formData.apiAuthType === 'CUSTOM_LOGIN' ? '自动注入 Cookie / Token' : '无凭证依赖') }}
                    </span>
                  </div>
                  <div class="flow-step-node active">
                    <div class="node-icon"><database-outlined /></div>
                    <div class="node-text">
                      <div class="node-title">步骤 2: 单据业务抽取 (Extract)</div>
                      <div class="node-desc">
                        {{ formData.apiAuthType === 'KINGDEE_K3' ? 'ExecuteBillQuery 单据动态查询' : '执行主数据拉取' }}
                      </div>
                    </div>
                  </div>
                  <div class="flow-step-arrow">
                    <div class="arrow-line"></div>
                    <span class="arrow-badge">
                      {{ formData.apiAuthType === 'KINGDEE_K3' ? '自动二维数组对齐与结构化' : 'JsonPath 提取解析' }}
                    </span>
                  </div>
                  <div class="flow-step-node">
                    <div class="node-icon"><table-outlined /></div>
                    <div class="node-text">
                      <div class="node-title">步骤 3: 临时表暂存 (${stage_table})</div>
                      <div class="node-desc">数据落入临时表供 SQL 转换</div>
                    </div>
                  </div>
                </div>

                <!-- 鉴权模式切换选择 -->
                <a-card :bordered="false" class="pane-card" style="margin-bottom: 16px">
                  <a-form-item label="接口鉴权与前置登录方式" extra="金蝶云星空等 ERP 需先调用认证接口获取 KDS/Cookie 鉴权后才能抽取单据，本系统内置全自动两步流水线无须繁琐拖拽">
                    <a-radio-group v-model:value="formData.apiAuthType" button-style="solid" @change="onAuthTypeChange">
                      <a-radio-button value="KINGDEE_K3">
                        👑 金蝶云星空 (K/3 Cloud 会话登录 - 推荐)
                      </a-radio-button>
                      <a-radio-button value="CUSTOM_LOGIN">
                        🔌 通用前置接口登录 (双请求)
                      </a-radio-button>
                      <a-radio-button value="NONE">
                        🌐 标准单接口直连 (无前置登录)
                      </a-radio-button>
                    </a-radio-group>
                  </a-form-item>
                </a-card>

                <!-- 模式 A: 金蝶云星空 K/3 Cloud 模式 -->
                <div v-if="formData.apiAuthType === 'KINGDEE_K3'">
                  <!-- 步骤一：金蝶登录认证配置 -->
                  <a-card
                    title="🔑 步骤一：金蝶云星空登录认证配置 (前置获取 KDS 会话凭证)"
                    :bordered="false"
                    class="pane-card module-card"
                    style="margin-bottom: 16px"
                  >
                    <template #extra>
                      <a-space>
                        <a-button
                          size="small"
                          type="link"
                          :loading="savingCredential"
                          @click="handleSaveCredential"
                        >
                          💾 保存我的凭证
                        </a-button>
                        <a-button
                          size="small"
                          type="link"
                          :loading="loadingCredential"
                          @click="handleLoadCredential"
                        >
                          📥 载入默认凭证
                        </a-button>
                        <a-button
                          type="primary"
                          ghost
                          size="small"
                          :loading="testingLogin"
                          @click="handleTestLogin"
                        >
                          <key-outlined /> 测试登录并验证 KDS 凭证
                        </a-button>
                      </a-space>
                    </template>

                    <a-row :gutter="16">
                      <a-col :span="24">
                        <a-form-item label="金蝶登录验证接口 (AuthService.ValidateUser)" required>
                          <a-input
                            v-model:value="kingdeeConfig.loginUrl"
                            placeholder="例如：http://10.10.12.3/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.ValidateUser.common.kdsvc"
                            @change="onKingdeeLoginUrlChange"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="6">
                        <a-form-item label="数据中心/账套代码 (acctID)" required>
                          <a-input
                            v-model:value="kingdeeConfig.acctID"
                            placeholder="例如：603c4f940a4306"
                            @change="syncKingdeeLoginBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="6">
                        <a-form-item label="登录用户名 (userName)" required>
                          <a-input
                            v-model:value="kingdeeConfig.userName"
                            placeholder="例如：administrator"
                            @change="syncKingdeeLoginBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="6">
                        <a-form-item label="登录密码 (password)" required>
                          <a-input-password
                            v-model:value="kingdeeConfig.password"
                            placeholder="请输入登录密码"
                            @change="syncKingdeeLoginBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="6">
                        <a-form-item label="多语言环境代码 (lcid)">
                          <a-input-number
                            v-model:value="kingdeeConfig.lcid"
                            placeholder="2052 (中文简体)"
                            style="width: 100%"
                            @change="syncKingdeeLoginBody"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <!-- 测试登录状态反馈 -->
                    <div v-if="loginTestResult" style="margin-top: 8px">
                      <a-alert
                        v-if="loginTestResult.success"
                        type="success"
                        show-icon
                        :message="`🎉 ${loginTestResult.message || '登录测试成功！'}`"
                      >
                        <template #description>
                          <div style="font-family: monospace; font-size: 12px; margin-top: 4px; word-break: break-all">
                            <strong>已捕获会话 Cookie:</strong> {{ loginTestResult.cookie || '已自动获取 Session' }}
                          </div>
                        </template>
                      </a-alert>
                      <a-alert
                        v-else
                        type="error"
                        show-icon
                        :message="`❌ ${loginTestResult.message || '登录测试失败'}`"
                      />
                    </div>
                  </a-card>

                  <!-- 步骤二：金蝶单据数据抽取配置 -->
                  <a-card
                    title="📥 步骤二：金蝶单据数据抽取配置 (DynamicFormService.ExecuteBillQuery)"
                    :bordered="false"
                    class="pane-card module-card"
                  >
                    <template #extra>
                      <a-space>
                        <span style="font-size: 12px; color: #8c8c8c">单据快捷模板:</span>
                        <a-button size="small" @click="loadKingdeeBillTemplate('PUR_PurchaseOrder')">
                          📦 采购订单
                        </a-button>
                        <a-button size="small" @click="loadKingdeeBillTemplate('SAL_SaleOrder')">
                          💰 销售订单
                        </a-button>
                        <a-button size="small" @click="loadKingdeeBillTemplate('BD_MATERIAL')">
                          🏭 物料清单
                        </a-button>
                        <a-button size="small" :loading="savingCredential" @click="handleSaveCredential">
                          💾 保存为默认
                        </a-button>
                        <a-button size="small" @click="formatJsonBody">
                          🪄 格式化 JSON
                        </a-button>
                        <a-button
                          type="primary"
                          size="small"
                          :loading="previewingExtract"
                          @click="handlePreviewExtract"
                        >
                          <table-outlined /> 测试抽取与数据预览
                        </a-button>
                      </a-space>
                    </template>

                    <a-row :gutter="16">
                      <a-col :span="4">
                        <a-form-item label="请求方式">
                          <a-input value="POST" disabled />
                        </a-form-item>
                      </a-col>
                      <a-col :span="20">
                        <a-form-item label="单据查询接口 URL (ExecuteBillQuery)" name="apiUrl" required>
                          <a-input
                            v-model:value="formData.apiUrl"
                            placeholder="例如：http://10.10.12.3/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <!-- 金蝶单据参数快捷向导表单 -->
                    <a-row :gutter="16">
                      <a-col :span="8">
                        <a-form-item label="业务对象表单标识 (FormId)" required>
                          <a-input
                            v-model:value="kingdeeQuery.formId"
                            placeholder="如 PUR_PurchaseOrder / BD_MATERIAL"
                            @change="syncKingdeeQueryToBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="16">
                        <a-form-item label="抽取字段列表" required>
                          <div class="fk-toolbar">
                            <span class="fk-count">
                              共 {{ fieldRows.length }} 个字段 · 启用 {{ enabledFieldCount }}
                            </span>
                            <a-space :size="6" wrap>
                              <input
                                ref="fileInputRef"
                                type="file"
                                accept=".xlsx,.xls"
                                style="display: none"
                                @change="onExcelFileChange"
                              />
                              <a-button size="small" @click="triggerExcelImport">
                                <file-excel-outlined /> Excel 导入
                              </a-button>
                              <a-button
                                size="small"
                                :disabled="!fieldRows.length"
                                @click="handleExportExcel"
                              >
                                <download-outlined /> 导出
                              </a-button>
                              <a-button size="small" @click="handleDownloadTemplate">
                                <file-add-outlined /> 模板
                              </a-button>
                              <a-button
                                size="small"
                                :loading="kingdeeFieldsLoading"
                                @click="handlePullKingdeeFields"
                              >
                                <api-outlined /> 从金蝶拉字段
                              </a-button>
                              <a-button size="small" @click="handleInferTypes">
                                <bulb-outlined /> 推断类型
                              </a-button>
                              <a-button size="small" @click="pasteModalVisible = true">
                                粘贴导入
                              </a-button>
                              <a-button size="small" type="link" @click="toggleTextMode">
                                {{ textMode ? '切换到表格' : '文本模式' }}
                              </a-button>
                            </a-space>
                          </div>
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <div class="fk-panel">
                      <div v-if="!textMode">
                        <a-table
                          :columns="fieldColumns"
                          :data-source="fieldRows"
                          size="small"
                          row-key="rid"
                          :pagination="false"
                          :scroll="{ y: 260 }"
                          bordered
                        >
                          <template #bodyCell="{ column, record, index }">
                            <template v-if="column.dataIndex === 'idx'">
                              {{ index + 1 }}
                            </template>
                            <template v-else-if="column.dataIndex === 'fieldKey'">
                              <a-input
                                v-model:value="record.fieldKey"
                                size="small"
                                placeholder="如 FBillNo"
                                class="fk-input-mono"
                                @change="onFieldRowChanged"
                              />
                            </template>
                            <template v-else-if="column.dataIndex === 'label'">
                              <a-input
                                v-model:value="record.label"
                                size="small"
                                placeholder="选填"
                                @change="onFieldRowChanged"
                              />
                            </template>
                            <template v-else-if="column.dataIndex === 'type'">
                              <a-tooltip title="保存后按该类型创建临时表列（未指定按 TEXT）；类型与数据不符会导致写入失败">
                                <a-select
                                  v-model:value="record.type"
                                  size="small"
                                  :options="fieldTypeSelectOptions"
                                  style="width: 100%"
                                  @change="onFieldRowChanged"
                                />
                              </a-tooltip>
                            </template>
                            <template v-else-if="column.dataIndex === 'enabled'">
                              <a-switch
                                v-model:checked="record.enabled"
                                size="small"
                                @change="onFieldRowChanged"
                              />
                            </template>
                            <template v-else-if="column.dataIndex === 'action'">
                              <a-space :size="0">
                                <a-button
                                  type="link"
                                  size="small"
                                  :disabled="index === 0"
                                  @click="moveFieldRow(index, -1)"
                                >
                                  上移
                                </a-button>
                                <a-button
                                  type="link"
                                  size="small"
                                  :disabled="index === fieldRows.length - 1"
                                  @click="moveFieldRow(index, 1)"
                                >
                                  下移
                                </a-button>
                                <a-button
                                  type="link"
                                  size="small"
                                  danger
                                  @click="removeFieldRow(index)"
                                >
                                  删除
                                </a-button>
                              </a-space>
                            </template>
                          </template>
                        </a-table>
                        <div class="fk-foot">
                          <a-button type="dashed" size="small" block @click="addFieldRow">
                            <plus-outlined /> 添加字段
                          </a-button>
                        </div>
                      </div>
                      <div v-else>
                        <a-textarea
                          v-model:value="kingdeeQuery.fieldKeys"
                          :rows="4"
                          class="code-textarea"
                          placeholder="例如：FID,FBillNo,FDate,FSupplierId.FNumber,FDocumentStatus"
                          @change="onFieldKeysTextChange"
                        />
                      </div>
                      <div class="fk-hint">
                        顺序即列序：抽取结果按 FieldKeys 下标对齐金蝶返回的二维数组。类型用于创建临时表列（未指定按 TEXT），保存后生效，改类型会在下次运行时自动重建临时表。
                      </div>
                    </div>

                    <a-modal
                      v-model:open="pasteModalVisible"
                      title="粘贴导入字段"
                      ok-text="解析并导入"
                      cancel-text="取消"
                      @ok="confirmPasteImport"
                    >
                      <p style="font-size: 12px; color: #8c8c8c; margin-bottom: 8px">
                        每行一个字段，可用制表符或逗号分隔中文名；也可直接粘贴一整串逗号分隔的字段名。
                      </p>
                      <a-textarea
                        v-model:value="pasteText"
                        :rows="10"
                        class="code-textarea"
                        :placeholder="pastePlaceholder"
                      />
                    </a-modal>

                    <a-modal
                      v-model:open="kingdeeFieldModalVisible"
                      title="从金蝶拉取字段"
                      :footer="null"
                      width="820px"
                    >
                      <div v-if="kingdeeFieldsLoading" class="kf-loading">
                        <a-spin />
                        <span>正在登录金蝶并读取表单元数据，请稍候…</span>
                      </div>
                      <a-alert
                        v-else-if="kingdeeFieldError"
                        type="error"
                        show-icon
                        :message="kingdeeFieldError"
                      />
                      <template v-else>
                        <div class="kf-bar">
                          <span class="fk-count">
                            共 {{ kingdeeFields.length }} 个字段 · 已选 {{ kingdeeCheckedCount }}
                          </span>
                          <a-space :size="6">
                            <a-button size="small" @click="toggleAllKingdeeFields(true)">全选</a-button>
                            <a-button size="small" @click="toggleAllKingdeeFields(false)">清空</a-button>
                          </a-space>
                        </div>
                        <a-table
                          :columns="kingdeeColumns"
                          :data-source="kingdeeFields"
                          size="small"
                          row-key="key"
                          :pagination="false"
                          :scroll="{ y: 380 }"
                          bordered
                        >
                          <template #bodyCell="{ column, record }">
                            <template v-if="column.dataIndex === 'checked'">
                              <a-checkbox v-model:checked="record.checked" />
                            </template>
                            <template v-else-if="column.dataIndex === 'key'">
                              <span class="fk-mono">{{ record.key }}</span>
                            </template>
                            <template v-else-if="column.dataIndex === 'rawType'">
                              <span class="kf-raw">{{ record.rawType || '-' }}</span>
                            </template>
                            <template v-else-if="column.dataIndex === 'type'">
                              <a-select
                                v-model:value="record.type"
                                size="small"
                                :options="fieldTypeSelectOptions"
                                style="width: 100%"
                              />
                            </template>
                          </template>
                        </a-table>
                        <div class="kf-footer">
                          <span class="fk-hint-inline">
                            首次拉取会把「清单里还没有的字段」预勾选，已有字段保持不勾选，避免覆盖手工调整
                          </span>
                          <a-space>
                            <a-button @click="kingdeeFieldModalVisible = false">取消</a-button>
                            <a-button @click="applyKingdeeFields('append')">追加所选</a-button>
                            <a-button type="primary" @click="applyKingdeeFields('replace')">
                              覆盖为所选
                            </a-button>
                          </a-space>
                        </div>
                      </template>
                    </a-modal>

                    <a-row :gutter="16">
                      <a-col :span="12">
                        <a-form-item label="过滤条件 (FilterString)">
                          <a-input
                            v-model:value="kingdeeQuery.filterString"
                            placeholder="例如：FDate >= '${yesterday}' AND FDocumentStatus = 'C'"
                            @change="syncKingdeeQueryToBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="6">
                        <a-form-item label="排序规则 (OrderString)">
                          <a-input
                            v-model:value="kingdeeQuery.orderString"
                            placeholder="例如：FID DESC"
                            @change="syncKingdeeQueryToBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="6">
                        <a-form-item label="单次抓取上限 (Limit)">
                          <a-input-number
                            v-model:value="kingdeeQuery.limit"
                            :min="1"
                            :max="10000"
                            style="width: 100%"
                            @change="syncKingdeeQueryToBody"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="24">
                        <a-form-item label="请求体 Body (JSON 格式完整结构)">
                          <template #extra>
                            <span class="field-hint">系统已根据上方字段自动同步为金蝶标准 JSON 结构，支持在此自由微调扩展参数</span>
                          </template>
                          <a-textarea
                            v-model:value="formData.apiBody"
                            :rows="7"
                            class="code-textarea"
                            :placeholder="bodyPlaceholder"
                            @change="syncBodyToKingdeeQuery"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <!-- 高级配置：分页取数 -->
                    <a-collapse v-model:activeKey="advancedActiveKeys" :bordered="false" class="adv-collapse">
                      <a-collapse-panel key="pagination" header="高级配置：分页取数（数据量大时按偏移量翻页）">
                        <a-row :gutter="16">
                          <a-col :span="6">
                            <a-form-item label="分页取数">
                              <a-switch
                                v-model:checked="pagination.enabled"
                                checked-children="已开启"
                                un-checked-children="未开启"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="每页条数 (limit)">
                              <a-input-number
                                v-model:value="pagination.pageSize"
                                :min="1"
                                :max="10000"
                                :disabled="!pagination.enabled"
                                style="width: 100%"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="最多取数次数">
                              <a-input-number
                                v-model:value="pagination.maxPages"
                                :min="1"
                                :max="10000"
                                :disabled="!pagination.enabled"
                                style="width: 100%"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="请求间隔 (ms，限速)">
                              <a-input-number
                                v-model:value="pagination.intervalMs"
                                :min="0"
                                :max="60000"
                                :step="50"
                                :disabled="!pagination.enabled"
                                style="width: 100%"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                        </a-row>

                        <a-row :gutter="16">
                          <a-col :span="6">
                            <a-form-item label="起始偏移">
                              <a-input-number
                                v-model:value="pagination.start"
                                :min="0"
                                :disabled="!pagination.enabled"
                                style="width: 100%"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="增长间隔">
                              <a-input-number
                                v-model:value="pagination.step"
                                :min="0"
                                :max="1000000"
                                :disabled="!pagination.enabled"
                                style="width: 100%"
                                @change="syncPagination"
                              />
                              <div class="fk-hint">0 表示与每页条数相同</div>
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="结束条件：本页条数 <">
                              <a-input-number
                                v-model:value="pagination.endWhenLessThan"
                                :min="0"
                                :max="10000"
                                :disabled="!pagination.enabled"
                                style="width: 100%"
                                @change="syncPagination"
                              />
                              <div class="fk-hint">0 表示与每页条数相同</div>
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="偏移量字段路径">
                              <a-input
                                v-model:value="pagination.offsetPath"
                                :disabled="!pagination.enabled"
                                placeholder="data.StartRow"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                        </a-row>

                        <a-row :gutter="16">
                          <a-col :span="6">
                            <a-form-item label="每页条数字段路径">
                              <a-input
                                v-model:value="pagination.limitPath"
                                :disabled="!pagination.enabled"
                                placeholder="data.Limit"
                                @change="syncPagination"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="18">
                            <a-form-item label="配置预览">
                              <div class="fk-hint" style="margin-top: 0; line-height: 1.9">
                                每轮把
                                <code>{{ pagination.offsetPath || 'data.StartRow' }}</code>
                                写成「起始偏移 + 第几页 × 增长间隔」，并把
                                <code>{{ pagination.limitPath || 'data.Limit' }}</code>
                                写成每页条数；某页返回条数小于结束阈值即停止。
                                金蝶 ExecuteBillQuery / BillQuery 的请求体默认就是
                                <code>data.StartRow</code> 与 <code>data.Limit</code>。
                              </div>
                            </a-form-item>
                          </a-col>
                        </a-row>
                      </a-collapse-panel>
                    </a-collapse>

                    <!-- 结果提取 JsonPath -->
                    <a-row :gutter="16">
                      <a-col :span="16">
                        <a-form-item label="结果提取 JsonPath" name="apiDataPath" required>
                          <a-input
                            v-model:value="formData.apiDataPath"
                            placeholder="$.* (金蝶标准单据直接返回二维列表)"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="8">
                        <div style="padding-top: 30px">
                          <span style="font-size: 12px; color: #8c8c8c; margin-right: 6px">常用推荐:</span>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.*'">$.* (金蝶默认)</a-tag>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.data.records'">$.data.records</a-tag>
                        </div>
                      </a-col>
                    </a-row>

                    <a-alert
                      message="💡 智能二维数组映射特性"
                      description="金蝶 ExecuteBillQuery 原生返回二维数组格式。系统抽取引擎已内置智能列对齐机制：自动将 FieldKeys 中的字段名与二维数组按序号精准匹配，还原为规整的行记录对象，并无缝加载进步骤三的临时表 ${stage_table}！"
                      type="info"
                      show-icon
                      style="margin-top: 8px"
                    />
                  </a-card>
                </div>

                <!-- 模式 B: 通用前置接口登录 (双请求) -->
                <div v-else-if="formData.apiAuthType === 'CUSTOM_LOGIN'">
                  <!-- 步骤一：通用登录认证配置 -->
                  <a-card
                    title="🔑 步骤一：通用前置接口鉴权配置 (获取 Token / Cookie)"
                    :bordered="false"
                    class="pane-card module-card"
                    style="margin-bottom: 16px"
                  >
                    <template #extra>
                      <a-button
                        type="primary"
                        ghost
                        size="small"
                        :loading="testingLogin"
                        @click="handleTestLogin"
                      >
                        <key-outlined /> 测试登录并提取凭证
                      </a-button>
                    </template>

                    <a-row :gutter="16">
                      <a-col :span="4">
                        <a-form-item label="登录请求方式">
                          <a-select v-model:value="customAuthConfig.loginMethod">
                            <a-select-option value="POST">POST</a-select-option>
                            <a-select-option value="GET">GET</a-select-option>
                          </a-select>
                        </a-form-item>
                      </a-col>
                      <a-col :span="20">
                        <a-form-item label="登录验证接口 URL" required>
                          <a-input
                            v-model:value="customAuthConfig.loginUrl"
                            placeholder="例如：https://api.example.com/oauth/token"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="10">
                        <a-form-item label="登录请求头 Headers (JSON)">
                          <a-textarea
                            v-model:value="customAuthConfig.loginHeaders"
                            :rows="5"
                            class="code-textarea"
                            placeholder='{"Content-Type": "application/json"}'
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="14">
                        <a-form-item label="登录请求体 Body (JSON)">
                          <a-textarea
                            v-model:value="customAuthConfig.loginBody"
                            :rows="5"
                            class="code-textarea"
                            placeholder='{"username": "admin", "password": "xxx"}'
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="16">
                        <a-form-item label="Token 提取 JsonPath (选填，自动从返回提取Token)" extra="如填 $.token，抽取时将自动放入 Authorization: Bearer {token}；若接口通过 Set-Cookie 鉴权则可留空">
                          <a-input
                            v-model:value="customAuthConfig.extractPath"
                            placeholder="例如：$.data.token 或 $.access_token"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <!-- 测试登录状态反馈 -->
                    <div v-if="loginTestResult" style="margin-top: 8px">
                      <a-alert
                        v-if="loginTestResult.success"
                        type="success"
                        show-icon
                        :message="`🎉 ${loginTestResult.message || '登录测试成功！'}`"
                      >
                        <template #description>
                          <div style="font-family: monospace; font-size: 12px; margin-top: 4px; word-break: break-all">
                            <span v-if="loginTestResult.cookie"><strong>Cookie:</strong> {{ loginTestResult.cookie }}<br/></span>
                            <span v-if="loginTestResult.token"><strong>Token:</strong> {{ loginTestResult.token }}</span>
                          </div>
                        </template>
                      </a-alert>
                      <a-alert
                        v-else
                        type="error"
                        show-icon
                        :message="`❌ ${loginTestResult.message || '登录测试失败'}`"
                      />
                    </div>
                  </a-card>

                  <!-- 步骤二：通用数据抽取配置 -->
                  <a-card
                    title="📥 步骤二：业务数据接口抽取配置"
                    :bordered="false"
                    class="pane-card module-card"
                  >
                    <template #extra>
                      <a-space>
                        <a-button size="small" @click="formatJsonBody">
                          🪄 格式化 JSON
                        </a-button>
                        <a-button
                          type="primary"
                          size="small"
                          :loading="previewingExtract"
                          @click="handlePreviewExtract"
                        >
                          <table-outlined /> 测试抽取与数据预览
                        </a-button>
                      </a-space>
                    </template>

                    <a-row :gutter="16">
                      <a-col :span="4">
                        <a-form-item label="请求方式" name="apiMethod" required>
                          <a-select v-model:value="formData.apiMethod">
                            <a-select-option value="POST">POST</a-select-option>
                            <a-select-option value="GET">GET</a-select-option>
                            <a-select-option value="PUT">PUT</a-select-option>
                          </a-select>
                        </a-form-item>
                      </a-col>
                      <a-col :span="20">
                        <a-form-item label="接口请求 URL" name="apiUrl" required>
                          <a-input
                            v-model:value="formData.apiUrl"
                            placeholder="例如：https://api.example.com/v1/bills/query"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="10">
                        <a-form-item label="额外请求头 Headers (JSON格式)">
                          <a-textarea
                            v-model:value="formData.apiHeaders"
                            :rows="6"
                            class="code-textarea"
                            :placeholder="headersPlaceholder"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="14">
                        <a-form-item label="请求体 Body (JSON格式)">
                          <a-textarea
                            v-model:value="formData.apiBody"
                            :rows="6"
                            class="code-textarea"
                            placeholder='{"query": "..."}'
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="16">
                        <a-form-item label="结果提取 JsonPath" name="apiDataPath" required>
                          <a-input
                            v-model:value="formData.apiDataPath"
                            placeholder="例如：$.data.records 或 $.*"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="8">
                        <div style="padding-top: 30px">
                          <span style="font-size: 12px; color: #8c8c8c; margin-right: 6px">常用推荐:</span>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.data.records'">$.data.records</a-tag>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.data.list'">$.data.list</a-tag>
                        </div>
                      </a-col>
                    </a-row>
                  </a-card>
                </div>

                <!-- 模式 C: 标准单接口直连 (无前置登录) -->
                <div v-else>
                  <a-card
                    title="🌐 标准单接口直连抽取配置"
                    :bordered="false"
                    class="pane-card module-card"
                  >
                    <template #extra>
                      <a-space>
                        <a-button size="small" @click="applyRestTemplate">
                          📋 载入 REST 分页模板
                        </a-button>
                        <a-button size="small" @click="formatJsonBody">
                          🪄 格式化 JSON
                        </a-button>
                        <a-button
                          type="primary"
                          size="small"
                          :loading="previewingExtract"
                          @click="handlePreviewExtract"
                        >
                          <table-outlined /> 测试抽取与数据预览
                        </a-button>
                      </a-space>
                    </template>

                    <a-row :gutter="16">
                      <a-col :span="4">
                        <a-form-item label="请求方式" name="apiMethod" required>
                          <a-select v-model:value="formData.apiMethod">
                            <a-select-option value="POST">POST</a-select-option>
                            <a-select-option value="GET">GET</a-select-option>
                            <a-select-option value="PUT">PUT</a-select-option>
                          </a-select>
                        </a-form-item>
                      </a-col>
                      <a-col :span="20">
                        <a-form-item label="接口请求 URL" name="apiUrl" required>
                          <a-input
                            v-model:value="formData.apiUrl"
                            placeholder="例如：https://api.example.com/v1/materials/list"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="10">
                        <a-form-item label="请求头 Headers (JSON格式)">
                          <a-textarea
                            v-model:value="formData.apiHeaders"
                            :rows="6"
                            class="code-textarea"
                            :placeholder="headersPlaceholder"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="14">
                        <a-form-item label="请求体 Body (JSON格式，POST/PUT)">
                          <a-textarea
                            v-model:value="formData.apiBody"
                            :rows="6"
                            class="code-textarea"
                            :placeholder="bodyPlaceholder"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <a-row :gutter="16">
                      <a-col :span="16">
                        <a-form-item label="结果提取 JsonPath" name="apiDataPath" required>
                          <a-input
                            v-model:value="formData.apiDataPath"
                            placeholder="例如：$.data.records 或 $.*"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="8">
                        <div style="padding-top: 30px">
                          <span style="font-size: 12px; color: #8c8c8c; margin-right: 6px">常用推荐:</span>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.data.records'">$.data.records</a-tag>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.data.list'">$.data.list</a-tag>
                        </div>
                      </a-col>
                    </a-row>
                  </a-card>
                </div>
              </div>

              <!-- 底部导航条 -->
              <div class="tab-footer-nav">
                <a-button @click="activeTab = '1'">
                  <arrow-left-outlined /> 上一步：基础信息
                </a-button>
                <a-space>
                  <a-button :loading="submitting" @click="handleSaveStep(false)">
                    <save-outlined /> 保存当前进度
                  </a-button>
                  <a-button type="primary" @click="goToNextTab('3')">
                    下一步：配置 SQL 转换 <arrow-right-outlined />
                  </a-button>
                </a-space>
              </div>
            </div>
          </a-tab-pane>

          <!-- Tab 3: SQL 处理与转换脚本 (Transform) -->
          <a-tab-pane key="3" tab="🔄 3. SQL 转换处理 (Transform)">
            <div class="tab-pane-content">
              <a-alert
                message="💡 数据流转机制说明"
                description="步骤2抽取的数据将首先自动加载至当前流程的专属临时表 ${stage_table}。您可在此编写原生 SQL 语句进行清洗、关联、计算，并最终写入数仓目标表 ${target_table}。"
                type="info"
                show-icon
                style="margin-bottom: 16px"
              />

              <a-card :bordered="false" class="pane-card">
                <a-form-item name="transformSql" required>
                  <SqlEditor
                    v-model="formData.transformSql"
                    placeholder="-- 清空目标表并覆盖写入示例：
INSERT INTO ${target_table} (id, material_code, material_name, sync_date)
SELECT id, material_code, name, '${today}'
FROM ${stage_table}
ON DUPLICATE KEY UPDATE material_name = VALUES(material_name);"
                  />
                </a-form-item>
              </a-card>

              <!-- 底部导航条 -->
              <div class="tab-footer-nav">
                <a-button @click="activeTab = '2'">
                  <arrow-left-outlined /> 上一步：数据抽取配置
                </a-button>
                <a-space>
                  <a-button :loading="submitting" @click="handleSaveStep(false)">
                    <save-outlined /> 保存当前进度
                  </a-button>
                  <a-button type="primary" @click="goToNextTab('4')">
                    下一步：目标数仓输出 <arrow-right-outlined />
                  </a-button>
                </a-space>
              </div>
            </div>
          </a-tab-pane>

          <!-- Tab 4: 目标数仓输出配置 (Sink) -->
          <a-tab-pane key="4" tab="📤 4. 目标数仓输出 (Sink)">
            <div class="tab-pane-content">
              <a-card :bordered="false" class="pane-card">
                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="目标 MySQL 数仓连接" name="targetDsId" required>
                      <a-select v-model:value="formData.targetDsId" placeholder="选择目标数仓连接" allow-clear>
                        <a-select-option v-for="ds in datasourceList" :key="ds.id" :value="ds.id">
                          {{ ds.dsName }} ({{ ds.host ? `${ds.host}:${ds.port || 3306}/${ds.databaseName || ''}` : ds.jdbcUrl }})
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="目标数据表名" name="targetTable" required>
                      <a-input v-model:value="formData.targetTable" placeholder="例如：dwd_erp_material_info" />
                    </a-form-item>
                  </a-col>
                </a-row>

                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="写入策略">
                      <a-select v-model:value="formData.writeMode">
                        <a-select-option value="APPEND">APPEND (直接追加插入)</a-select-option>
                        <a-select-option value="OVERWRITE">OVERWRITE (清空全量覆盖)</a-select-option>
                        <a-select-option value="UPSERT">UPSERT (依据主键更新合并)</a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <a-col :span="12" v-if="formData.writeMode === 'UPSERT'">
                    <a-form-item label="唯一主键列 (逗号分隔)">
                      <a-input v-model:value="formData.upsertKeys" placeholder="例如：id 或 material_code" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </a-card>

              <!-- 底部导航条 -->
              <div class="tab-footer-nav">
                <a-button @click="activeTab = '3'">
                  <arrow-left-outlined /> 上一步：SQL 处理转换
                </a-button>
                <a-space>
                  <a-button :loading="submitting" @click="handleSaveStep(false)">
                    <save-outlined /> 保存当前进度
                  </a-button>
                  <a-button type="primary" @click="goToNextTab('5')">
                    下一步：调度与告警 <arrow-right-outlined />
                  </a-button>
                </a-space>
              </div>
            </div>
          </a-tab-pane>

          <!-- Tab 5: 调度与告警通知 -->
          <a-tab-pane key="5" tab="⏰ 5. 调度与告警 (Schedule & Alert)">
            <div class="tab-pane-content">
              <a-card :bordered="false" class="pane-card">
                <a-row :gutter="24">
                  <a-col :span="14">
                    <a-form-item label="Cron 定时调度表达式">
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
                    <a-form-item label="是否开启定时调度">
                      <a-switch
                        v-model:checked="scheduleChecked"
                        checked-children="已启用"
                        un-checked-children="停用"
                      />
                    </a-form-item>
                  </a-col>
                </a-row>

                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="异常告警通知通道">
                      <a-select v-model:value="formData.alertConfigId" placeholder="选择企微/钉钉/飞书/邮件通道" allow-clear>
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
                    <a-form-item label="任务超时时间 (秒)">
                      <a-input-number v-model:value="formData.timeoutSeconds" :min="60" style="width: 100%" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </a-card>

              <!-- 底部导航条 -->
              <div class="tab-footer-nav">
                <a-button @click="activeTab = '4'">
                  <arrow-left-outlined /> 上一步：目标数仓输出
                </a-button>
                <a-space>
                  <a-button :loading="submitting" @click="handleSaveStep(false)">
                    <save-outlined /> 保存当前进度
                  </a-button>
                  <a-button type="primary" :loading="submitting" @click="handleCompleteSave">
                    <check-outlined /> 完成并保存全部流程
                  </a-button>
                </a-space>
              </div>
            </div>
          </a-tab-pane>
        </a-tabs>
      </a-form>
    </div>

    <!-- 实时数据抽取预览弹窗 -->
    <a-modal
      v-model:open="previewModalVisible"
      title="🔍 WebAPI 抽取测试与数据预览"
      width="960px"
      :footer="null"
    >
      <div v-if="previewResult">
        <a-alert
          type="success"
          show-icon
          style="margin-bottom: 16px"
          :message="`🎉 数据抽取成功！接口返回 ${previewResult.total || 0} 条记录，以下为前 ${previewRecords.length} 条实时结构化数据：`"
        />

        <a-table
          :data-source="previewRecords"
          :columns="previewColumns"
          :scroll="{ x: 'max-content', y: 340 }"
          size="small"
          :pagination="false"
          bordered
        />

        <a-collapse ghost style="margin-top: 16px">
          <a-collapse-panel key="log" header="📋 查看底层抽取执行日志 (含会话Cookie、请求耗时与数据流)">
            <pre class="log-pre">{{ previewResult.log }}</pre>
          </a-collapse-panel>
        </a-collapse>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, h } from 'vue'
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CheckOutlined,
  SaveOutlined,
  KeyOutlined,
  DatabaseOutlined,
  TableOutlined,
  FileExcelOutlined,
  DownloadOutlined,
  FileAddOutlined,
  PlusOutlined,
  ApiOutlined,
  BulbOutlined
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import SqlEditor from './SqlEditor.vue'
import {
  savePipeline,
  updatePipeline,
  saveStepPipeline,
  testWebapiLogin,
  previewWebapiExtract,
  queryKingdeeFormFields,
  saveKingdeeCredential,
  getKingdeeCredentialDefault,
  type KingdeeFieldMeta,
  type KingdeeCredentialMasked
} from '../api/pipeline'
import { getDatasourceList } from '../api/datasource'
import { getAlertConfigList } from '../api/alert'
import { getFolderTree } from '../api/folder'
import type { EtlPipeline, EtlDatasource, EtlAlertConfig } from '../types'
import {
  FIELD_TYPE_OPTIONS,
  createRow,
  splitFieldKeys,
  joinFieldKeys,
  mergeRows,
  parseFieldExcel,
  parseFieldPaste,
  exportFieldRows,
  downloadFieldTemplate,
  fieldRowsToMetaJson,
  fieldRowsFromMeta,
  inferFieldType,
  type FieldRow,
  type ParseResult
} from '../utils/fieldKeysExcel'

const emit = defineEmits(['back', 'success'])

const activeTab = ref('1')
const isEdit = ref(false)
const submitting = ref(false)
const currentId = ref<string>('')
const formRef = ref()

const datasourceList = ref<EtlDatasource[]>([])
const alertList = ref<EtlAlertConfig[]>([])
const folderTree = ref<any[]>([])

const headersPlaceholder = `{\n  "Content-Type": "application/json"\n}`
const bodyPlaceholder = `{\n  "data": {\n    "FormId": "PUR_PurchaseOrder",\n    "FieldKeys": "FID,FBillNo,FDate,FSupplierId.FNumber,FDocumentStatus",\n    "FilterString": "FDate >= '\${yesterday}'",\n    "OrderString": "FID DESC",\n    "Limit": 2000,\n    "StartRow": 0\n  }\n}`

const initialFormData = (): Partial<EtlPipeline> => ({
  sourceType: 'MYSQL',
  apiAuthType: 'KINGDEE_K3',
  apiMethod: 'POST',
  writeMode: 'APPEND',
  timeoutSeconds: 1800,
  maxRetryCount: 0,
  scheduleStatus: 0,
  apiDataPath: '$.*',
  transformSql: ''
})

const formData = ref<Partial<EtlPipeline>>(initialFormData())

// 金蝶云星空登录配置响应式对象
const kingdeeConfig = ref({
  loginUrl: 'http://10.10.12.3/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.ValidateUser.common.kdsvc',
  acctID: '',
  userName: 'administrator',
  password: '',
  lcid: 2052
})

// 金蝶单据查询快速向导参数
const defaultKingdeeQuery = () => ({
  formId: 'PUR_PurchaseOrder',
  fieldKeys: 'FID,FBillNo,FDate,FSupplierId.FNumber,FDocumentStatus',
  filterString: "FDate >= '${yesterday}'",
  orderString: 'FID DESC',
  limit: 2000
})

const kingdeeQuery = ref(defaultKingdeeQuery())

// ===== 高级配置：分页取数（对应 t_etl_pipeline.api_pagination） =====
const defaultPagination = () => ({
  enabled: false,
  offsetPath: 'data.StartRow',
  limitPath: 'data.Limit',
  start: 0,
  step: 0,
  pageSize: 2000,
  maxPages: 200,
  endWhenLessThan: 0,
  intervalMs: 0
})

const pagination = ref(defaultPagination())
const advancedActiveKeys = ref<string[]>([])

// api_pagination 在库里是 MySQL JSON 列，空字符串会被判为非法 JSON，关闭时写 {} 而不是 ''
const syncPagination = () => {
  formData.value.apiPagination = JSON.stringify(
    pagination.value.enabled ? pagination.value : {}
  )
}

const loadPagination = () => {
  pagination.value = defaultPagination()
  if (formData.value.apiPagination) {
    try {
      const parsed = JSON.parse(formData.value.apiPagination)
      pagination.value = { ...pagination.value, ...parsed }
      if (pagination.value.enabled) {
        advancedActiveKeys.value = ['pagination']
      }
    } catch (e) {
      pagination.value = defaultPagination()
    }
  }
}

// ===== 抽取字段清单（表格编辑 + Excel 导入导出） =====
const fieldRows = ref<FieldRow[]>([])
const textMode = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const pasteModalVisible = ref(false)
const pasteText = ref('')
const pastePlaceholder = 'FID\t单据内码\nFBillNo\t单据编号\nFSupplierId.FNumber\t供应商编码'

// 从金蝶 QueryBusinessInfo 拉取的字段元数据
const kingdeeFieldModalVisible = ref(false)
const kingdeeFieldsLoading = ref(false)
const kingdeeFieldError = ref('')
const kingdeeFields = ref<Array<KingdeeFieldMeta & { checked: boolean }>>([])
const kingdeeCheckedCount = computed(() => kingdeeFields.value.filter((f) => f.checked).length)
const kingdeeColumns: any[] = [
  { title: '', dataIndex: 'checked', width: 46, align: 'center' },
  { title: '字段名', dataIndex: 'key' },
  { title: '中文名', dataIndex: 'label', width: 170 },
  { title: '金蝶类型', dataIndex: 'rawType', width: 130 },
  { title: '临时表类型', dataIndex: 'type', width: 170 }
]

const fieldTypeSelectOptions = FIELD_TYPE_OPTIONS.map((t) => ({ label: t, value: t }))
const fieldColumns: any[] = [
  { title: '序号', dataIndex: 'idx', width: 56, align: 'center' },
  { title: '字段名 (FieldKey)', dataIndex: 'fieldKey' },
  { title: '中文名', dataIndex: 'label', width: 150 },
  { title: '类型', dataIndex: 'type', width: 132 },
  { title: '启用', dataIndex: 'enabled', width: 62, align: 'center' },
  { title: '操作', dataIndex: 'action', width: 150, align: 'center' }
]

const enabledFieldCount = computed(
  () => fieldRows.value.filter((r) => r.enabled && r.fieldKey.trim()).length
)

// 通用登录前置鉴权响应式对象
const customAuthConfig = ref({
  loginUrl: '',
  loginMethod: 'POST',
  loginHeaders: '{\n  "Content-Type": "application/json"\n}',
  loginBody: '{\n  "username": "admin",\n  "password": ""\n}',
  extractPath: '$.token'
})

// 已保存凭证的占位符：界面上回填 8 个星号，实际提交时视为「留空」由后端兜底
const PASSWORD_SENTINEL = '********'

// 金蝶客户端
const savingCredential = ref(false)
const loadingCredential = ref(false)
const hasSavedPassword = ref(false)

// 测试登录状态
const testingLogin = ref(false)
const loginTestResult = ref<any>(null)

// 预览抽取状态
const previewingExtract = ref(false)
const previewModalVisible = ref(false)
const previewResult = ref<any>(null)

const previewRecords = computed(() => {
  return previewResult.value?.records || []
})

const previewColumns = computed(() => {
  if (!previewRecords.value || previewRecords.value.length === 0) return []
  const first = previewRecords.value[0]
  return Object.keys(first).map((key) => ({
    title: key,
    dataIndex: key,
    key,
    ellipsis: true,
    width: 160
  }))
})

const scheduleChecked = computed({
  get: () => formData.value.scheduleStatus === 1,
  set: (val: boolean) => {
    formData.value.scheduleStatus = val ? 1 : 0
  }
})

const isDraft = computed(() => {
  return !formData.value.targetDsId || !formData.value.targetTable || !formData.value.transformSql
})

const formRules = {
  pipelineName: [{ required: true, message: '请输入流程名称', trigger: 'blur' }],
  sourceType: [{ required: true, message: '请选择数据源类型' }]
}

// 切换鉴权模式
const onAuthTypeChange = () => {
  if (formData.value.apiAuthType === 'KINGDEE_K3') {
    formData.value.apiMethod = 'POST'
    formData.value.apiDataPath = '$.*'
    if (!formData.value.apiUrl || !formData.value.apiUrl.includes('k3cloud')) {
      const host = extractHost(kingdeeConfig.value.loginUrl) || 'http://10.10.12.3'
      formData.value.apiUrl = `${host}/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc`
    }
    syncKingdeeQueryToBody()
    syncKingdeeLoginBody()
  }
}

const extractHost = (url: string) => {
  try {
    const u = new URL(url)
    return `${u.protocol}//${u.host}`
  } catch (e) {
    return ''
  }
}

// 更改金蝶登录URL时，联动建议单据URL前缀
const onKingdeeLoginUrlChange = () => {
  const host = extractHost(kingdeeConfig.value.loginUrl)
  if (host && formData.value.apiUrl && formData.value.apiUrl.includes('/k3cloud/')) {
    formData.value.apiUrl = `${host}/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc`
  }
  syncKingdeeLoginBody()
}

// 实际提交的密码：占位星号视为空，交由后端使用已保存凭证
const effectivePassword = () => {
  const password = kingdeeConfig.value.password
  return password === PASSWORD_SENTINEL ? '' : password
}

// 同步金蝶登录表单至 apiAuthConfig
const syncKingdeeLoginBody = () => {
  // 占位星号不能被当成真密码发出去，统一转成空串交给后端用已保存凭证兜底
  formData.value.apiAuthConfig = JSON.stringify({
    ...kingdeeConfig.value,
    password: effectivePassword()
  })
}

// 同步金蝶单据参数至 Body
const syncKingdeeQueryToBody = () => {
  formData.value.apiBody = JSON.stringify(
    {
      data: {
        FormId: kingdeeQuery.value.formId || '',
        FieldKeys: kingdeeQuery.value.fieldKeys || '',
        FilterString: kingdeeQuery.value.filterString || '',
        OrderString: kingdeeQuery.value.orderString || '',
        TopRowCount: 0,
        Limit: kingdeeQuery.value.limit || 2000,
        StartRow: 0
      }
    },
    null,
    2
  )
}

// 由 FieldKeys 串重建表格行（保留已存在行的中文名/类型/启用/备注）
const rebuildFieldRows = () => {
  fieldRows.value = mergeRows(splitFieldKeys(kingdeeQuery.value.fieldKeys), fieldRows.value)
}

// 表格变更 -> 回写 FieldKeys -> 同步金蝶请求体
const applyFieldRows = () => {
  kingdeeQuery.value.fieldKeys = joinFieldKeys(fieldRows.value)
  syncKingdeeQueryToBody()
}

const onFieldRowChanged = () => {
  applyFieldRows()
}

const onFieldKeysTextChange = () => {
  syncKingdeeQueryToBody()
  rebuildFieldRows()
}

const toggleTextMode = () => {
  if (textMode.value) rebuildFieldRows()
  textMode.value = !textMode.value
}

const addFieldRow = () => {
  fieldRows.value.push(createRow())
}

const removeFieldRow = (index: number) => {
  fieldRows.value.splice(index, 1)
  applyFieldRows()
}

const moveFieldRow = (index: number, delta: number) => {
  const target = index + delta
  if (target < 0 || target >= fieldRows.value.length) return
  const rows = fieldRows.value
  const tmp = rows[index]
  rows[index] = rows[target]
  rows[target] = tmp
  applyFieldRows()
}

const triggerExcelImport = () => fileInputRef.value?.click()

const applyParsedRows = (result: ParseResult, sourceLabel: string) => {
  if (result.errors.length) {
    Modal.error({
      title: '导入失败，请修正后重试',
      width: 620,
      content: h(
        'div',
        result.errors.slice(0, 12).map((e: string) => h('div', { style: 'line-height:1.7' }, e))
      )
    })
    return
  }
  if (!result.rows.length) {
    message.warning('没有解析到有效字段')
    return
  }
  const total = result.rows.length
  Modal.confirm({
    title: `导入 ${total} 个字段`,
    content: `当前清单 ${fieldRows.value.length} 个字段将被替换（${sourceLabel}），确认继续？`,
    okText: '覆盖导入',
    cancelText: '取消',
    onOk: () => {
      fieldRows.value = result.rows
      applyFieldRows()
      if (result.warnings.length) {
        message.warning(
          `已导入 ${total} 个字段；${result.warnings.slice(0, 2).join('；')}${
            result.warnings.length > 2 ? ` 等 ${result.warnings.length} 条` : ''
          }`
        )
      } else {
        message.success(`已导入 ${total} 个字段`)
      }
    }
  })
}

const onExcelFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files && input.files[0]
  input.value = ''
  if (!file) return
  const result = await parseFieldExcel(file)
  applyParsedRows(result, `来源：${file.name}`)
}

const handleExportExcel = () => {
  if (!fieldRows.value.length) {
    message.warning('当前没有可导出的字段')
    return
  }
  exportFieldRows(fieldRows.value, kingdeeQuery.value.formId)
  message.success('已导出 Excel')
}

const handleDownloadTemplate = () => {
  downloadFieldTemplate()
  message.success('模板已下载')
}

// 从金蝶拉取表单字段（自动带出中文名与类型）
const handlePullKingdeeFields = async () => {
  if (!kingdeeQuery.value.formId) {
    message.warning('请先填写业务对象表单标识 FormId')
    return
  }
  if (!kingdeeConfig.value.loginUrl) {
    message.warning('请先在上方填写金蝶登录接口地址')
    return
  }
  kingdeeFields.value = []
  kingdeeFieldError.value = ''
  kingdeeFieldModalVisible.value = true
  kingdeeFieldsLoading.value = true
  try {
    const list = await queryKingdeeFormFields({
      loginUrl: kingdeeConfig.value.loginUrl,
      acctID: kingdeeConfig.value.acctID,
      userName: kingdeeConfig.value.userName,
      password: effectivePassword() || undefined,
      lcid: kingdeeConfig.value.lcid,
      formId: kingdeeQuery.value.formId
    })
    const existing = new Set(fieldRows.value.map((r) => r.fieldKey.trim().toUpperCase()))
    kingdeeFields.value = (list || []).map((f) => ({
      ...f,
      type: f.type || inferFieldType(f.key),
      checked: !existing.has((f.key || '').trim().toUpperCase())
    }))
    if (!kingdeeFields.value.length) {
      kingdeeFieldError.value = '金蝶未返回任何字段，请确认 FormId 是否正确'
    }
  } catch (e: any) {
    kingdeeFieldError.value = e?.message || '拉取字段失败，请检查登录配置'
  } finally {
    kingdeeFieldsLoading.value = false
  }
}

const toggleAllKingdeeFields = (checked: boolean) => {
  kingdeeFields.value.forEach((f) => {
    f.checked = checked
  })
}

// append: 只补清单里没有的字段；replace: 用所选字段整体替换清单
const applyKingdeeFields = (mode: 'append' | 'replace') => {
  const picked = kingdeeFields.value.filter((f) => f.checked && f.key)
  if (!picked.length) {
    message.warning('请至少勾选一个字段')
    return
  }
  if (mode === 'replace') {
    fieldRows.value = picked.map((f) =>
      createRow({ fieldKey: f.key, label: f.label || '', type: f.type || 'TEXT', enabled: true })
    )
  } else {
    const existing = new Set(fieldRows.value.map((r) => r.fieldKey.trim().toUpperCase()))
    picked.forEach((f) => {
      const upper = f.key.trim().toUpperCase()
      if (existing.has(upper)) {
        const row = fieldRows.value.find((r) => r.fieldKey.trim().toUpperCase() === upper)
        if (row) {
          if (!row.label) row.label = f.label || ''
          if (!row.type || row.type === 'TEXT') row.type = f.type || row.type
        }
        return
      }
      fieldRows.value.push(
        createRow({ fieldKey: f.key, label: f.label || '', type: f.type || 'TEXT', enabled: true })
      )
      existing.add(upper)
    })
  }
  applyFieldRows()
  kingdeeFieldModalVisible.value = false
  message.success(`已${mode === 'replace' ? '覆盖' : '追加'} ${picked.length} 个字段`)
}

// 只填充还没有明确类型（空或 TEXT）的行，避免覆盖手工设置
const handleInferTypes = () => {
  if (!fieldRows.value.length) {
    message.warning('请先添加字段')
    return
  }
  let changed = 0
  fieldRows.value.forEach((row) => {
    if (!row.fieldKey.trim()) return
    if (!row.type || row.type === 'TEXT') {
      row.type = inferFieldType(row.fieldKey)
      changed += 1
    }
  })
  if (changed) {
    applyFieldRows()
    message.success(`已按字段名推断 ${changed} 个字段的类型，保存后生效`)
  } else {
    message.info('所有字段都已有明确类型，未做改动')
  }
}

const confirmPasteImport = () => {
  const text = pasteText.value
  pasteModalVisible.value = false
  const result = parseFieldPaste(text)
  applyParsedRows(result, '来源：粘贴文本')
  pasteText.value = ''
}

// 从 Body 反向解析金蝶单据参数
const syncBodyToKingdeeQuery = () => {
  try {
    if (!formData.value.apiBody) return
    const parsed = JSON.parse(formData.value.apiBody)
    const d = parsed.data || parsed
    if (d.FormId) kingdeeQuery.value.formId = d.FormId
    if (d.FieldKeys) kingdeeQuery.value.fieldKeys = d.FieldKeys
    if (d.FilterString !== undefined) kingdeeQuery.value.filterString = d.FilterString
    if (d.OrderString !== undefined) kingdeeQuery.value.orderString = d.OrderString
    if (d.Limit !== undefined) kingdeeQuery.value.limit = d.Limit
    rebuildFieldRows()
  } catch (e) {}
}

// 载入金蝶登录认证标准模板
const loadKingdeeAuthTemplate = () => {
  kingdeeConfig.value = {
    loginUrl: 'http://10.10.12.3/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.ValidateUser.common.kdsvc',
    acctID: kingdeeConfig.value.acctID || '603c4f940a4306',
    userName: kingdeeConfig.value.userName || 'administrator',
    password: kingdeeConfig.value.password || '',
    lcid: 2052
  }
  syncKingdeeLoginBody()
  message.success('已载入金蝶云星空标准登录接口格式')
}

// 把已保存的凭证套用到表单（密码永不回显）
const applyCredentialToForm = (credential: KingdeeCredentialMasked) => {
  kingdeeConfig.value = {
    loginUrl: credential.loginUrl || kingdeeConfig.value.loginUrl,
    acctID: credential.acctID || '',
    userName: credential.userName || '',
    // 已保存密码用 8 个星号占位，让界面看起来是填好的；提交时会被转成空串
    password: credential.hasPassword ? PASSWORD_SENTINEL : '',
    lcid: credential.lcid || 2052
  }
  hasSavedPassword.value = !!credential.hasPassword
  // 单据查询接口地址随凭证一起载入；没存过则按登录地址推导一个默认值
  if (credential.queryUrl) {
    formData.value.apiUrl = credential.queryUrl
  } else if (!formData.value.apiUrl && credential.loginUrl) {
    formData.value.apiUrl = buildQueryUrl(credential.loginUrl)
  }
  syncKingdeeLoginBody()
}

// 由登录地址推导单据查询接口（沿用登录地址的大小写风格）
const buildQueryUrl = (loginUrl: string) => {
  const host = extractHost(loginUrl)
  if (!host) return ''
  const prefix = loginUrl.includes('/K3Cloud/') ? '/K3Cloud/' : '/k3cloud/'
  return `${host}${prefix}Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc`
}

// 保存当前表单中的凭证（密码加密落库；留空表示沿用旧密码）
const handleSaveCredential = async () => {
  if (!kingdeeConfig.value.loginUrl?.trim()) {
    message.warning('请先填写金蝶登录接口地址')
    return
  }
  if (!kingdeeConfig.value.acctID?.trim()) {
    message.warning('请先填写数据中心 / 账套代码 acctID')
    return
  }
  if (!kingdeeConfig.value.userName?.trim()) {
    message.warning('请先填写登录用户名')
    return
  }
  if (!kingdeeConfig.value.password && !hasSavedPassword.value) {
    message.warning('首次保存必须填写登录密码')
    return
  }
  savingCredential.value = true
  try {
    const saved = await saveKingdeeCredential({
      loginUrl: kingdeeConfig.value.loginUrl.trim(),
      // 单据查询接口地址一并保存，下次载入凭证时自动回填
      queryUrl: formData.value.apiUrl?.trim() || undefined,
      acctID: kingdeeConfig.value.acctID?.trim(),
      userName: kingdeeConfig.value.userName?.trim(),
      password: effectivePassword() || undefined,
      lcid: kingdeeConfig.value.lcid
    })
    hasSavedPassword.value = !!saved?.hasPassword
    // 保存后统一回填占位星号，界面不再保留明文
    kingdeeConfig.value.password = hasSavedPassword.value ? PASSWORD_SENTINEL : ''
    syncKingdeeLoginBody()
    message.success('凭证已加密保存，之后无需再手填密码')
  } finally {
    savingCredential.value = false
  }
}

// 载入默认凭证；没有保存过则回落为标准登录地址模板
const handleLoadCredential = async () => {
  loadingCredential.value = true
  try {
    const credential = await getKingdeeCredentialDefault()
    if (!credential) {
      loadKingdeeAuthTemplate()
      message.info('尚未保存凭证，已载入金蝶标准登录地址模板')
      return
    }
    applyCredentialToForm(credential)
    message.success('已载入保存的凭证（密码不回显，留空即使用已保存密码）')
  } finally {
    loadingCredential.value = false
  }
}

// 打开页面时静默套用已保存凭证，省去每次手填
const loadCredentialSilently = async () => {
  try {
    const credential = await getKingdeeCredentialDefault()
    if (credential) {
      applyCredentialToForm(credential)
    }
  } catch (e) {
    // 静默失败：不影响正常编辑
  }
}

// 载入金蝶常用单据模板
const loadKingdeeBillTemplate = (billType: string) => {
  if (billType === 'PUR_PurchaseOrder') {
    kingdeeQuery.value = {
      formId: 'PUR_PurchaseOrder',
      fieldKeys: 'FID,FBillNo,FDate,FSupplierId.FNumber,FDocumentStatus',
      filterString: "FDate >= '${yesterday}'",
      orderString: 'FID DESC',
      limit: 2000
    }
  } else if (billType === 'SAL_SaleOrder') {
    kingdeeQuery.value = {
      formId: 'SAL_SaleOrder',
      fieldKeys: 'FID,FBillNo,FDate,FCustId.FNumber,FBillAllAmount,FDocumentStatus',
      filterString: "FDate >= '${yesterday}'",
      orderString: 'FID DESC',
      limit: 2000
    }
  } else if (billType === 'BD_MATERIAL') {
    kingdeeQuery.value = {
      formId: 'BD_MATERIAL',
      fieldKeys: 'FMaterialId,FNumber,FName,FSpecification,FMaterialGroup.FNumber',
      filterString: "FApproveDate >= '${yesterday}'",
      orderString: 'FMaterialId ASC',
      limit: 2000
    }
  }
  syncKingdeeQueryToBody()
  rebuildFieldRows()
  message.success(`已载入 ${billType} 常用抽取字段与过滤条件`)
}

// 测试前置登录
const handleTestLogin = async () => {
  const authType = formData.value.apiAuthType || 'KINGDEE_K3'
  let authConfigStr = ''
  if (authType === 'KINGDEE_K3') {
    if (!kingdeeConfig.value.loginUrl) {
      message.warning('请先填写金蝶登录验证接口 URL')
      return
    }
    syncKingdeeLoginBody()
    authConfigStr = formData.value.apiAuthConfig || ''
  } else if (authType === 'CUSTOM_LOGIN') {
    if (!customAuthConfig.value.loginUrl) {
      message.warning('请先填写通用登录验证接口 URL')
      return
    }
    authConfigStr = JSON.stringify(customAuthConfig.value)
  }

  testingLogin.value = true
  loginTestResult.value = null
  try {
    const res = await testWebapiLogin({
      authType,
      authConfig: authConfigStr
    })
    loginTestResult.value = res
    if (res.success) {
      message.success('登录认证成功！已成功捕获会话 Cookie')
    } else {
      message.error(res.message || '登录验证失败')
    }
  } catch (e: any) {
    loginTestResult.value = {
      success: false,
      message: e.message || '网络请求异常'
    }
    message.error('登录请求异常：' + (e.message || ''))
  } finally {
    testingLogin.value = false
  }
}

// 测试数据抽取并预览数据
const handlePreviewExtract = async () => {
  if (!formData.value.apiUrl) {
    message.warning('请先填写数据抽取接口 URL')
    return
  }

  previewingExtract.value = true
  try {
    // 确保 authConfig 同步
    if (formData.value.apiAuthType === 'KINGDEE_K3') {
      syncKingdeeLoginBody()
      syncKingdeeQueryToBody()
    } else if (formData.value.apiAuthType === 'CUSTOM_LOGIN') {
      formData.value.apiAuthConfig = JSON.stringify(customAuthConfig.value)
    }

    const res = await previewWebapiExtract(formData.value)
    previewResult.value = res
    previewModalVisible.value = true
    message.success(`数据抽取成功！共拉取到 ${res.total || 0} 条记录`)
  } catch (e: any) {
    message.error('抽取测试失败：' + (e.message || '未知异常'))
  } finally {
    previewingExtract.value = false
  }
}

// 格式化 JSON
const formatJsonBody = () => {
  try {
    if (formData.value.apiHeaders) {
      formData.value.apiHeaders = JSON.stringify(JSON.parse(formData.value.apiHeaders), null, 2)
    }
    if (formData.value.apiBody) {
      formData.value.apiBody = JSON.stringify(JSON.parse(formData.value.apiBody), null, 2)
      syncBodyToKingdeeQuery()
    }
    message.success('JSON 格式化美化成功')
  } catch (e: any) {
    message.warning('JSON 格式解析有误，请检查语法')
  }
}

// 标准 REST 模板加载
const applyRestTemplate = () => {
  formData.value.apiMethod = 'POST'
  formData.value.apiUrl = 'https://api.example.com/v1/materials/list'
  formData.value.apiHeaders = JSON.stringify(
    {
      'Content-Type': 'application/json',
      Authorization: 'Bearer {token}'
    },
    null,
    2
  )
  formData.value.apiBody = JSON.stringify(
    {
      page: 1,
      pageSize: 500,
      updateStartTime: '${yesterday}'
    },
    null,
    2
  )
  formData.value.apiDataPath = '$.data.records'
  message.success('已加载标准 RESTful API 分页查询模板')
}

const selectPresetCron = ({ key }: { key: string }) => {
  formData.value.cronExpr = key
}

// 对外暴露 open 方法以供 App.vue 调用
const open = async (record?: EtlPipeline, defaultFolderId?: string) => {
  activeTab.value = '1'
  loginTestResult.value = null
  previewResult.value = null

  // 加载数据源、告警通道、目录树
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
      folderId: record.folderId ? String(record.folderId) : undefined,
      apiAuthType: record.apiAuthType || 'KINGDEE_K3'
    }

    // 回填前置鉴权与金蝶单据参数
    if (record.apiAuthConfig) {
      try {
        const parsedAuth = JSON.parse(record.apiAuthConfig)
        if (record.apiAuthType === 'KINGDEE_K3') {
          kingdeeConfig.value = { ...kingdeeConfig.value, ...parsedAuth }
        } else if (record.apiAuthType === 'CUSTOM_LOGIN') {
          customAuthConfig.value = { ...customAuthConfig.value, ...parsedAuth }
        }
      } catch (e) {}
    } else {
      // 老流程没存过鉴权配置时，套用已保存凭证，避免重复手填
      await loadCredentialSilently()
    }

    if (record.apiBody) {
      syncBodyToKingdeeQuery()
    } else {
      fieldRows.value = []
    }
    // 中文名/类型/备注来自 field_meta（启用状态仍以 FieldKeys 为准）
    if (record.fieldMeta) {
      fieldRows.value = fieldRowsFromMeta(record.fieldMeta, kingdeeQuery.value.fieldKeys)
    }
    loadPagination()
  } else {
    isEdit.value = false
    currentId.value = ''
    formData.value = {
      ...initialFormData(),
      folderId: defaultFolderId || undefined
    }
    kingdeeQuery.value = defaultKingdeeQuery()
    pagination.value = defaultPagination()
    advancedActiveKeys.value = []
    syncKingdeeLoginBody()
    syncKingdeeQueryToBody()
    rebuildFieldRows()
    // 新建流程时自动套用已保存凭证，省去每次手填
    await loadCredentialSilently()
  }
}

const handleBack = () => {
  emit('back')
}

const handleReset = () => {
  if (!isEdit.value) {
    formData.value = initialFormData()
    kingdeeQuery.value = defaultKingdeeQuery()
    pagination.value = defaultPagination()
    advancedActiveKeys.value = []
    syncKingdeeLoginBody()
    syncKingdeeQueryToBody()
    rebuildFieldRows()
  }
}

const prepareDataBeforeSave = () => {
  if (formData.value.sourceType === 'WEB_API') {
    if (formData.value.apiAuthType === 'KINGDEE_K3') {
      syncKingdeeLoginBody()
      syncKingdeeQueryToBody()
    } else if (formData.value.apiAuthType === 'CUSTOM_LOGIN') {
      formData.value.apiAuthConfig = JSON.stringify(customAuthConfig.value)
    } else {
      formData.value.apiAuthConfig = ''
    }
    // 字段清单元数据随流程保存（中文名/类型/启用/备注），供后端按类型建临时表
    formData.value.fieldMeta = fieldRowsToMetaJson(fieldRows.value)
    // 分页配置只在启用时落库，避免残留旧配置
    syncPagination()
  } else {
    // 非 WebAPI 源不使用字段清单，清空避免残留类型影响临时表
    formData.value.fieldMeta = ''
  }
}

// 暂存当前步骤进度 (草稿保存，随时可存)
const handleSaveStep = async (silent = false): Promise<boolean> => {
  if (!formData.value.pipelineName || !formData.value.pipelineName.trim()) {
    if (!silent) {
      message.warning('请先在第 1 步输入【流程名称】，以便暂存进度')
      activeTab.value = '1'
    }
    return false
  }

  prepareDataBeforeSave()
  submitting.value = true
  try {
    const payload: Partial<EtlPipeline> = {
      ...formData.value,
      id: currentId.value ? currentId.value : undefined
    }
    const saved = await saveStepPipeline(payload)
    if (saved && saved.id) {
      currentId.value = String(saved.id)
      formData.value.id = String(saved.id)
      formData.value.pipelineCode = saved.pipelineCode
      isEdit.value = true
    }
    if (!silent) {
      message.success('当前配置进度已暂存保存！可随时返回列表或下次继续配置')
    }
    return true
  } catch (e: any) {
    if (!silent) {
      message.error('保存失败：' + (e.message || '系统异常'))
    }
    return false
  } finally {
    submitting.value = false
  }
}

// 点击下一步：自动静默暂存当前步并切换 Tab
const goToNextTab = async (targetKey: string) => {
  if (formData.value.pipelineName && formData.value.pipelineName.trim()) {
    await handleSaveStep(true)
  }
  activeTab.value = targetKey
}

// 完成并保存全部流程
const handleCompleteSave = async () => {
  if (!formData.value.pipelineName || !formData.value.pipelineName.trim()) {
    message.warning('请先在第 1 步输入【流程名称】')
    activeTab.value = '1'
    return
  }

  // 检查是否具备可运行的完整性（目标数仓、目标表、转换SQL）
  const isIncomplete = !formData.value.targetDsId || !formData.value.targetTable || !formData.value.transformSql
  if (isIncomplete) {
    const saved = await handleSaveStep(true)
    if (saved) {
      message.warning('当前流程已暂存为【草稿】！因尚未配置数仓目标库或转换SQL，暂无法启用调度与运行')
      emit('success')
    }
    return
  }

  prepareDataBeforeSave()
  submitting.value = true
  try {
    const payload: Partial<EtlPipeline> = {
      ...formData.value,
      id: currentId.value ? currentId.value : undefined
    }
    await saveStepPipeline(payload)
    message.success('ETL 流程全部配置已成功保存！')
    emit('success')
  } catch (e: any) {
    message.error('保存失败：' + (e.message || '系统异常'))
  } finally {
    submitting.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.pipeline-editor-wrapper {
  background: #ffffff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 100px);
}

.editor-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  border-radius: 8px 8px 0 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.title-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.editor-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f1f1f;
}

.title-tag {
  font-size: 13px;
  padding: 2px 8px;
}

.editor-body {
  padding: 16px 20px 24px;
  flex: 1;
}

.tab-pane-content {
  padding: 12px 4px;
}

.pane-card {
  background: #ffffff;
}

.code-textarea {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  background: #fcfcfc;
  line-height: 1.5;
}

.field-hint {
  font-size: 12px;
  color: #8c8c8c;
}

.quick-chip {
  cursor: pointer;
  user-select: none;
}

.quick-chip:hover {
  color: #1677ff;
  border-color: #1677ff;
}

.tab-footer-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
}

/* 顶部两步抽取流程图可视化 */
.api-flow-pipeline {
  display: flex;
  align-items: center;
  background: #f6f8fb;
  border: 1px solid #e1e7f0;
  border-radius: 8px;
  padding: 14px 18px;
  margin-bottom: 16px;
  gap: 12px;
}

.flow-step-node {
  display: flex;
  align-items: center;
  background: #ffffff;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  padding: 8px 12px;
  gap: 10px;
  flex: 1;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.flow-step-node.active {
  border-color: #1677ff;
  background: #f0f7ff;
}

.flow-step-node .node-icon {
  font-size: 18px;
  color: #1677ff;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  background: #e6f4ff;
  border-radius: 50%;
}

.flow-step-node .node-text {
  flex: 1;
}

.flow-step-node .node-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f1f1f;
  margin-bottom: 2px;
}

.flow-step-node .node-desc {
  font-size: 11px;
  color: #8c8c8c;
}

.flow-step-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 130px;
}

.flow-step-arrow .arrow-line {
  width: 100%;
  height: 2px;
  background: #1677ff;
  position: relative;
}

.flow-step-arrow .arrow-line::after {
  content: '';
  position: absolute;
  right: 0;
  top: -4px;
  border-left: 6px solid #1677ff;
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
}

.flow-step-arrow .arrow-badge {
  font-size: 11px;
  color: #1677ff;
  background: #ffffff;
  border: 1px solid #b5d5ff;
  border-radius: 10px;
  padding: 1px 8px;
  margin-top: 4px;
  white-space: nowrap;
}

.module-card {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
}

.module-card :deep(.ant-card-head) {
  background: #fafafa;
  min-height: 42px;
  font-size: 13px;
  font-weight: 600;
}

.log-pre {
  background: #2b2b2b;
  color: #a9b7c6;
  padding: 12px;
  border-radius: 4px;
  font-family: Consolas, monospace;
  font-size: 12px;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
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

.fk-input-mono :deep(input) {
  font-family: Consolas, Monaco, monospace;
}

.kf-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 0;
  font-size: 13px;
  color: #8c8c8c;
}

.kf-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.kf-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
}

.fk-hint-inline {
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.5;
  max-width: 420px;
}

.fk-mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.kf-raw {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
