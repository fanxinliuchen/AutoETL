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
          <a-tab-pane key="2" tab="📥 2. 数据抽取配置">
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
                    :title="
                      bodyMode === 'REPORT'
                        ? '📥 步骤二：金蝶报表取数配置 (parameters + QuicklyCondition)'
                        : '📥 步骤二：金蝶单据数据抽取配置 (DynamicFormService.ExecuteBillQuery)'
                    "
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
                      <a-col :span="24">
                        <a-form-item label="取数方式">
                          <a-radio-group
                            v-model:value="bodyModeSel"
                            button-style="solid"
                            @change="onBodyModeSelChange"
                          >
                            <a-radio-button value="AUTO">自动识别（推荐）</a-radio-button>
                            <a-radio-button value="STANDARD">
                              标准查询（data.FormId + FilterString）
                            </a-radio-button>
                            <a-radio-button value="REPORT">
                              报表方案查询（parameters + QuicklyCondition）
                            </a-radio-button>
                          </a-radio-group>
                          <div class="detect-line">
                            <a-spin v-if="detectLoading" size="small" />
                            <a-tag v-if="bodyModeSel !== 'AUTO'" color="default" class="detect-tag">
                              已手动指定，不再自动切换
                            </a-tag>
                            <a-tag
                              v-else-if="detectResult && detectResult.formId === kingdeeQuery.formId"
                              :color="detectResult.type === 'STANDARD' ? 'green' : 'orange'"
                              class="detect-tag"
                            >
                              {{
                                detectResult.type === 'STANDARD'
                                  ? `已识别：单据（${detectResult.fieldCount} 个字段）`
                                  : '已识别：报表（无单据字段定义）'
                              }}
                            </a-tag>
                            <span v-if="detectError" class="detect-err">识别失败：{{ detectError }}</span>
                            <span
                              v-else-if="detectResult && detectResult.formId === kingdeeQuery.formId"
                              class="detect-reason"
                            >
                              {{ detectResult.reason }}
                            </span>
                          </div>
                          <div class="fk-hint">
                            自动识别会用 QueryBusinessInfo 探一次表单：能取到字段定义的是单据，走
                            ExecuteBillQuery；取不到的是报表，走带 FSCHEMEID 与 QuicklyCondition
                            的报表接口。手动选择后不再被自动结果覆盖；切换会重建请求体模板，仍可在下方微调。
                          </div>
                        </a-form-item>
                      </a-col>
                    </a-row>

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
                          <div v-if="reportUrlMalformed" class="body-warn">
                            金蝶服务地址的装配名分隔符是<b>英文逗号</b>，不是点号：
                            <code>{{ normalizedApiUrl }}</code>
                            <a style="margin-left: 8px" @click="fixReportUrl">一键修正</a>
                          </div>
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <!-- 金蝶单据参数快捷向导表单 -->
                    <a-row :gutter="16">
                      <a-col :span="8">
                        <a-form-item
                          :label="bodyMode === 'REPORT' ? '业务对象 (FORMID)' : '业务对象表单标识 (FormId)'"
                          required
                        >
                          <a-input
                            v-model:value="kingdeeQuery.formId"
                            placeholder="如 PUR_PurchaseOrder / STK_StockSummaryRpt"
                            @change="onFormIdChanged"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="8" v-if="bodyMode === 'REPORT'">
                        <a-form-item label="过滤方案 ID (FSCHEMEID)">
                          <a-input
                            v-model:value="schemeId"
                            placeholder="如 6ac9a38a6e3769"
                            @change="syncKingdeeQueryToBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="bodyMode === 'REPORT' ? 8 : 16">
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
                              <a-tooltip
                                v-if="bodyMode === 'REPORT'"
                                title="报表没有列定义接口，这里用 GetReportData 试取 1 行，按返回的 key 反推字段"
                              >
                                <a-button
                                  size="small"
                                  :loading="reportFieldsLoading"
                                  @click="handleDetectReportFields"
                                >
                                  <table-outlined /> 从报表试取字段
                                </a-button>
                              </a-tooltip>
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

                    <!-- 报表方案：查询条件 QuicklyCondition -->
                    <a-row :gutter="16" v-if="bodyMode === 'REPORT'">
                      <a-col :span="24">
                        <a-form-item label="查询条件 (QuicklyCondition)">
                          <div class="fk-toolbar">
                            <span class="fk-count">
                              {{ quickConditions.length }} 个条件 · 每行为 FieldName = FieldValue
                            </span>
                            <a-button size="small" @click="addQuickCondition">
                              <plus-outlined /> 添加条件
                            </a-button>
                          </div>
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <div v-if="bodyMode === 'REPORT'" class="filt-panel">
                      <div class="var-box">
                        <div class="var-head">
                          <span class="var-title">变量定义</span>
                          <span class="var-hint">
                            执行时在目标数据源上以 SQL 计算，条件值里用 <code>${名称}</code> 引用；内置
                            <code>${today}</code> <code>${yesterday}</code> <code>${now}</code>
                          </span>
                          <a-button size="small" type="link" @click="addVariable">
                            <plus-outlined /> 添加变量
                          </a-button>
                        </div>
                        <div v-for="(v, vi) in filterVariables" :key="v.rid" class="var-item">
                          <div class="var-row">
                            <a-input
                              v-model:value="v.name"
                              size="small"
                              class="fk-input-mono"
                              placeholder="变量名"
                              style="width: 148px; flex: none"
                              @change="onVariableEdited(v)"
                            />
                            <span class="var-eq">=</span>
                            <a-textarea
                              v-model:value="v.sql"
                              size="small"
                              class="fk-input-mono var-sql"
                              :auto-size="{ minRows: 1, maxRows: 3 }"
                              placeholder="SELECT 表达式，如 DATE_SUB(CURDATE(), INTERVAL ((WEEKDAY(CURDATE()) + 3) % 7 + 7) DAY)"
                              style="flex: 1; min-width: 260px"
                              @change="onVariableEdited(v)"
                            />
                            <a-tooltip title="在目标数据源上执行一次，立即看到取值">
                              <a-button
                                size="small"
                                :loading="v.testing"
                                @click="testVariable(v)"
                              >
                                试算
                              </a-button>
                            </a-tooltip>
                            <a-button size="small" type="link" danger @click="removeVariable(vi)">
                              删除
                            </a-button>
                          </div>
                          <div
                            v-if="v.result"
                            class="var-result"
                            :class="{ 'var-result-err': v.resultError }"
                          >
                            <template v-if="v.resultError">试算失败：{{ v.result }}</template>
                            <template v-else>
                              {{ v.name || '结果' }} = <b>{{ v.result }}</b>
                              <span class="var-result-time">（{{ v.elapsedMs }} ms）</span>
                            </template>
                          </div>
                        </div>
                        <div v-if="!filterVariables.length" class="var-empty">
                          未定义变量。需要「上个会计周」这类动态日期时，在此配置 SQL 即可，条件值选「变量」引用。
                        </div>
                      </div>

                      <div v-for="(row, index) in quickConditions" :key="row.rid" class="filt-row">
                        <a-auto-complete
                          v-model:value="row.fieldName"
                          size="small"
                          class="fk-input-mono"
                          :options="filterFieldOptions"
                          placeholder="条件字段，如 BeginDate"
                          style="flex: 1; min-width: 200px"
                          @change="syncKingdeeQueryToBody"
                        />
                        <span class="filt-where">=</span>
                        <a-input
                          v-model:value="row.fieldValue"
                          size="small"
                          class="fk-input-mono"
                          placeholder="值：变量写 ${NAME}，文本直接填"
                          style="flex: 1; min-width: 200px"
                          @change="syncKingdeeQueryToBody"
                        />
                        <a-button
                          size="small"
                          type="link"
                          danger
                          style="flex: none"
                          @click="removeQuickCondition(index)"
                        >
                          删除
                        </a-button>
                      </div>
                      <div v-if="!quickConditions.length" class="filt-empty">
                        尚未添加条件，点击右上角「+ 添加条件」
                      </div>
                      <div class="filt-preview">
                        生成：<code>{{ quickConditionPreview || '（空）' }}</code>
                      </div>
                    </div>

                    <a-row :gutter="16" v-if="bodyMode !== 'REPORT'">
                      <a-col :span="24">
                        <a-form-item label="过滤条件 (FilterString)">
                          <div class="fk-toolbar">
                            <span class="fk-count">
                              {{ filterConditions.length }} 个条件 ·
                              {{ filterMode === 'table' ? '结构化' : '文本' }}模式
                            </span>
                            <a-space :size="6">
                              <a-button
                                v-if="filterMode === 'table'"
                                size="small"
                                @click="addFilterCondition"
                              >
                                <plus-outlined /> 添加条件
                              </a-button>
                              <a-button size="small" type="link" @click="switchFilterMode">
                                {{ filterMode === 'table' ? '切换到文本模式' : '切换到结构化' }}
                              </a-button>
                            </a-space>
                          </div>
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <div
                      v-if="bodyMode !== 'REPORT' && filterMode === 'table'"
                      class="filt-panel"
                    >
                      <!-- 变量定义：值由 SQL 在目标数据源上计算 -->
                      <div class="var-box">
                        <div class="var-head">
                          <span class="var-title">变量定义</span>
                          <span class="var-hint">
                            执行时在目标数据源上以 SQL 计算，条件值里用 <code>${名称}</code> 引用；内置
                            <code>${today}</code> <code>${yesterday}</code> <code>${now}</code>
                          </span>
                          <a-button size="small" type="link" @click="addVariable">
                            <plus-outlined /> 添加变量
                          </a-button>
                        </div>
                        <div v-for="(v, vi) in filterVariables" :key="v.rid" class="var-item">
                          <div class="var-row">
                            <a-input
                              v-model:value="v.name"
                              size="small"
                              class="fk-input-mono"
                              placeholder="变量名"
                              style="width: 148px; flex: none"
                              @change="onVariableEdited(v)"
                            />
                            <span class="var-eq">=</span>
                            <a-textarea
                              v-model:value="v.sql"
                              size="small"
                              class="fk-input-mono var-sql"
                              :auto-size="{ minRows: 1, maxRows: 3 }"
                              placeholder="SELECT 表达式，如 DATE_SUB(CURDATE(), INTERVAL ((WEEKDAY(CURDATE()) + 3) % 7 + 7) DAY)"
                              style="flex: 1; min-width: 260px"
                              @change="onVariableEdited(v)"
                            />
                            <a-tooltip title="在目标数据源上执行一次，立即看到取值">
                              <a-button
                                size="small"
                                :loading="v.testing"
                                @click="testVariable(v)"
                              >
                                试算
                              </a-button>
                            </a-tooltip>
                            <a-button size="small" type="link" danger @click="removeVariable(vi)">
                              删除
                            </a-button>
                          </div>
                          <div
                            v-if="v.result"
                            class="var-result"
                            :class="{ 'var-result-err': v.resultError }"
                          >
                            <template v-if="v.resultError">试算失败：{{ v.result }}</template>
                            <template v-else>
                              {{ v.name || '结果' }} = <b>{{ v.result }}</b>
                              <span class="var-result-time">（{{ v.elapsedMs }} ms）</span>
                            </template>
                          </div>
                        </div>
                        <div v-if="!filterVariables.length" class="var-empty">
                          未定义变量。需要「上个会计周」这类动态日期时，在此配置 SQL 即可，条件值选「变量」引用。
                        </div>
                      </div>

                      <div v-for="(row, index) in filterConditions" :key="row.rid" class="filt-row">
                        <a-select
                          v-if="index > 0"
                          v-model:value="row.connector"
                          size="small"
                          style="width: 78px; flex: none"
                          :options="[
                            { label: 'AND', value: 'AND' },
                            { label: 'OR', value: 'OR' }
                          ]"
                          @change="applyFilterConditions"
                        />
                        <span v-else class="filt-where">WHERE</span>
                        <a-auto-complete
                          v-model:value="row.field"
                          size="small"
                          class="fk-input-mono"
                          :options="filterFieldOptions"
                          placeholder="选择或输入字段名"
                          style="flex: 1; min-width: 190px"
                          @change="applyFilterConditions"
                        />
                        <a-select
                          v-model:value="row.op"
                          size="small"
                          style="width: 116px; flex: none"
                          :options="FILTER_OPS.map((o) => ({ label: o, value: o }))"
                          @change="applyFilterConditions"
                        />
                        <template v-if="needsFilterValue(row.op)">
                          <a-select
                            v-model:value="row.valueType"
                            size="small"
                            style="width: 104px; flex: none"
                            :options="FILTER_VALUE_TYPES"
                            @change="applyFilterConditions"
                          />
                          <a-auto-complete
                            v-if="row.valueType === 'VARIABLE'"
                            v-model:value="row.value"
                            size="small"
                            class="fk-input-mono"
                            :options="variableOptions"
                            placeholder="选择变量名，如 startdate"
                            style="flex: 1; min-width: 160px"
                            @change="applyFilterConditions"
                          />
                          <a-input
                            v-else
                            v-model:value="row.value"
                            size="small"
                            style="flex: 1; min-width: 150px"
                            :placeholder="
                              row.valueType === 'RAW'
                                ? '原始表达式，如 0 或 CURDATE()'
                                : row.op.toUpperCase().includes('IN')
                                  ? '多个值用英文逗号分隔'
                                  : '条件值'
                            "
                            @change="applyFilterConditions"
                          />
                        </template>
                        <div v-else style="flex: 1"></div>
                        <a-button
                          size="small"
                          type="link"
                          danger
                          style="flex: none"
                          @click="removeFilterCondition(index)"
                        >
                          删除
                        </a-button>
                      </div>

                      <div v-if="!filterConditions.length" class="filt-empty">
                        尚未添加条件，点击右上角「+ 添加条件」
                      </div>
                      <div class="filt-preview">
                        生成：<code>{{ kingdeeQuery.filterString || '（空）' }}</code>
                      </div>
                    </div>
                    <div v-else-if="bodyMode !== 'REPORT'" class="filt-panel">
                      <a-textarea
                        v-model:value="kingdeeQuery.filterString"
                        :rows="3"
                        class="code-textarea"
                        placeholder="例如：FModifyDate >= '${startdate}' AND FDocumentStatus = 'C'"
                        @change="syncKingdeeQueryToBody"
                      />
                      <div class="fk-hint">
                        文本模式原样写入 FilterString；${变量} 会在执行时按「变量定义」中的 SQL 解析。
                      </div>
                    </div>

                    <!-- 循环取数 -->
                    <div class="loop-box">
                      <div class="loop-head">
                        <span class="loop-title">循环取数</span>
                        <a-switch
                          v-model:checked="loopConfig.enabled"
                          checked-children="已开启"
                          un-checked-children="未开启"
                          @change="onLoopEdited"
                        />
                        <span class="loop-hint">
                          按循环 SQL 返回的每一行跑一轮完整流程（串行，某轮失败即中止后续）
                        </span>
                        <a-button
                          v-if="loopConfig.enabled"
                          size="small"
                          :loading="loopPreviewLoading"
                          @click="previewLoop"
                        >
                          试算
                        </a-button>
                      </div>
                      <template v-if="loopConfig.enabled">
                        <a-textarea
                          v-model:value="loopConfig.sql"
                          :rows="5"
                          class="code-textarea"
                          :placeholder="loopSqlPlaceholder"
                          @change="onLoopEdited"
                        />
                        <a-row :gutter="16" style="margin-top: 8px">
                          <a-col :span="8">
                            <a-form-item label="最多循环次数">
                              <a-input-number
                                v-model:value="loopConfig.maxIterations"
                                :min="1"
                                :max="10000"
                                style="width: 100%"
                                @change="onLoopEdited"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="8">
                            <a-form-item label="每轮间隔 (ms)">
                              <a-input-number
                                v-model:value="loopConfig.intervalMs"
                                :min="0"
                                :max="60000"
                                style="width: 100%"
                                @change="onLoopEdited"
                              />
                            </a-form-item>
                          </a-col>
                        </a-row>
                        <div class="fk-hint">
                          列名即变量名，会覆盖「变量定义」里的同名变量；多维度组合（如 13 个工厂 × 4 个会计周）
                          直接在 SQL 里 CROSS JOIN 出行，系统逐行执行。
                        </div>
                        <div v-if="loopPreview" class="loop-preview">
                          <div class="loop-preview-head">
                            共 <b>{{ loopPreview.rowCount }}</b> 轮 · 列：
                            {{ loopPreview.columns.join(', ') }}
                          </div>
                          <div v-for="(r, i) in loopPreview.rows" :key="i" class="loop-preview-row">
                            {{ i + 1 }}. {{ formatLoopRow(r) }}
                          </div>
                          <div v-if="loopPreview.rowCount > loopPreview.rows.length" class="loop-preview-more">
                            …… 其余 {{ loopPreview.rowCount - loopPreview.rows.length }} 轮未展示
                          </div>
                        </div>
                      </template>
                    </div>

                    <a-row :gutter="16" v-if="bodyMode !== 'REPORT'">
                      <a-col :span="12">
                        <a-form-item label="排序规则 (OrderString)">
                          <a-input
                            v-model:value="kingdeeQuery.orderString"
                            placeholder="例如：FBillNo ASC"
                            @change="syncKingdeeQueryToBody"
                          />
                        </a-form-item>
                      </a-col>
                      <a-col :span="12">
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
                            <span class="field-hint">
                              系统已根据上方字段自动同步为金蝶标准 JSON 结构，支持在此自由微调扩展参数
                            </span>
                          </template>
                          <a-textarea
                            v-model:value="formData.apiBody"
                            :rows="7"
                            class="code-textarea"
                            :placeholder="bodyPlaceholder"
                            @change="onBodyEdited"
                          />
                          <div v-if="bodyShape === 'UNKNOWN'" class="body-warn">
                            当前报文既不是「标准查询」也不是「报表方案」结构（JSON
                            可能语法有误）：保存时将<b>按原文提交</b>，不会用上方的 FormId / 字段覆盖它。
                          </div>
                          <div v-else-if="bodyManual" class="body-note">
                            已手工编辑请求体：保存以此处文本为准，上方字段仅用于展示与解析。
                          </div>
                        </a-form-item>
                      </a-col>
                    </a-row>

                    <!-- 高级配置：分页取数 -->
                    <a-collapse v-model:activeKey="advancedActiveKeys" :bordered="false" class="adv-collapse">
                      <a-collapse-panel key="pagination">
                        <template #header>
                          <span class="adv-title">高级配置 · 分页取数</span>
                          <a-tag
                            :color="pagination.enabled ? 'green' : 'default'"
                            class="adv-tag"
                          >
                            {{ pagination.enabled ? '已开启' : '未开启' }}
                          </a-tag>
                          <span class="adv-sub">数据量大时按偏移量翻页</span>
                        </template>
                        <a-row :gutter="16">
                          <a-col :span="6">
                            <a-form-item label="分页取数">
                              <a-switch
                                v-model:checked="pagination.enabled"
                                checked-children="已开启"
                                un-checked-children="未开启"
                                @change="onPaginationToggle"
                              />
                            </a-form-item>
                          </a-col>
                          <a-col :span="6">
                            <a-form-item label="每页条数 (limit)">
                              <a-input :value="pagination.pageSize" disabled />
                              <div class="fk-hint">跟随上方「单次抓取上限」联动</div>
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
                          <a-col :span="8">
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
                          <a-col :span="8">
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
                          <a-col :span="8">
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

                    <!-- 附加字段：抽取字段之外，额外写入数仓的列 -->
                    <div class="extra-box">
                      <div class="extra-head">
                        <span class="extra-title">附加字段</span>
                        <span class="extra-hint">
                          除抽取字段外，额外写入数仓的列（如 begindate / enddate 这类时间变量）
                        </span>
                        <a-button size="small" type="link" @click="addExtraColumn">
                          <plus-outlined /> 添加列
                        </a-button>
                      </div>
                      <div v-for="(col, index) in extraColumns" :key="col.rid" class="filt-row">
                        <a-input
                          v-model:value="col.name"
                          size="small"
                          class="fk-input-mono"
                          placeholder="列名，如 begindate"
                          style="flex: 1; min-width: 150px"
                          @change="onExtraColumnsChanged"
                        />
                        <a-select
                          v-model:value="col.valueType"
                          size="small"
                          style="width: 96px; flex: none"
                          :options="[
                            { label: '变量', value: 'VARIABLE' },
                            { label: '常量', value: 'CONSTANT' }
                          ]"
                          @change="onExtraColumnsChanged"
                        />
                        <a-auto-complete
                          v-if="col.valueType === 'VARIABLE'"
                          v-model:value="col.value"
                          size="small"
                          class="fk-input-mono"
                          :options="variableOptions"
                          placeholder="变量名，如 begindate"
                          style="flex: 1; min-width: 160px"
                          @change="onExtraColumnsChanged"
                        />
                        <a-input
                          v-else
                          v-model:value="col.value"
                          size="small"
                          class="fk-input-mono"
                          placeholder="常量值，可写 ${变量}"
                          style="flex: 1; min-width: 160px"
                          @change="onExtraColumnsChanged"
                        />
                        <a-select
                          v-model:value="col.type"
                          size="small"
                          style="width: 130px; flex: none"
                          :options="fieldTypeSelectOptions"
                          @change="onExtraColumnsChanged"
                        />
                        <a-button
                          size="small"
                          type="link"
                          danger
                          style="flex: none"
                          @click="removeExtraColumn(index)"
                        >
                          删除
                        </a-button>
                      </div>
                      <div v-if="!extraColumns.length" class="filt-empty">
                        未配置附加列：只写入从接口抽取到的字段
                      </div>
                      <div class="fk-hint">
                        列名会用于建临时表与入库，只允许字母/数字/下划线；选「变量」取上方变量定义（运行时按 SQL
                        计算），时间类变量列类型建议选 DATE / DATETIME。字段映射里会同步出现这些列，可用「更新目标表」一键建到数仓。
                      </div>
                    </div>
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
          <a-tab-pane key="3" tab="🔄 3. SQL 转换处理（可选）">
            <div class="tab-pane-content">
              <a-alert
                message="💡 数据流转机制说明"
                description="步骤2抽取的数据将首先自动加载至当前流程的专属临时表 ${stage_table}。您可在此编写原生 SQL 语句进行清洗、关联、计算，并最终写入数仓目标表 ${target_table}；留空则由系统按目标表结构自动生成直通入库脚本。"
                type="info"
                show-icon
                style="margin-bottom: 16px"
              />

              <a-card :bordered="false" class="pane-card">
                <a-form-item name="transformSql">
                  <SqlEditor
                    v-model="formData.transformSql"
                    placeholder="-- 清空目标表并覆盖写入示例：
INSERT INTO ${target_table} (id, material_code, material_name, sync_date)
SELECT id, material_code, name, '${today}'
FROM ${stage_table}
ON DUPLICATE KEY UPDATE material_name = VALUES(material_name);"
                  />
                  <div class="fk-hint" style="margin-top: 8px">
                    留空即可：系统会按目标表与抽取字段的<b>同名列</b>自动生成
                    <code>INSERT INTO ... SELECT ...</code>（按写入模式决定是否先清空、是否 UPSERT）。
                    需要改字段名、聚合、过滤或多表关联时，再在这里写脚本。
                  </div>
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
          <a-tab-pane key="4" tab="📤 4. 目标数仓输出">
            <div class="tab-pane-content">
              <a-card :bordered="false" class="pane-card">
                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="目标 MySQL 数仓连接" name="targetDsId" required>
                      <a-select
                        v-model:value="formData.targetDsId"
                        placeholder="选择目标数仓连接"
                        allow-clear
                        @change="onTargetDsChange"
                      >
                        <a-select-option v-for="ds in datasourceList" :key="ds.id" :value="ds.id">
                          {{ ds.dsName }} ({{ ds.host ? `${ds.host}:${ds.port || 3306}/${ds.databaseName || ''}` : ds.jdbcUrl }})
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="目标数据表名" name="targetTable" required>
                      <a-auto-complete
                        v-model:value="formData.targetTable"
                        :options="targetTableOptions"
                        placeholder="选择已有表，或直接输入新表名"
                        :filter-option="filterTableOption"
                        @select="onTargetTableChange"
                        @blur="onTargetTableChange"
                      />
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
                        <a-select-option value="SYNC">SYNC (增量同步：合并 + 软删除)</a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <a-col :span="12" v-if="formData.writeMode === 'UPSERT' || formData.writeMode === 'SYNC'">
                    <a-form-item
                      :label="
                        formData.writeMode === 'SYNC'
                          ? '比对主键列 (逗号分隔，如 FEntity_FENTRYID)'
                          : '唯一主键列 (逗号分隔)'
                      "
                    >
                      <a-input
                        v-model:value="formData.upsertKeys"
                        :placeholder="
                          formData.writeMode === 'SYNC'
                            ? '金蝶分录内码，如 FEntity_FENTRYID'
                            : '例如：id 或 material_code'
                        "
                      />
                    </a-form-item>
                  </a-col>
                </a-row>

                <!-- 增量同步配置 -->
                <div v-if="formData.writeMode === 'SYNC'" class="sync-box">
                  <div class="sync-title">增量同步配置</div>
                  <a-row :gutter="16">
                    <a-col :span="8">
                      <a-form-item label="删除判定范围字段">
                        <a-input
                          v-model:value="syncConfig.scopeField"
                          placeholder="如 FAuditDate（审核时间）"
                          @change="syncSyncConfig"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="窗口天数（近 N 天）">
                        <a-input-number
                          v-model:value="syncConfig.scopeDays"
                          :min="1"
                          :max="3650"
                          style="width: 100%"
                          @change="syncSyncConfig"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="删除比例上限（超过则中止）">
                        <a-input-number
                          v-model:value="syncConfig.maxDeleteRatio"
                          :min="0.05"
                          :max="1"
                          :step="0.05"
                          :precision="2"
                          style="width: 100%"
                          @change="syncSyncConfig"
                        />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-row :gutter="16">
                    <a-col :span="8">
                      <a-form-item label="软删除标记列">
                        <a-input
                          v-model:value="syncConfig.softDeleteColumn"
                          placeholder="is_deleted"
                          @change="syncSyncConfig"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="软删除时间列（留空则不打时间）">
                        <a-input
                          v-model:value="syncConfig.softDeleteTimeColumn"
                          placeholder="delete_time"
                          @change="syncSyncConfig"
                        />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <div class="fk-hint">
                    执行时：在「范围字段 ≥ 今天 − N 天」的窗口内，目标表有、本次抽取没有的行 → 标记删除；
                    本次数据按主键合并写入，并把删除标记复位。第 3 步的转换 SQL 需<b>留空</b>，由系统生成脚本。
                    目标表需先具备软删除列（如 <code>is_deleted TINYINT DEFAULT 0</code>、
                    <code>delete_time DATETIME NULL</code>）。
                  </div>
                </div>

                <!-- 字段映射：左=抽取字段，右=目标表字段 -->
                <div class="map-box">
                  <div class="map-head">
                    <span class="map-title">字段映射</span>
                    <span class="map-sub">{{ mappingSummary }}</span>
                    <a-space :size="8">
                      <a-button size="small" :loading="mappingLoading" @click="refreshMapping">
                        重新获取
                      </a-button>
                      <a-button
                        size="small"
                        type="primary"
                        ghost
                        :disabled="!formData.targetDsId || !formData.targetTable"
                        @click="openSchemaModal"
                      >
                        更新（新建）目标表
                      </a-button>
                    </a-space>
                  </div>

                  <div v-if="!formData.targetDsId" class="map-empty">
                    请先选择目标数据源；选择后自动读取表清单与表结构
                  </div>
                  <div v-else-if="!formData.targetTable" class="map-empty">
                    选择或输入目标表名后，这里会显示左右字段对照
                  </div>
                  <div v-else-if="!mappingRows.length" class="map-empty">
                    暂无字段。左侧来自第 2 步抽取字段清单；若尚未配置，请先回到第 2 步维护字段
                  </div>
                  <div v-else class="map-table">
                    <div class="map-row map-row-head">
                      <span>抽取字段</span>
                      <span>来源类型</span>
                      <span class="map-arrow"></span>
                      <span>目标表字段</span>
                      <span>目标类型</span>
                      <span>状态</span>
                    </div>
                    <div v-for="row in mappingRows" :key="row.key" class="map-row">
                      <span class="map-name">{{ row.sourceName || '—' }}</span>
                      <span class="map-type">{{ row.sourceType || '' }}</span>
                      <span class="map-arrow">
                        {{ row.status === 'MISSING_IN_TARGET' ? '→' : row.status === 'MISSING_IN_SOURCE' ? '←' : '↔' }}
                      </span>
                      <span class="map-name">{{ row.targetName || '—' }}</span>
                      <span class="map-type">{{ row.targetType || '' }}</span>
                      <span :class="['map-status', row.statusClass]">{{ row.statusText }}</span>
                    </div>
                  </div>
                  <div v-if="formData.writeMode === 'SYNC' && mappingRows.length" class="fk-hint">
                    增量同步模式已自动把软删除列计入左侧字段，目标表缺列时可通过「更新（新建）目标表」补齐。
                  </div>
                </div>
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
          <a-tab-pane key="5" tab="⏰ 5. 调度与告警">
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

    <!-- 目标表结构变更确认 -->
    <a-modal
      v-model:open="schemaModal.visible"
      :title="`更新目标表：${formData.targetTable || ''}`"
      width="820px"
      :confirm-loading="schemaModal.submitting"
      ok-text="确认更新"
      cancel-text="取消"
      @ok="submitSchema"
    >
      <a-alert
        v-if="!schemaModal.tableExists"
        type="warning"
        show-icon
        message="目标表不存在，确认后将按左侧字段新建"
        description="主键可留空；若写入模式为 SYNC，建议把比对主键设为 PRIMARY KEY，否则合并写入会重复。"
        style="margin-bottom: 12px"
      />
      <a-alert
        v-else
        type="info"
        show-icon
        :message="schemaPlanText"
        description="类型修改只改列类型，NULL 约束按原样保留（主键列强制 NOT NULL）；删除列不可恢复。变更会立即在目标库执行。"
        style="margin-bottom: 12px"
      />

      <div class="schema-section">
        <div class="schema-title">新增字段（左侧有、目标表无）</div>
        <div v-if="!schemaModal.addColumns.length" class="schema-empty">无</div>
        <div v-for="(col, i) in schemaModal.addColumns" :key="col.name" class="schema-row">
          <span class="schema-name">{{ col.name }}</span>
          <a-input v-model:value="col.type" size="small" style="width: 220px" />
          <a-button size="small" type="link" danger @click="schemaModal.addColumns.splice(i, 1)">
            移除
          </a-button>
        </div>
      </div>

      <div class="schema-section">
        <div class="schema-title">类型变更（目标表已有、与左侧类型不一致）</div>
        <div v-if="!schemaModal.modifyColumns.length" class="schema-empty">无</div>
        <div v-for="col in schemaModal.modifyColumns" :key="col.name" class="schema-row">
          <a-checkbox v-model:checked="col.checked" :disabled="col.autoIncrement" />
          <span class="schema-name">{{ col.name }}</span>
          <span class="schema-type-old">{{ col.currentType }}</span>
          <span class="schema-arrow">→</span>
          <a-input
            v-model:value="col.type"
            size="small"
            style="width: 180px"
            :disabled="!col.checked || col.autoIncrement"
          />
        </div>
        <div v-if="schemaModal.modifyColumns.length" class="fk-hint">
          勾选后按右侧类型执行 MODIFY COLUMN；自增列不支持改类型。TEXT 列要先改成
          VARCHAR(n) 才能设为主键。
        </div>
      </div>

      <div class="schema-section">
        <div class="schema-title">删除字段（目标表有、左侧无）</div>
        <div v-if="!schemaModal.dropCandidates.length" class="schema-empty">无</div>
        <a-checkbox-group v-else v-model:value="schemaModal.dropColumns">
          <a-checkbox v-for="c in schemaModal.dropCandidates" :key="c" :value="c">{{ c }}</a-checkbox>
        </a-checkbox-group>
        <div v-if="schemaModal.dropCandidates.length" class="fk-hint">
          默认不勾选；只有勾选的列才会执行 DROP COLUMN（不可恢复，请确认下游没有依赖）
        </div>
      </div>

      <div class="schema-section">
        <div class="schema-title">主键（可多选）</div>
        <a-checkbox-group v-model:value="schemaModal.primaryKeys">
          <a-checkbox v-for="f in schemaModal.keyCandidates" :key="f" :value="f">{{ f }}</a-checkbox>
        </a-checkbox-group>
        <div class="fk-hint">
          当前主键：{{ schemaModal.currentKeys.length ? schemaModal.currentKeys.join(', ') : '无' }}；
          勾选后会先删除旧主键再添加新主键，主键列自动设为 NOT NULL
        </div>
        <a-checkbox
          v-if="schemaModal.currentKeys.length"
          v-model:checked="schemaModal.dropPrimaryKey"
          style="margin-top: 6px"
        >
          移除现有主键（上面不选新主键时即清空主键）
        </a-checkbox>
      </div>
    </a-modal>

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
  detectKingdeeForm,
  detectKingdeeReportFields,
  saveKingdeeCredential,
  getKingdeeCredentialDefault,
  evaluateVariable,
  evaluateLoop,
  listTargetTables,
  describeTargetTable,
  applyTargetSchema,
  type TargetTableColumn,
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
const bodyPlaceholderStandard = `{\n  "data": {\n    "FormId": "PUR_PurchaseOrder",\n    "FieldKeys": "FID,FBillNo,FDate,FSupplierId.FNumber,FDocumentStatus",\n    "FilterString": "FDate >= '\${yesterday}'",\n    "OrderString": "FID DESC",\n    "Limit": 2000,\n    "StartRow": 0\n  }\n}`
const bodyPlaceholderReport = `{\n  "parameters": [\n    {\n      "FORMID": "STK_StockSummaryRpt",\n      "FSCHEMEID": "6ac9a38a6e3769",\n      "QuicklyCondition": [\n        { "FieldName": "BeginDate", "FieldValue": "\${STARTDATE}" },\n        { "FieldName": "EndDate", "FieldValue": "\${ENDDATE}" }\n      ],\n      "StartRow": 0,\n      "Limit": 10000,\n      "FieldKeys": "FMATERIALNUMBER,FMATERIALNAME"\n    }\n  ]\n}`
const bodyPlaceholder = computed(() =>
  bodyMode.value === 'REPORT' ? bodyPlaceholderReport : bodyPlaceholderStandard
)

const initialFormData = (): Partial<EtlPipeline> => ({
  sourceType: 'MYSQL',
  apiAuthType: 'KINGDEE_K3',
  apiMethod: 'POST',
  writeMode: 'APPEND',
  syncConfig: '',
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

// ===== 金蝶取数方式：标准查询 / 报表方案查询 =====
type BodyMode = 'STANDARD' | 'REPORT'

// 标准查询：{"data":{"FormId":...,"FilterString":...}} —— ExecuteBillQuery / BillQuery
// 报表方案：{"parameters":[{"FORMID":...,"FSCHEMEID":...,"QuicklyCondition":[...]}]}
// 实际生效的报文模式（由 自动识别 / 手动指定 / 已保存报文 决定）
const bodyMode = ref<BodyMode>('STANDARD')

// 用户的选择：自动识别 / 手动指定标准查询 / 手动指定报表方案。
// 手动指定后不再被自动识别结果覆盖（随 filter_meta 持久化）
type BodyModeSel = 'AUTO' | BodyMode
const bodyModeSel = ref<BodyModeSel>('AUTO')

// 自动识别状态
const detectLoading = ref(false)
const detectError = ref('')
const detectResult = ref<{
  formId: string
  type: BodyMode
  fieldCount: number
  reason: string
} | null>(null)
let detectTimer: ReturnType<typeof setTimeout> | undefined

// 报文结构识别结果：UNKNOWN 表示既不是标准查询也不是报表方案（例如手工粘贴的自定义报文）
type BodyShape = BodyMode | 'UNKNOWN'

// 请求体是否被用户手工改过。为 true 时保存以文本原文为准，
// 不再用向导里的 FormId / 字段重新生成，避免"保存后配置被还原成旧值"
const bodyManual = ref(false)
// 保存时对报文的提示（例如结构无法识别时按原文提交）
const bodySaveWarning = ref('')

// 报表方案的过滤方案 ID
const schemeId = ref('')

interface QuickCondition {
  rid: string
  fieldName: string
  fieldValue: string
}

let quickRidSeed = 0
const quickConditions = ref<QuickCondition[]>([])

const defaultQuickCondition = (): QuickCondition => {
  quickRidSeed += 1
  return { rid: `qc_${quickRidSeed}`, fieldName: '', fieldValue: '' }
}

const addQuickCondition = () => {
  quickConditions.value.push(defaultQuickCondition())
  syncKingdeeQueryToBody()
}

const removeQuickCondition = (index: number) => {
  quickConditions.value.splice(index, 1)
  syncKingdeeQueryToBody()
}

// 手动切换取数方式：记住选择并重建报文模板；手动选定后不再被自动识别覆盖
const onBodyModeSelChange = () => {
  formData.value.filterMeta = buildFilterMeta()
  detectResult.value = null
  detectError.value = ''
  if (bodyModeSel.value === 'AUTO') {
    detectFormType()
    return
  }
  bodyMode.value = bodyModeSel.value
  syncKingdeeQueryToBody()
}

// FormId 改动：先同步报文，再在「自动识别」下重新探测表单类型
const onFormIdChanged = () => {
  syncKingdeeQueryToBody()
  detectResult.value = null
  detectError.value = ''
  scheduleDetect()
}

// 输入过程中防抖，避免每敲一个字符就打一次金蝶
const scheduleDetect = () => {
  if (bodyModeSel.value !== 'AUTO') return
  if (detectTimer) clearTimeout(detectTimer)
  detectTimer = setTimeout(() => detectFormType(), 600)
}

/**
 * 探测表单类型（单据 / 报表）。
 *
 * <p>只有「自动识别」模式才会改报文模板；手动指定时仅展示探测结果，不覆盖用户选择。
 */
const detectFormType = async () => {
  const formId = kingdeeQuery.value.formId?.trim()
  if (!formId || !kingdeeConfig.value.loginUrl) {
    detectResult.value = null
    detectError.value = ''
    return
  }
  if (detectLoading.value) return
  detectLoading.value = true
  detectError.value = ''
  try {
    const r = await detectKingdeeForm({
      loginUrl: kingdeeConfig.value.loginUrl,
      acctID: kingdeeConfig.value.acctID,
      userName: kingdeeConfig.value.userName,
      password: effectivePassword() || undefined,
      lcid: kingdeeConfig.value.lcid,
      formId
    })
    detectResult.value = {
      formId: r.formId || formId,
      // 后端返回 BILL / REPORT，前端用 STANDARD / REPORT 表示报文形态
      type: r.type === 'BILL' ? 'STANDARD' : 'REPORT',
      fieldCount: r.fieldCount,
      reason: r.reason
    }
    if (bodyModeSel.value === 'AUTO' && bodyMode.value !== detectResult.value.type) {
      bodyMode.value = detectResult.value.type
      if (!bodyManual.value) {
        syncKingdeeQueryToBody()
      }
    }
  } catch (e: any) {
    detectResult.value = null
    detectError.value = e?.message || '探测失败'
  } finally {
    detectLoading.value = false
  }
}

// ===== 金蝶服务地址纠错 =====
// K3Cloud 的装配名分隔符是英文逗号：GetReportData,Kingdee.K3.SCM...common.kdsvc
// 误写成点号时金蝶会直接返回 "... no found"，是报表方案跑不通的最常见原因
const K3_ASSEMBLY_DOT = /\.(Kingdee\.[A-Za-z0-9_.]+)\.common\.kdsvc$/i

const normalizeK3Url = (url: string) =>
  String(url || '').replace(K3_ASSEMBLY_DOT, ',$1.common.kdsvc')

const reportUrlMalformed = computed(() => K3_ASSEMBLY_DOT.test(formData.value.apiUrl || ''))
const normalizedApiUrl = computed(() => normalizeK3Url(formData.value.apiUrl || ''))

const fixReportUrl = () => {
  formData.value.apiUrl = normalizedApiUrl.value
  message.success('接口地址已修正为逗号分隔')
}

// 报表模式的条件预览
const quickConditionPreview = computed(() =>
  quickConditions.value
    .filter((c) => c.fieldName.trim())
    .map((c) => `${c.fieldName.trim()} = ${c.fieldValue}`)
    .join(' AND ')
)

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
// 默认展开，避免被当成一条不起眼的折叠项
const advancedActiveKeys = ref<string[]>(['pagination'])

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
      advancedActiveKeys.value = ['pagination']
    } catch (e) {
      pagination.value = defaultPagination()
    }
  }
  // 每页条数以「单次抓取上限」为准，避免两处配置不一致
  pagination.value.pageSize = kingdeeQuery.value.limit || pagination.value.pageSize || 2000
}

// 开启分页时先把每页条数对齐到单次抓取上限
const onPaginationToggle = () => {
  if (pagination.value.enabled) {
    pagination.value.pageSize = kingdeeQuery.value.limit || 2000
  }
  syncPagination()
}

// ===== 过滤条件：多字段 · 每字段多条件 =====
type FilterValueType = 'TEXT' | 'NUMBER' | 'VARIABLE' | 'RAW'

interface FilterCondition {
  rid: string
  connector: 'AND' | 'OR'
  field: string
  op: string
  valueType: FilterValueType
  value: string
}

// 变量定义：值由一段 SQL 在目标数据源上实时计算
interface FilterVariable {
  rid: string
  name: string
  sql: string
  // 以下为试算用的界面状态，不写入 filter_meta
  testing?: boolean
  result?: string
  resultError?: boolean
  elapsedMs?: number
}

const FILTER_OPS = [
  '=',
  '<>',
  '>',
  '>=',
  '<',
  '<=',
  'LIKE',
  'NOT LIKE',
  'IN',
  'NOT IN',
  'IS NULL',
  'IS NOT NULL'
]

const FILTER_VALUE_TYPES = [
  { label: '文本', value: 'TEXT' },
  { label: '数字', value: 'NUMBER' },
  { label: '变量', value: 'VARIABLE' },
  { label: '原始表达式', value: 'RAW' }
]

let filterRidSeed = 0
const defaultFilterCondition = (): FilterCondition => {
  filterRidSeed += 1
  return {
    rid: `fc_${filterRidSeed}`,
    connector: 'AND',
    field: '',
    op: '=',
    valueType: 'TEXT',
    value: ''
  }
}

const defaultFilterVariable = (): FilterVariable => {
  filterRidSeed += 1
  return { rid: `fv_${filterRidSeed}`, name: '', sql: '' }
}

const filterConditions = ref<FilterCondition[]>([])
const filterVariables = ref<FilterVariable[]>([])

// ===== 附加列：除抽取字段外，额外写进数仓的列（如 begindate / enddate 这类时间变量）=====
type ExtraValueType = 'VARIABLE' | 'CONSTANT'

interface ExtraColumn {
  rid: string
  name: string
  valueType: ExtraValueType
  value: string
  /** 建临时表/目标表用的列类型 */
  type: string
}

const extraColumns = ref<ExtraColumn[]>([])
let extraRidSeed = 0

const defaultExtraColumn = (): ExtraColumn => {
  extraRidSeed += 1
  return { rid: `ec_${extraRidSeed}`, name: '', valueType: 'VARIABLE', value: '', type: 'VARCHAR(255)' }
}

const addExtraColumn = () => {
  extraColumns.value.push(defaultExtraColumn())
}

const removeExtraColumn = (index: number) => {
  extraColumns.value.splice(index, 1)
  onExtraColumnsChanged()
}

// 附加列改动后立即落进 filter_meta，字段映射左侧也会同步出现
const onExtraColumnsChanged = () => {
  formData.value.filterMeta = buildFilterMeta()
}


// ===== 循环取数：循环 SQL 返回多行，每行跑一轮完整流程 =====
interface LoopConfigForm {
  enabled: boolean
  sql: string
  maxIterations: number
  intervalMs: number
}

const defaultLoopConfig = (): LoopConfigForm => ({
  enabled: false,
  sql: '',
  maxIterations: 200,
  intervalMs: 0
})

const loopConfig = ref<LoopConfigForm>(defaultLoopConfig())
const loopPreviewLoading = ref(false)
const loopPreview = ref<{ columns: string[]; rowCount: number; rows: Record<string, string>[] } | null>(
  null
)

const loopSqlPlaceholder = `WITH RECURSIVE weeks AS (
  SELECT DATE_SUB('2026-10-01', INTERVAL ((WEEKDAY('2026-10-01') + 3) % 7) DAY) AS startdate
  UNION ALL
  SELECT DATE_ADD(startdate, INTERVAL 7 DAY) FROM weeks
  WHERE DATE_ADD(startdate, INTERVAL 7 DAY) <= LAST_DAY('2026-10-01')
)
SELECT startdate, DATE_ADD(startdate, INTERVAL 6 DAY) AS enddate FROM weeks`

const onLoopEdited = () => {
  loopPreview.value = null
  applyFilterConditions()
}

const formatLoopRow = (row: Record<string, string>) =>
  Object.entries(row)
    .map(([k, v]) => `${k}=${v}`)
    .join('  ')

const previewLoop = async () => {
  if (!loopConfig.value.sql.trim()) {
    message.warning('请先填写循环数据集 SQL')
    return
  }
  if (!formData.value.targetDsId) {
    message.warning('请先选择目标数据源：循环 SQL 在目标库上执行')
    return
  }
  loopPreviewLoading.value = true
  try {
    loopPreview.value = await evaluateLoop({
      targetDsId: formData.value.targetDsId,
      sql: loopConfig.value.sql.trim(),
      limit: 5
    })
  } finally {
    loopPreviewLoading.value = false
  }
}

// ===== 增量同步（SYNC）配置：随流程存于 sync_config =====
const defaultSyncConfig = () => ({
  scopeField: '',
  scopeDays: 90,
  softDeleteColumn: 'is_deleted',
  softDeleteTimeColumn: 'delete_time',
  maxDeleteRatio: 0.5
})

const syncConfig = ref(defaultSyncConfig())

const syncSyncConfig = () => {
  formData.value.syncConfig = JSON.stringify(syncConfig.value)
}

const loadSyncConfig = () => {
  syncConfig.value = defaultSyncConfig()
  if (!formData.value.syncConfig) {
    return
  }
  try {
    const parsed = JSON.parse(formData.value.syncConfig)
    syncConfig.value = { ...syncConfig.value, ...parsed }
  } catch (e) {
    syncConfig.value = defaultSyncConfig()
  }
}

// ===== 目标表字段映射 =====
type MappingStatus = 'MATCH' | 'TYPE_DIFF' | 'MISSING_IN_TARGET' | 'MISSING_IN_SOURCE'

interface MappingRow {
  key: string
  sourceName?: string
  sourceType?: string
  targetName?: string
  targetType?: string
  status: MappingStatus
  statusText: string
  statusClass: string
}

const targetTableOptions = ref<{ value: string }[]>([])
const targetColumns = ref<TargetTableColumn[]>([])
const mappingLoading = ref(false)
let lastMappingTable = ''

const parseKeysLower = (keys?: string) =>
  (keys || '')
    .split(',')
    .map((k) => k.trim().toLowerCase())
    .filter(Boolean)

// 左侧：第 2 步抽取字段；增量同步模式自动补上软删除列
const sourceFields = computed(() => {
  const list = fieldRows.value
    .filter((r) => r.fieldKey.trim() && r.enabled)
    .map((r) => ({ name: r.fieldKey.trim(), type: r.type || 'VARCHAR(255)' }))
  if (formData.value.writeMode === 'SYNC') {
    const sd = (syncConfig.value.softDeleteColumn || 'is_deleted').trim()
    const sdt = (syncConfig.value.softDeleteTimeColumn || '').trim()
    if (sd && !list.some((i) => i.name.toLowerCase() === sd.toLowerCase())) {
      list.push({ name: sd, type: 'TINYINT' })
    }
    if (sdt && !list.some((i) => i.name.toLowerCase() === sdt.toLowerCase())) {
      list.push({ name: sdt, type: 'DATETIME' })
    }
  }
  // 附加列也要出现在映射左侧，才能通过「更新目标表」把列建到数仓
  for (const col of extraColumns.value) {
    const name = col.name.trim()
    if (!name || list.some((i) => i.name.toLowerCase() === name.toLowerCase())) continue
    list.push({ name, type: col.type || 'VARCHAR(255)' })
  }
  return list
})

const sourceFieldNames = computed(() => sourceFields.value.map((f) => f.name))

const normalizeType = (type: string) => (type || '').replace(/\s+/g, '').toLowerCase()

const mappingRows = computed<MappingRow[]>(() => {
  const sources = sourceFields.value
  const targets = targetColumns.value
  const targetMap = new Map(targets.map((c) => [c.name.toLowerCase(), c]))
  const rows: MappingRow[] = []
  const used = new Set<string>()

  for (const s of sources) {
    const key = s.name.toLowerCase()
    const t = targetMap.get(key)
    if (t) {
      used.add(key)
      const same = normalizeType(s.type) === normalizeType(t.columnType)
      rows.push({
        key: `s_${s.name}`,
        sourceName: s.name,
        sourceType: s.type,
        targetName: t.name,
        targetType: t.columnType,
        status: same ? 'MATCH' : 'TYPE_DIFF',
        statusText: same ? '已匹配' : '类型不一致',
        statusClass: same ? 'is-ok' : 'is-warn'
      })
    } else {
      rows.push({
        key: `s_${s.name}`,
        sourceName: s.name,
        sourceType: s.type,
        status: 'MISSING_IN_TARGET',
        statusText: '目标表缺列',
        statusClass: 'is-new'
      })
    }
  }

  for (const t of targets) {
    if (used.has(t.name.toLowerCase())) continue
    if (sources.some((s) => s.name.toLowerCase() === t.name.toLowerCase())) continue
    rows.push({
      key: `t_${t.name}`,
      targetName: t.name,
      targetType: t.columnType,
      status: 'MISSING_IN_SOURCE',
      statusText: '目标表多余',
      statusClass: 'is-extra'
    })
  }
  return rows
})

const mappingSummary = computed(() => {
  if (!formData.value.targetTable || !mappingRows.value.length) return ''
  if (!targetColumns.value.length) return '目标表不存在，可点「更新（新建）目标表」创建'
  const miss = mappingRows.value.filter((r) => r.status === 'MISSING_IN_TARGET').length
  const extra = mappingRows.value.filter((r) => r.status === 'MISSING_IN_SOURCE').length
  const diff = mappingRows.value.filter((r) => r.status === 'TYPE_DIFF').length
  const parts = [`共 ${mappingRows.value.length} 行`]
  if (miss) parts.push(`缺 ${miss} 列`)
  if (extra) parts.push(`多 ${extra} 列`)
  if (diff) parts.push(`类型不一致 ${diff}`)
  if (!miss && !extra && !diff) parts.push('结构与映射一致')
  return parts.join(' · ')
})

const filterTableOption = (input: string, option: { value: string }) =>
  option.value.toLowerCase().includes((input || '').toLowerCase())

const refreshMapping = async (force = false) => {
  if (!formData.value.targetDsId) {
    targetTableOptions.value = []
    targetColumns.value = []
    return
  }
  const table = (formData.value.targetTable || '').trim()
  if (!force && table === lastMappingTable) {
    return
  }
  mappingLoading.value = true
  try {
    const tables = await listTargetTables(formData.value.targetDsId)
    targetTableOptions.value = tables.map((t) => ({ value: t }))
    targetColumns.value = table
      ? await describeTargetTable(formData.value.targetDsId, table)
      : []
    lastMappingTable = table
  } catch (e) {
    targetColumns.value = []
  } finally {
    mappingLoading.value = false
  }
}

const onTargetDsChange = async () => {
  lastMappingTable = ''
  targetColumns.value = []
  await refreshMapping(true)
}

const onTargetTableChange = async () => {
  await refreshMapping()
}

// ===== 目标表结构变更确认弹窗 =====
interface ModifyColumnDraft {
  name: string
  currentType: string
  type: string
  comment?: string
  nullable: boolean
  autoIncrement: boolean
  checked: boolean
}

const schemaModal = ref({
  visible: false,
  submitting: false,
  tableExists: false,
  addColumns: [] as { name: string; type: string }[],
  modifyColumns: [] as ModifyColumnDraft[],
  dropCandidates: [] as string[],
  dropColumns: [] as string[],
  currentKeys: [] as string[],
  keyCandidates: [] as string[],
  primaryKeys: [] as string[],
  dropPrimaryKey: false
})

const openSchemaModal = () => {
  const add = mappingRows.value
    .filter((r) => r.status === 'MISSING_IN_TARGET' && r.sourceName)
    .map((r) => ({ name: r.sourceName as string, type: r.sourceType || 'VARCHAR(255)' }))
  // 目标表已有但类型与左侧不一致：默认全部勾选，按左侧类型改（自增列跳过，MODIFY 会丢自增属性）
  const modify: ModifyColumnDraft[] = mappingRows.value
    .filter((r) => r.status === 'TYPE_DIFF' && r.targetName)
    .map((r) => {
      const col = targetColumns.value.find(
        (c) => c.name.toLowerCase() === (r.targetName as string).toLowerCase()
      )
      return {
        name: r.targetName as string,
        currentType: r.targetType || '',
        type: r.sourceType || 'VARCHAR(255)',
        comment: col?.comment,
        nullable: col ? col.nullable : true,
        autoIncrement: !!col?.autoIncrement,
        checked: !col?.autoIncrement
      }
    })
  const drop = mappingRows.value
    .filter((r) => r.status === 'MISSING_IN_SOURCE' && r.targetName)
    .map((r) => r.targetName as string)
  const currentKeys = targetColumns.value.filter((c) => c.primaryKey).map((c) => c.name)
  const candidates = sourceFieldNames.value

  // 主键默认沿用现有主键；没有则按增量同步的比对主键预选
  let defaultKeys = currentKeys.filter((k) =>
    candidates.some((c) => c.toLowerCase() === k.toLowerCase())
  )
  if (!defaultKeys.length && formData.value.writeMode === 'SYNC') {
    const upsertKeys = parseKeysLower(formData.value.upsertKeys)
    defaultKeys = candidates.filter((c) => upsertKeys.includes(c.toLowerCase()))
  }

  schemaModal.value = {
    visible: true,
    submitting: false,
    tableExists: targetColumns.value.length > 0,
    addColumns: add,
    modifyColumns: modify,
    dropCandidates: drop,
    // 默认不勾选：以「改类型 / 补列」为主的操作不该顺手把目标表多余列删掉
    dropColumns: [],
    currentKeys,
    keyCandidates: candidates,
    primaryKeys: defaultKeys,
    dropPrimaryKey: false
  }
}

const sameKeySet = (current: string[], desired: string[]) => {
  if (current.length !== desired.length) return false
  const lower = current.map((k) => k.toLowerCase())
  return desired.every((k) => lower.includes(k.toLowerCase()))
}

// 弹窗顶部的变更摘要
const schemaPlanText = computed(() => {
  const m = schemaModal.value
  const parts: string[] = []
  if (m.addColumns.length) parts.push(`新增 ${m.addColumns.length} 列`)
  const mods = m.modifyColumns.filter((c) => c.checked && !c.autoIncrement).length
  if (mods) parts.push(`修改类型 ${mods} 列`)
  if (m.dropColumns.length) parts.push(`删除 ${m.dropColumns.length} 列`)
  const pkChanged =
    m.primaryKeys.length > 0 && !sameKeySet(m.currentKeys, m.primaryKeys)
  if (m.dropPrimaryKey && !m.primaryKeys.length) parts.push('移除主键')
  else if (pkChanged) parts.push(`主键改为 ${m.primaryKeys.join(' + ')}`)
  return parts.length ? `拟执行：${parts.join('、')}` : '未检测到结构差异：可改类型、勾选主键或手动增删列'
})

const submitSchema = async () => {
  if (!formData.value.targetDsId || !formData.value.targetTable) {
    message.warning('请先选择目标数据源与目标表')
    return
  }
  const mods = schemaModal.value.modifyColumns.filter((c) => c.checked && !c.autoIncrement)
  const pkChanged =
    schemaModal.value.primaryKeys.length > 0 &&
    !sameKeySet(schemaModal.value.currentKeys, schemaModal.value.primaryKeys)
  const dropPk = schemaModal.value.dropPrimaryKey && schemaModal.value.currentKeys.length > 0
  if (
    !schemaModal.value.addColumns.length &&
    !schemaModal.value.dropColumns.length &&
    !mods.length &&
    !pkChanged &&
    !dropPk
  ) {
    message.warning('没有需要变更的字段：新增/删除列、类型变更、主键都没有改动')
    return
  }
  schemaModal.value.submitting = true
  try {
    const res = await applyTargetSchema({
      datasourceId: formData.value.targetDsId,
      table: formData.value.targetTable.trim(),
      addColumns: schemaModal.value.addColumns,
      modifyColumns: mods.map((c) => ({
        name: c.name,
        type: c.type,
        comment: c.comment,
        // 主键列必须 NOT NULL，其余保持原样
        nullable: schemaModal.value.primaryKeys.some(
          (k) => k.toLowerCase() === c.name.toLowerCase()
        )
          ? false
          : c.nullable
      })),
      dropColumns: schemaModal.value.dropColumns,
      primaryKeys: schemaModal.value.primaryKeys,
      dropPrimaryKey: schemaModal.value.dropPrimaryKey
    })
    message.success(
      res.created ? `已新建目标表 ${res.table}` : `目标表已更新，执行 ${res.statements} 条语句`
    )
    schemaModal.value.visible = false
    lastMappingTable = ''
    await refreshMapping(true)
  } finally {
    schemaModal.value.submitting = false
  }
}
// table=结构化条件，text=直接编辑 FilterString
const filterMode = ref<'table' | 'text'>('table')

const needsFilterValue = (op: string) => op !== 'IS NULL' && op !== 'IS NOT NULL'

const quoteText = (text: string) => `'${text.replace(/'/g, "''")}'`

// 变量引用统一成 ${name}：用户填 startdate 或 ${startdate} 都接受
const variableRef = (raw: string) => {
  const name = (raw || '').trim().replace(/^\$\{/, '').replace(/\}$/, '').trim()
  return '${' + name + '}'
}

// 条件「变量」下拉候选：内置变量 + 已定义的变量
const BUILTIN_VARIABLES = ['today', 'yesterday', 'now']
const variableOptions = computed(() => {
  const defined = filterVariables.value
    .filter((v) => v.name.trim())
    .map((v) => v.name.trim())
  return [...new Set([...BUILTIN_VARIABLES, ...defined])].map((value) => ({ value }))
})

const renderFilterValue = (row: FilterCondition) => {
  const op = row.op.toUpperCase()
  if (op === 'IN' || op === 'NOT IN') {
    const items = row.value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    if (!items.length) return "('')"
    const rendered = items.map((item) => {
      if (row.valueType === 'NUMBER') return item
      if (row.valueType === 'VARIABLE') return quoteText(variableRef(item))
      return quoteText(item)
    })
    return `(${rendered.join(', ')})`
  }
  switch (row.valueType) {
    case 'NUMBER':
      return row.value.trim() || '0'
    case 'VARIABLE':
      return quoteText(variableRef(row.value))
    case 'RAW':
      return row.value.trim()
    default:
      return quoteText(row.value)
  }
}

const buildFilterString = () => {
  const usable = filterConditions.value.filter((row) => row.field.trim())
  if (!usable.length) return ''
  return usable
    .map((row, index) => {
      const base = `${row.field.trim()} ${row.op}`
      const expr = needsFilterValue(row.op) ? `${base} ${renderFilterValue(row)}` : base
      return index === 0 ? expr : `${row.connector} ${expr}`
    })
    .join(' ')
}

// filter_meta 统一序列化：过滤模式 / 取数方式 / 变量 / 循环 / 条件都在这里落库，
// 任何一处改动都走同一个函数，避免某个分支把别的配置写丢
const buildFilterMeta = () =>
  JSON.stringify({
    mode: filterMode.value,
    bodyMode: bodyModeSel.value,
    variables: filterVariables.value
      .map((v) => ({ name: v.name.trim(), sql: v.sql.trim() }))
      .filter((v) => v.name && v.sql),
    // 附加列：落库时补进每一行的额外字段（变量 / 常量）
    extraColumns: extraColumns.value
      .map((c) => ({
        name: c.name.trim(),
        valueType: c.valueType,
        value: c.value.trim(),
        type: c.type || 'VARCHAR(255)'
      }))
      .filter((c) => c.name),
    loop: loopConfig.value.enabled
      ? {
          enabled: true,
          sql: loopConfig.value.sql.trim(),
          maxIterations: loopConfig.value.maxIterations,
          intervalMs: loopConfig.value.intervalMs
        }
      : { enabled: false },
    conditions: filterConditions.value
  })

// 结构化条件 -> FilterString（并同步请求体与持久化配置）
const applyFilterConditions = () => {
  kingdeeQuery.value.filterString = buildFilterString()
  formData.value.filterMeta = buildFilterMeta()
  syncKingdeeQueryToBody()
}

const addVariable = () => {
  filterVariables.value.push(defaultFilterVariable())
}

const removeVariable = (index: number) => {
  filterVariables.value.splice(index, 1)
  applyFilterConditions()
}

// 编辑变量后清掉上一次的试算结果，避免看着旧值误判
const onVariableEdited = (v: FilterVariable) => {
  v.result = undefined
  v.resultError = false
  v.elapsedMs = undefined
  applyFilterConditions()
}

// 变量 SQL 试算：在目标数据源上执行一次，立即返回取值
const testVariable = async (v: FilterVariable) => {
  if (!v.sql.trim()) {
    message.warning('请先填写 SQL 表达式')
    return
  }
  if (!formData.value.targetDsId) {
    message.warning('请先选择目标数据源：变量 SQL 在目标库上执行')
    return
  }
  v.testing = true
  v.result = undefined
  v.resultError = false
  try {
    const res = await evaluateVariable({
      targetDsId: formData.value.targetDsId,
      name: v.name.trim() || undefined,
      sql: v.sql.trim()
    })
    v.result = res.value
    v.elapsedMs = res.elapsedMs
    v.resultError = false
  } catch (e: any) {
    v.result = e?.message || '执行失败'
    v.resultError = true
  } finally {
    v.testing = false
  }
}

const addFilterCondition = () => {
  filterConditions.value.push(defaultFilterCondition())
  applyFilterConditions()
}

const removeFilterCondition = (index: number) => {
  filterConditions.value.splice(index, 1)
  applyFilterConditions()
}

// 尽量把已有的 FilterString 解析回结构化条件；解析不了就返回 null
const parseFilterString = (raw: string): FilterCondition[] | null => {
  const text = (raw || '').trim()
  if (!text) return []
  const tokens = text.split(/\s+(AND|OR)\s+/i)
  if (tokens.length % 2 === 0) return null

  const rows: FilterCondition[] = []
  for (let i = 0; i < tokens.length; i += 2) {
    const expr = tokens[i].trim()
    if (!expr) return null
    const connector = i === 0 ? 'AND' : (tokens[i - 1].toUpperCase() as 'AND' | 'OR')
    const match = expr.match(
      /^([A-Za-z_][A-Za-z0-9_.]*)\s*(NOT\s+LIKE|NOT\s+IN|>=|<=|<>|!=|=|>|<|LIKE|IN|IS\s+NOT\s+NULL|IS\s+NULL)\s*(.*)$/i
    )
    if (!match) return null
    const field = match[1]
    const op = match[2].toUpperCase().replace(/\s+/g, ' ')
    const rawValue = (match[3] || '').trim()

    const row = defaultFilterCondition()
    row.connector = connector
    row.field = field
    row.op = FILTER_OPS.includes(op) ? op : '='

    if (op === 'IS NULL' || op === 'IS NOT NULL') {
      rows.push(row)
      continue
    }
    if (op === 'IN' || op === 'NOT IN') {
      row.valueType = 'RAW'
      row.value = rawValue
      rows.push(row)
      continue
    }
    if (/^'.*'$/.test(rawValue)) {
      const inner = rawValue.slice(1, -1).replace(/''/g, "'")
      const varMatch = inner.match(/^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/)
      if (varMatch) {
        row.valueType = 'VARIABLE'
        row.value = varMatch[1]
      } else {
        row.valueType = 'TEXT'
        row.value = inner
      }
    } else if (/^-?\d+(\.\d+)?$/.test(rawValue)) {
      row.valueType = 'NUMBER'
      row.value = rawValue
    } else {
      row.valueType = 'RAW'
      row.value = rawValue
    }
    rows.push(row)
  }
  return rows
}

const loadFilter = (preserveMode = false) => {
  if (!preserveMode) {
    filterMode.value = 'table'
  }
  let conditions: FilterCondition[] | null = null
  filterVariables.value = []
  extraColumns.value = []
  loopConfig.value = defaultLoopConfig()
  loopPreview.value = null
  bodyModeSel.value = 'AUTO'
  if (formData.value.filterMeta) {
    try {
      const parsed = JSON.parse(formData.value.filterMeta)
      if (parsed?.mode === 'text') {
        filterMode.value = 'text'
      }
      // 附加列：老流程没有该字段时为空
      if (Array.isArray(parsed?.extraColumns)) {
        extraColumns.value = parsed.extraColumns
          .filter((c: any) => c && String(c.name ?? '').trim())
          .map((c: any) => ({
            rid: `ec_${++extraRidSeed}`,
            name: String(c.name ?? '').trim(),
            valueType: c.valueType === 'CONSTANT' ? 'CONSTANT' : 'VARIABLE',
            value: String(c.value ?? ''),
            type: String(c.type ?? 'VARCHAR(255)')
          }))
      }
      // 取数方式：老流程没有该字段时按「自动识别」处理
      if (['AUTO', 'STANDARD', 'REPORT'].includes(parsed?.bodyMode)) {
        bodyModeSel.value = parsed.bodyMode
      }
      if (parsed?.loop) {
        loopConfig.value = {
          ...defaultLoopConfig(),
          enabled: !!parsed.loop.enabled,
          sql: String(parsed.loop.sql ?? ''),
          maxIterations: Number(parsed.loop.maxIterations) || 200,
          intervalMs: Number(parsed.loop.intervalMs) || 0
        }
      }
      if (Array.isArray(parsed?.variables)) {
        filterVariables.value = parsed.variables
          .filter((item: any) => item && (item.name || item.sql))
          .map((item: any) => ({
            rid: `fv_${++filterRidSeed}`,
            name: String(item.name ?? ''),
            sql: String(item.sql ?? '')
          }))
      }
      if (Array.isArray(parsed?.conditions)) {
        conditions = parsed.conditions.map((item: any) => ({
          ...defaultFilterCondition(),
          connector: item?.connector === 'OR' ? 'OR' : 'AND',
          field: String(item?.field ?? ''),
          op: typeof item?.op === 'string' ? item.op : '=',
          valueType: (['TEXT', 'NUMBER', 'VARIABLE', 'RAW'].includes(item?.valueType)
            ? item.valueType
            : 'TEXT') as FilterValueType,
          value: String(item?.value ?? '')
        }))
      }
    } catch (e) {
      conditions = null
    }
  }
  if (!conditions) {
    // 老流程只有 FilterString，尽量解析回来
    conditions = parseFilterString(kingdeeQuery.value.filterString) || []
  }
  filterConditions.value = conditions
}

const switchFilterMode = () => {
  if (filterMode.value === 'table') {
    // 从文本切回表格：先尝试解析用户手写的内容
    const parsed = parseFilterString(kingdeeQuery.value.filterString)
    if (parsed === null) {
      message.warning('当前过滤条件无法解析为结构化条件，已保留原有条件，编辑表格会覆盖文本内容')
    } else {
      filterConditions.value = parsed
      applyFilterConditions()
    }
  }
  filterMode.value = filterMode.value === 'table' ? 'text' : 'table'
  // 只改模式：变量、循环、取数方式等其余配置必须原样保留
  formData.value.filterMeta = buildFilterMeta()
}

// 字段名候选（来自已拉取的字段清单），用于过滤条件输入提示
const filterFieldOptions = computed(() =>
  fieldRows.value
    .filter((row) => row.fieldKey.trim())
    .map((row) => ({ value: row.fieldKey.trim() }))
)

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
// 报表方案：试取 1 行推断字段
const reportFieldsLoading = ref(false)
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

// 转换 SQL 可为空：留空时后端按目标表结构自动生成直通入库脚本
const isDraft = computed(() => {
  return !formData.value.targetDsId || !formData.value.targetTable
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
  // 由向导字段重建报文，视为系统生成，保存时不再保留手工原文
  bodyManual.value = false
  if (bodyMode.value === 'REPORT') {
    // 报表方案查询：过滤条件走 QuicklyCondition 数组，没有 FilterString / OrderString
    formData.value.apiBody = JSON.stringify(
      {
        parameters: [
          {
            FORMID: kingdeeQuery.value.formId || '',
            FSCHEMEID: schemeId.value || '',
            QuicklyCondition: quickConditions.value
              .filter((c) => c.fieldName.trim())
              .map((c) => ({ FieldName: c.fieldName.trim(), FieldValue: c.fieldValue })),
            StartRow: 0,
            Limit: kingdeeQuery.value.limit || 2000,
            FieldKeys: kingdeeQuery.value.fieldKeys || ''
          }
        ]
      },
      null,
      2
    )
  } else {
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

  // 每页条数与「单次抓取上限」联动，避免两处配置打架
  const limit = kingdeeQuery.value.limit || 2000
  if (pagination.value.pageSize !== limit) {
    pagination.value.pageSize = limit
    if (pagination.value.enabled) {
      syncPagination()
    }
  }

  // 数据路径与分页路径随报文体切换：
  // 报表返回 {"success":true,"data":[..],"TotalCount":n}，数据在 $.data；
  // 标准查询返回裸二维数组，用 $.* 逐行取。路径写错会把整包响应当成一行解析。
  if (bodyMode.value === 'REPORT') {
    if (!formData.value.apiDataPath || formData.value.apiDataPath === '$.*') {
      formData.value.apiDataPath = '$.data'
    }
    pagination.value.offsetPath = 'parameters[0].StartRow'
    pagination.value.limitPath = 'parameters[0].Limit'
  } else {
    if (formData.value.apiDataPath === '$.data') {
      formData.value.apiDataPath = '$.*'
    }
    pagination.value.offsetPath = 'data.StartRow'
    pagination.value.limitPath = 'data.Limit'
  }
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
  // 已确认是报表：QueryBusinessInfo 本来就没有列定义，直接说明原因，别让用户等一次必然失败的请求
  if (
    bodyMode.value === 'REPORT' &&
    detectResult.value?.type === 'REPORT' &&
    detectResult.value.formId === kingdeeQuery.value.formId
  ) {
    kingdeeFields.value = []
    kingdeeFieldModalVisible.value = true
    kingdeeFieldsLoading.value = false
    kingdeeFieldError.value =
      '该表单是报表类（金蝶未返回单据字段定义），无法通过 QueryBusinessInfo 拉取列；请直接在「抽取字段列表」手工填写，或用 Excel 导入。'
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

/**
 * 报表方案：试取 1 行推断字段。
 *
 * <p>报表没有列定义接口，只能靠 GetReportData 返回的首行反推 key。
 * 方案查不出数据时明确告知，而不是让用户以为功能坏了。
 */
const handleDetectReportFields = async () => {
  const formId = kingdeeQuery.value.formId?.trim()
  if (!formId) {
    message.warning('请先填写报表 FORMID')
    return
  }
  if (!schemeId.value?.trim()) {
    message.warning('请先填写过滤方案 ID (FSCHEMEID)')
    return
  }
  if (!kingdeeConfig.value.loginUrl) {
    message.warning('请先在上方填写金蝶登录接口地址')
    return
  }
  reportFieldsLoading.value = true
  try {
    const res = await detectKingdeeReportFields({
      loginUrl: kingdeeConfig.value.loginUrl,
      acctID: kingdeeConfig.value.acctID,
      userName: kingdeeConfig.value.userName,
      password: effectivePassword() || undefined,
      lcid: kingdeeConfig.value.lcid,
      formId,
      schemeId: schemeId.value.trim(),
      reportUrl: formData.value.apiUrl?.trim() || undefined
    })
    if (!res?.fields?.length) {
      message.warning(res?.message || '未能从报表结果推断出字段')
      return
    }
    const existed = new Set(fieldRows.value.map((r) => r.fieldKey.trim().toUpperCase()))
    const rows = mergeRows(
      res.fields.map((f) => f.key),
      fieldRows.value
    )
    res.fields.forEach((f) => {
      if (existed.has((f.key || '').toUpperCase())) return
      const row = rows.find((r) => r.fieldKey.trim().toUpperCase() === f.key.toUpperCase())
      if (!row) return
      row.label = f.label && f.label !== f.key ? f.label : ''
      row.type = f.type || row.type
    })
    fieldRows.value = rows
    kingdeeQuery.value.fieldKeys = joinFieldKeys(rows)
    syncKingdeeQueryToBody()
    message.success(
      `已按报表首行推断出 ${res.fields.length} 个字段（报表返回中没有中文名，可用 Excel 补全）`
    )
  } catch (e: any) {
    message.error('报表字段推断失败：' + (e?.message || '未知异常'))
  } finally {
    reportFieldsLoading.value = false
  }
}

const toggleAllKingdeeFields = (checked: boolean) => {
  kingdeeFields.value.forEach((f) => {
    f.checked = checked
  })
}

// 没有明确指定过类型的行：空值，或仍是历史/新建时的默认值（TEXT、VARCHAR(255)）
const isTypeUnset = (type?: string) => {
  const t = String(type || '').toUpperCase()
  return !t || t === 'TEXT' || t === 'VARCHAR(255)'
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
      createRow({ fieldKey: f.key, label: f.label || '', type: f.type || 'VARCHAR(255)', enabled: true })
    )
  } else {
    const existing = new Set(fieldRows.value.map((r) => r.fieldKey.trim().toUpperCase()))
    picked.forEach((f) => {
      const upper = f.key.trim().toUpperCase()
      if (existing.has(upper)) {
        const row = fieldRows.value.find((r) => r.fieldKey.trim().toUpperCase() === upper)
        if (row) {
          if (!row.label) row.label = f.label || ''
          if (isTypeUnset(row.type)) row.type = f.type || row.type
        }
        return
      }
      fieldRows.value.push(
        createRow({ fieldKey: f.key, label: f.label || '', type: f.type || 'VARCHAR(255)', enabled: true })
      )
      existing.add(upper)
    })
  }
  applyFieldRows()
  kingdeeFieldModalVisible.value = false
  message.success(`已${mode === 'replace' ? '覆盖' : '追加'} ${picked.length} 个字段`)
}

// 只填充还没有明确类型的行（空 / 默认 TEXT / 默认 VARCHAR(255)），避免覆盖手工设置
const handleInferTypes = () => {
  if (!fieldRows.value.length) {
    message.warning('请先添加字段')
    return
  }
  let changed = 0
  fieldRows.value.forEach((row) => {
    if (!row.fieldKey.trim()) return
    if (isTypeUnset(row.type)) {
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

// 判断报文属于哪种取数方式：标准查询 / 报表方案 / 无法识别
// 识别不出来的报文（例如手工拼的报文）绝不会被向导字段覆盖，保存时按原文提交
const detectBodyShape = (text: string): BodyShape => {
  if (!text || !text.trim()) return 'UNKNOWN'
  let parsed: any
  try {
    parsed = JSON.parse(text)
  } catch (e) {
    return 'UNKNOWN'
  }
  const root = Array.isArray(parsed) ? parsed[0] : parsed
  if (!root || typeof root !== 'object') return 'UNKNOWN'

  // 报表方案：{"parameters":[{FORMID,...}]}，也兼容 parameters 为单个对象或直接给一条参数
  const paramItem = Array.isArray(root.parameters)
    ? root.parameters[0]
    : root.parameters && typeof root.parameters === 'object'
      ? root.parameters
      : null
  if (paramItem && (paramItem.FORMID || paramItem.FSCHEMEID || paramItem.QuicklyCondition)) {
    return 'REPORT'
  }
  if (root.FORMID || root.FSCHEMEID || root.QuicklyCondition) return 'REPORT'

  // 标准查询：{"data":{FormId,...}} 或根节点就是 {FormId,...}
  const d = root.data && typeof root.data === 'object' ? root.data : root
  if (d.FormId || d.FieldKeys || d.FilterString !== undefined) return 'STANDARD'

  return 'UNKNOWN'
}

// 当前请求体的结构（用于界面提示）
const bodyShape = computed<BodyShape | ''>(() =>
  formData.value.apiBody?.trim() ? detectBodyShape(formData.value.apiBody) : ''
)

// 用户直接编辑请求体：以文本为准，打上手工标记
const onBodyEdited = () => {
  bodyManual.value = true
  syncBodyToKingdeeQuery()
}

// 从 Body 反向解析金蝶单据参数，返回识别到的报文结构
const syncBodyToKingdeeQuery = (): BodyShape => {
  if (!formData.value.apiBody?.trim()) return 'UNKNOWN'
  let parsed: any
  try {
    parsed = JSON.parse(formData.value.apiBody)
  } catch (e) {
    return 'UNKNOWN'
  }
  const root = Array.isArray(parsed) ? parsed[0] : parsed
  if (!root || typeof root !== 'object') return 'UNKNOWN'

  const paramItem = Array.isArray(root.parameters)
    ? root.parameters[0]
    : root.parameters && typeof root.parameters === 'object'
      ? root.parameters
      : null
  const p = paramItem || (root.FORMID || root.FSCHEMEID || root.QuicklyCondition ? root : null)

  if (p) {
    bodyMode.value = 'REPORT'
    if (p.FORMID) kingdeeQuery.value.formId = p.FORMID
    if (p.FSCHEMEID !== undefined) schemeId.value = p.FSCHEMEID
    if (p.FieldKeys) kingdeeQuery.value.fieldKeys = p.FieldKeys
    if (p.Limit !== undefined) kingdeeQuery.value.limit = p.Limit
    const conditions = Array.isArray(p.QuicklyCondition) ? p.QuicklyCondition : []
    quickConditions.value = conditions.map((c: any) => ({
      rid: `qc_${++quickRidSeed}`,
      fieldName: String(c?.FieldName ?? ''),
      fieldValue: String(c?.FieldValue ?? '')
    }))
    rebuildFieldRows()
    loadFilter(true)
    loadSyncConfig()
    return 'REPORT'
  }

  const d = root.data && typeof root.data === 'object' ? root.data : root
  if (d.FormId || d.FieldKeys || d.FilterString !== undefined) {
    bodyMode.value = 'STANDARD'
    if (d.FormId) kingdeeQuery.value.formId = d.FormId
    if (d.FieldKeys) kingdeeQuery.value.fieldKeys = d.FieldKeys
    if (d.FilterString !== undefined) kingdeeQuery.value.filterString = d.FilterString
    if (d.OrderString !== undefined) kingdeeQuery.value.orderString = d.OrderString
    if (d.Limit !== undefined) kingdeeQuery.value.limit = d.Limit
    rebuildFieldRows()
    loadFilter(true)
    loadSyncConfig()
    return 'STANDARD'
  }

  return 'UNKNOWN'
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
  loadFilter()
  loadSyncConfig()
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

    // 已保存的报文若无法识别结构（用户自定义报文），标记为手工内容，
    // 之后保存时按原文提交，不能被向导字段重置
    bodyManual.value = false
    bodySaveWarning.value = ''
    detectResult.value = null
    detectError.value = ''
    if (record.apiBody) {
      const shape = syncBodyToKingdeeQuery()
      if (shape === 'UNKNOWN' && record.apiBody.trim().startsWith('{')) {
        bodyManual.value = true
      }
    } else {
      fieldRows.value = []
    }
    // 中文名/类型/备注来自 field_meta（启用状态仍以 FieldKeys 为准）
    if (record.fieldMeta) {
      fieldRows.value = fieldRowsFromMeta(record.fieldMeta, kingdeeQuery.value.fieldKeys)
    }
    loadPagination()
    loadFilter()
    loadSyncConfig()
    // 手动钉死的取数方式优先于报文里体现的模式
    if (bodyModeSel.value !== 'AUTO') {
      bodyMode.value = bodyModeSel.value
    }
  } else {
    isEdit.value = false
    currentId.value = ''
    formData.value = {
      ...initialFormData(),
      folderId: defaultFolderId || undefined
    }
    kingdeeQuery.value = defaultKingdeeQuery()
    pagination.value = defaultPagination()
    advancedActiveKeys.value = ['pagination']
    // 新建流程回到默认口径，避免沿用上一条流程的取数方式
    bodyMode.value = 'STANDARD'
    bodyModeSel.value = 'AUTO'
    detectResult.value = null
    detectError.value = ''
    syncKingdeeLoginBody()
    syncKingdeeQueryToBody()
    rebuildFieldRows()
    loadFilter()
    loadSyncConfig()
    // 新建流程时自动套用已保存凭证，省去每次手填
    await loadCredentialSilently()
  }

  // 按目标数据源刷新表清单与表结构（用于字段映射）
  lastMappingTable = ''
  refreshMapping(true).catch(() => {})
}

const handleBack = () => {
  emit('back')
}

const handleReset = () => {
  if (!isEdit.value) {
    formData.value = initialFormData()
    kingdeeQuery.value = defaultKingdeeQuery()
    pagination.value = defaultPagination()
    advancedActiveKeys.value = ['pagination']
    bodyMode.value = 'STANDARD'
    bodyModeSel.value = 'AUTO'
    detectResult.value = null
    detectError.value = ''
    syncKingdeeLoginBody()
    syncKingdeeQueryToBody()
    rebuildFieldRows()
    loadFilter()
    loadSyncConfig()
  }
}

const prepareDataBeforeSave = () => {
  bodySaveWarning.value = ''
  if (formData.value.sourceType === 'WEB_API') {
    // 服务地址的装配名分隔符纠正为逗号，避免保存后运行时报 "no found"
    formData.value.apiUrl = normalizeK3Url(formData.value.apiUrl || '')
    if (formData.value.apiAuthType === 'KINGDEE_K3') {
      syncKingdeeLoginBody()
      // 请求体优先用户手改的原文：结构识别不出来时绝不回写向导字段，
      // 否则会把粘贴进来的第二种报文（parameters 等）覆盖回旧的 data 报文
      const rawBody = formData.value.apiBody?.trim() || ''
      if (bodyManual.value && rawBody) {
        const shape = detectBodyShape(rawBody)
        if (shape === 'UNKNOWN') {
          bodySaveWarning.value =
            '请求体不是可识别的金蝶报文结构，已按原文保存；上方 FormId / 字段未写入该报文'
        }
      } else {
        syncKingdeeQueryToBody()
      }
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

  submitting.value = true
  try {
    // 放进 try：整理数据时抛错也能给出提示，而不是"点了没反应"
    prepareDataBeforeSave()
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
    if (bodySaveWarning.value) {
      message.warning(bodySaveWarning.value)
    } else if (!silent) {
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
  const isIncomplete = !formData.value.targetDsId || !formData.value.targetTable
  if (isIncomplete) {
    const saved = await handleSaveStep(true)
    if (saved) {
      message.warning('当前流程已暂存为【草稿】！因尚未配置目标数据源或目标表，暂无法启用调度与运行')
      emit('success')
    }
    return
  }

  submitting.value = true
  try {
    prepareDataBeforeSave()
    const payload: Partial<EtlPipeline> = {
      ...formData.value,
      id: currentId.value ? currentId.value : undefined
    }
    await saveStepPipeline(payload)
    if (bodySaveWarning.value) {
      message.warning(bodySaveWarning.value)
    } else {
      message.success('ETL 流程全部配置已成功保存！')
    }
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

/* 请求体手工编辑 / 结构无法识别时的提示 */
.body-warn {
  margin-top: 6px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #ad4e00;
  background: #fff7e6;
  border: 1px solid #ffd591;
  border-radius: 4px;
}

.body-note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #1677ff;
}

/* 取数方式：自动识别状态行 */
.detect-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
  min-height: 22px;
  font-size: 12px;
}
.detect-tag {
  margin: 0;
}

.detect-err {
  color: #cf1322;
}

.detect-reason {
  color: #8c8c8c;
  line-height: 1.6;
}

/* 附加字段配置区 */
.extra-box {
  margin-top: 12px;
  padding: 10px 12px 12px;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  background: #fafafa;
}

.extra-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.extra-title {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
}

.extra-hint {
  flex: 1;
  min-width: 200px;
  font-size: 12px;
  color: #8c8c8c;
}

.extra-box .filt-row + .filt-row {
  margin-top: 6px;
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

.adv-collapse {
  background: #fafafa;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  margin-top: 4px;
  margin-bottom: 24px;
  overflow: hidden;
}

.adv-collapse :deep(.ant-collapse-header) {
  padding: 10px 14px !important;
  align-items: center !important;
}

.adv-collapse :deep(.ant-collapse-content-box) {
  padding: 4px 14px 8px !important;
  background: #fff;
}

.adv-title {
  font-size: 13px;
  font-weight: 500;
  color: #262626;
}

.adv-tag {
  margin-left: 8px;
  margin-right: 0;
}

.adv-sub {
  margin-left: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.filt-panel {
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 10px 12px;
  margin: -8px 0 16px;
}

.var-box {
  background: #fff;
  border: 1px dashed #cfe3f7;
  border-radius: 6px;
  padding: 8px 10px 4px;
  margin-bottom: 10px;
}

.var-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.var-title {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: #185fa5;
}

.var-hint {
  flex: 1;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.7;
}

.var-hint code {
  color: #185fa5;
  background: #e6f1fb;
  padding: 0 4px;
  border-radius: 3px;
  font-family: Consolas, Monaco, monospace;
}

.var-item {
  margin-bottom: 6px;
}

.var-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.var-result {
  margin: 2px 0 0 168px;
  font-size: 12px;
  line-height: 1.7;
  color: #237804;
  word-break: break-all;
}

.var-result b {
  font-family: Consolas, Monaco, monospace;
  font-weight: 600;
}

.var-result-err {
  color: #cf1322;
}

.var-result-time {
  color: #bfbfbf;
}

.var-eq {
  flex: none;
  line-height: 24px;
  color: #bfbfbf;
}

.var-sql :deep(textarea) {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.var-empty {
  padding: 2px 2px 6px;
  font-size: 12px;
  color: #bfbfbf;
}

.loop-box {
  margin: 4px 0 16px;
  padding: 10px 12px 4px;
  background: #fff;
  border: 1px dashed #e8d5b7;
  border-radius: 6px;
}

.loop-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.loop-title {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  color: #a8681b;
}

.loop-hint {
  flex: 1;
  font-size: 12px;
  color: #8c8c8c;
}

.loop-preview {
  margin-top: 8px;
  padding: 8px 10px;
  background: #fafafa;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #595959;
}

.loop-preview-head {
  margin-bottom: 4px;
  color: #262626;
}

.loop-preview-row {
  line-height: 1.8;
  word-break: break-all;
}

.loop-preview-more {
  color: #bfbfbf;
}

.sync-box {
  background: #fff;
  border: 1px dashed #cfe3f7;
  border-radius: 6px;
  padding: 10px 12px 2px;
  margin-top: 4px;
}

.sync-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #185fa5;
}

.map-box {
  margin-top: 12px;
  padding: 10px 12px;
  background: #fff;
  border: 1px dashed #cfe3f7;
  border-radius: 6px;
}

.map-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.map-title {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  color: #185fa5;
}

.map-sub {
  flex: 1;
  font-size: 12px;
  color: #8c8c8c;
}

.map-empty {
  padding: 12px 4px;
  font-size: 12px;
  color: #bfbfbf;
}

.map-table {
  overflow: hidden;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
}

.map-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 40px 1.4fr 1fr 110px;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  font-size: 12px;
  border-bottom: 1px solid #f5f5f5;
}

.map-row:last-child {
  border-bottom: none;
}

.map-row-head {
  background: #fafafa;
  font-weight: 600;
  color: #595959;
}

.map-name,
.map-type {
  overflow: hidden;
  font-family: Consolas, Monaco, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-name {
  color: #262626;
}

.map-type {
  color: #8c8c8c;
}

.map-arrow {
  color: #bfbfbf;
  text-align: center;
}

.map-status {
  font-size: 12px;
  text-align: right;
}

.map-status.is-ok {
  color: #237804;
}

.map-status.is-warn {
  color: #d46b08;
}

.map-status.is-new {
  color: #185fa5;
}

.map-status.is-extra {
  color: #cf1322;
}

.schema-section {
  margin-bottom: 16px;
}

.schema-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #262626;
}

.schema-empty {
  font-size: 12px;
  color: #bfbfbf;
}

.schema-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.schema-name {
  width: 240px;
  overflow: hidden;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 类型变更行：当前类型 → 目标类型 */
.schema-type-old {
  width: 130px;
  color: #8c8c8c;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  text-align: right;
}

.schema-arrow {
  color: #bfbfbf;
  font-size: 12px;
}

.filt-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.filt-where {
  width: 78px;
  flex: none;
  padding-left: 4px;
  font-size: 12px;
  color: #8c8c8c;
  font-family: Consolas, Monaco, monospace;
}

.filt-empty {
  font-size: 12px;
  color: #bfbfbf;
  padding: 6px 2px;
}

.filt-preview {
  margin-top: 6px;
  padding-top: 8px;
  border-top: 1px dashed #e8e8e8;
  font-size: 12px;
  color: #8c8c8c;
  word-break: break-all;
  line-height: 1.9;
}

.filt-preview code {
  color: #185fa5;
  background: #e6f1fb;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
}
</style>
