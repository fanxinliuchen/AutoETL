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
                      <a-radio-group v-model:value="formData.sourceType" button-style="solid" @change="onSourceTypeChange">
                        <a-radio-button value="MYSQL">数据库</a-radio-button>
                        <a-radio-button value="WEB_API">API接口</a-radio-button>
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
                  <a-form-item label="来源数据库（MySQL / PostgreSQL）" name="sourceDsId" required>
                    <a-select v-model:value="formData.sourceDsId" placeholder="选择抽取来源库" allow-clear>
                      <a-select-option v-for="ds in jdbcSourceList" :key="ds.id" :value="ds.id">
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

                  <!-- 变量定义：抽取 SQL 里用 ${名称} 引用；变量 SQL 在目标数据源上执行 -->
                  <div class="var-box" style="margin-bottom: 16px">
                    <div class="var-head">
                      <span class="var-title">变量定义</span>
                      <span class="var-hint">
                        SQL 里用 <code>${名称}</code> 引用，执行时在目标数据源上以 SQL 计算；内置
                        <code>${today}</code> <code>${yesterday}</code> <code>${now}</code>
                      </span>
                      <a-button size="small" type="link" @click="addMysqlVariable">
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
                          @change="onMysqlVariableEdited(v)"
                        />
                        <span class="var-eq">=</span>
                        <a-textarea
                          v-model:value="v.sql"
                          size="small"
                          class="fk-input-mono var-sql"
                          :auto-size="{ minRows: 1, maxRows: 3 }"
                          placeholder="SELECT 表达式，如 DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '%Y-%m-%d')"
                          style="flex: 1; min-width: 260px"
                          @change="onMysqlVariableEdited(v)"
                        />
                        <a-tooltip title="在目标数据源上执行一次，立即看到取值">
                          <a-button size="small" :loading="v.testing" @click="testVariable(v)">
                            试算
                          </a-button>
                        </a-tooltip>
                        <a-button size="small" type="link" danger @click="removeMysqlVariable(vi)">
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
                      未定义变量。抽取 SQL 里需要动态日期（如「昨天」）时在此配置，例如 statDate =
                      SELECT DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '%Y-%m-%d')
                    </div>
                  </div>

                  <!-- 抽取字段：解析 SQL 结果列，供第 4 步映射、建目标表、临时表类型使用 -->
                  <a-form-item>
                    <template #label>
                      <span class="field-list-label">
                        抽取字段清单
                        <a-button
                          size="small"
                          type="primary"
                          ghost
                          :loading="sourceColumnsLoading"
                          @click="parseSourceColumns"
                        >
                          <thunderbolt-outlined /> 解析 SQL 字段
                        </a-button>
                        <a-button v-if="fieldRows.length" size="small" type="link" @click="clearSourceFields">
                          清空字段
                        </a-button>
                      </span>
                    </template>

                    <ApiFieldList
                      v-if="fieldRows.length"
                      :rows="fieldRows"
                      :columns="mysqlFieldColumns"
                      :type-options="fieldTypeSelectOptions"
                      :enabled-count="enabledFieldCount"
                      :text-mode="textMode"
                      :field-keys-text="''"
                      key-placeholder="抽取结果列名（别名生效，可为中文）"
                      hint-text=""
                      :show-kingdee-tools="false"
                      :show-report-tools="false"
                      :kingdee-loading="false"
                      :field-check-loading="false"
                      :report-fields-loading="false"
                      :column-name-hint="columnNameHint"
                      @update:text-mode="textMode = $event"
                      @row-changed="onFieldRowChanged"
                      @add="addFieldRow"
                      @remove="removeFieldRow"
                      @move="moveFieldRow"
                      @import-excel="triggerExcelImport"
                      @export-excel="handleExportExcel"
                      @download-template="handleDownloadTemplate"
                      @open-paste="pasteModalVisible = true"
                      @infer-types="handleInferTypes"
                      @enable-all="toggleAllFieldEnabled"
                    />

                    <input
                      ref="fileInputRef"
                      type="file"
                      accept=".xlsx,.xls"
                      style="display: none"
                      @change="onExcelFileChange"
                    />
                  </a-form-item>
                </a-card>
              </div>

              <!-- WebAPI 源模式 (金蝶云星空 / 复杂两步会话鉴权) -->
              <div v-else>

                <!-- 源连接（连接注册中心）：金蝶/云表选连接，接口地址与凭据由连接统一维护 -->
                <a-card
                  v-if="formData.sourceType === 'WEB_API'"
                  :bordered="false"
                  class="pane-card"
                  style="margin-bottom: 16px"
                >
                  <a-form-item label="源连接（连接注册中心）">
                    <a-select
                      v-model:value="sourceConnValue"
                      allow-clear
                      placeholder="选择连接（可选）"
                      :options="apiConnectionOptions"
                    />
                  </a-form-item>
                </a-card>

                <!-- 鉴权模式切换选择：选了源连接就不再需要（连接已决定怎么鉴权） -->
                <a-card v-if="!selectedApiConn" :bordered="false" class="pane-card" style="margin-bottom: 16px">
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

                <!-- 云表模板：选了云表连接就走这套，不看 api_auth_type（那是金蝶遗留字段） -->
                <div v-if="isYunbiaoConn">
                  <a-card
                    title="📥 云表数据抽取配置 (formJson)"
                    :bordered="false"
                    class="pane-card module-card"
                  >
                    <template #extra>
                      <a-space>
                        <a-button size="small" @click="applyYunbiaoTemplate">📋 载入默认模板</a-button>
                        <a-button size="small" @click="saveYunbiaoTemplate">💾 保存成默认模板</a-button>
                        <a-button size="small" @click="formatJsonBody">🪄 格式化 JSON</a-button>
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

                    <a-form-item label="表单名称" name="apiUrl" required>
                      <a-input
                        v-model:value="yunbiaoFormName"
                        placeholder="如 表结合数采明细_IOT（只填表单名，接口地址由系统组装）"
                      />
                    </a-form-item>

                    <a-form-item label="抽取字段列表" required>
                      <input
                        ref="fileInputRef"
                        type="file"
                        accept=".xlsx,.xls"
                        style="display: none"
                        @change="onExcelFileChange"
                      />
                      <ApiFieldList
                        :rows="fieldRows"
                        :columns="fieldColumns"
                        :type-options="fieldTypeSelectOptions"
                        :enabled-count="enabledFieldCount"
                        :text-mode="textMode"
                        :field-keys-text="kingdeeQuery.fieldKeys"
                        key-placeholder="数据项名，如 采集单编码 / objectId"
                        hint-text="字段名就是云表数据项名（可为中文）；落库列名留空则按数据项名换算。类型按实测值选：空值返回 0.0 的列要用 VARCHAR，带小数的数值用 DECIMAL。"
                        :show-kingdee-tools="false"
                        :show-report-tools="false"
                        :kingdee-loading="false"
                        :field-check-loading="false"
                        :report-fields-loading="false"
                        :column-name-hint="columnNameHint"
                        @update:text-mode="textMode = $event"
                        @update:field-keys-text="kingdeeQuery.fieldKeys = $event"
                        @row-changed="onFieldRowChanged"
                        @add="addFieldRow"
                        @remove="removeFieldRow"
                        @move="moveFieldRow"
                        @import-excel="triggerExcelImport"
                        @export-excel="handleExportExcel"
                        @download-template="handleDownloadTemplate"
                        @open-paste="pasteModalVisible = true"
                        @infer-types="handleInferTypes"
                        @enable-all="toggleAllFieldEnabled"
                      />
                    </a-form-item>

                    <a-form-item label="请求体 Body（formJson）">
                      <a-textarea
                        v-model:value="formData.apiBody"
                        :rows="9"
                        class="code-textarea"
                        :placeholder="yunbiaoBodyPlaceholder"
                        @blur="onYunbiaoBodyChanged"
                      />
                      <div v-if="bodyManual" class="fk-hint">
                        已手工编辑请求体：保存以此处文本为准，上方字段仅用于展示与解析。
                      </div>
                    </a-form-item>

                    <!-- 变量定义：请求体里用 ${名称} 引用（过滤条件的取值也走这里）。
                         云表只认 formJson.filter 的 expressionList 结构，所以过滤条件直接写报文，不再提供易错的简化编辑器 -->
                    <div class="var-box" style="margin-bottom: 16px">
                      <div class="var-head">
                        <span class="var-title">变量定义</span>
                        <span class="var-hint">
                          执行时在目标数据源上以 SQL 计算，请求体里用 <code>${名称}</code> 引用；内置
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
                            placeholder="SELECT 表达式，如 DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 6 DAY), '%Y-%m-%d')"
                            style="flex: 1; min-width: 260px"
                            @change="onVariableEdited(v)"
                          />
                          <a-tooltip title="在目标数据源上执行一次，立即看到取值">
                            <a-button size="small" :loading="v.testing" @click="testVariable(v)">
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
                        未定义变量。需要「最近 7 天」这类动态日期时，在此配置 SQL，报文里用 ${名称} 引用。
                      </div>
                    </div>

                    <a-row :gutter="16">
                      <a-col :span="12">
                        <a-form-item label="结果提取 JsonPath" name="apiDataPath" required>
                          <a-input v-model:value="formData.apiDataPath" placeholder="$.results" />
                        </a-form-item>
                      </a-col>
                      <a-col :span="12">
                        <div style="padding-top: 30px">
                          <span style="font-size: 12px; color: #8c8c8c; margin-right: 6px">常用:</span>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.results'">$.results</a-tag>
                          <a-tag class="quick-chip" @click="formData.apiDataPath = '$.data'">$.data</a-tag>
                        </div>
                      </a-col>
                    </a-row>

                    <a-form-item label="分页取数">
                      <a-switch
                        :checked="pagination.enabled"
                        @change="(v: any) => onYunbiaoPagingToggle(!!v)"
                      />
                      <span style="margin-left: 8px; font-size: 12px; color: #8c8c8c">
                        行号模式：改写 formJson.pageInfo.rowIndex；每页条数写 pageInfo.pageSize
                      </span>
                    </a-form-item>
                    <template v-if="pagination.enabled">
                      <a-row :gutter="16">
                        <a-col :span="6">
                          <a-form-item label="每页条数">
                            <a-input-number
                              v-model:value="pagination.pageSize"
                              :min="1"
                              :max="5000"
                              style="width: 100%"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="6">
                          <a-form-item label="起始偏移">
                            <a-input-number v-model:value="pagination.start" :min="0" style="width: 100%" />
                          </a-form-item>
                        </a-col>
                        <a-col :span="6">
                          <a-form-item label="增长间隔">
                            <a-input-number v-model:value="pagination.step" :min="1" style="width: 100%" />
                          </a-form-item>
                        </a-col>
                        <a-col :span="6">
                          <a-form-item label="最多取数次数">
                            <a-input-number
                              v-model:value="pagination.maxPages"
                              :min="1"
                              :max="1000"
                              style="width: 100%"
                            />
                          </a-form-item>
                        </a-col>
                      </a-row>
                      <a-row :gutter="16">
                        <a-col :span="8">
                          <a-form-item label="偏移字段路径">
                            <a-input
                              v-model:value="pagination.offsetPath"
                              placeholder="formJson.pageInfo.rowIndex"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="8">
                          <a-form-item label="每页字段路径">
                            <a-input
                              v-model:value="pagination.limitPath"
                              placeholder="formJson.pageInfo.pageSize"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="8">
                          <a-form-item label="结束阈值（本页少于此数即停）">
                            <a-input-number
                              v-model:value="pagination.endWhenLessThan"
                              :min="0"
                              style="width: 100%"
                            />
                          </a-form-item>
                        </a-col>
                      </a-row>
                    </template>
                  </a-card>
                </div>


                <!-- 模式 A: 金蝶云星空 K/3 Cloud 模式（选了源连接后只隐藏步骤一，步骤二照常配置） -->
                <div v-else-if="formData.apiAuthType === 'KINGDEE_K3' || selectedApiConn?.connType === 'KINGDEE_K3'">
                  <!-- 步骤一：金蝶登录认证配置（由连接接管时隐藏） -->
                  <a-card
                    v-if="!selectedApiConn"
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

                  <!-- 数据抽取配置：三种连接（金蝶连接 / 云表连接 / 手工）都要用 -->
                  <a-card
                    :title="
                      selectedApiConn?.connType === 'IYUNBIAO'
                        ? '📥 云表数据抽取配置 (formJson)'
                        : bodyMode === 'REPORT'
                          ? '📥 金蝶报表取数配置 (parameters + QuicklyCondition)'
                          : '📥 金蝶单据数据抽取配置 (DynamicFormService.ExecuteBillQuery)'
                    "
                    :bordered="false"
                    class="pane-card module-card"
                  >
                    <template #extra>
                      <a-space>
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
                        <a-form-item
                          :label="
                            bodyMode === 'REPORT'
                              ? '报表查询接口 URL (GetReportData)'
                              : '单据查询接口 URL (ExecuteBillQuery)'
                          "
                          name="apiUrl"
                          required
                        >
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
                    </a-row>

                    <!-- 抽取字段列表：独占一行、左对齐，避免和上面的表单标识挤在一行 -->
                    <a-row :gutter="16">
                      <a-col :span="24">
                        <a-form-item label="抽取字段列表" required>
                          <input
                            ref="fileInputRef"
                            type="file"
                            accept=".xlsx,.xls"
                            style="display: none"
                            @change="onExcelFileChange"
                          />
                          <ApiFieldList
                            :rows="fieldRows"
                            :columns="fieldColumns"
                            :type-options="fieldTypeSelectOptions"
                            :enabled-count="enabledFieldCount"
                            :text-mode="textMode"
                            :field-keys-text="kingdeeQuery.fieldKeys"
                            key-placeholder="如 FBillNo 或 FSaleOrgId.FNumber"
                            hint-text="顺序即列序：抽取结果按 FieldKeys 下标对齐接口返回的顺序。类型用于创建临时表列（未指定按 TEXT），保存后生效，改类型会在下次运行时自动重建临时表。"
                            :show-kingdee-tools="true"
                            :show-report-tools="bodyMode === 'REPORT'"
                            :kingdee-loading="kingdeeFieldsLoading"
                            :field-check-loading="fieldCheckLoading"
                            :report-fields-loading="reportFieldsLoading"
                            :column-name-hint="columnNameHint"
                            @update:text-mode="textMode = $event"
                            @update:field-keys-text="kingdeeQuery.fieldKeys = $event"
                            @row-changed="onFieldRowChanged"
                            @add="addFieldRow"
                            @remove="removeFieldRow"
                            @move="moveFieldRow"
                            @import-excel="triggerExcelImport"
                            @export-excel="handleExportExcel"
                            @download-template="handleDownloadTemplate"
                            @open-paste="pasteModalVisible = true"
                            @pull-kingdee="handlePullKingdeeFields"
                            @validate-fields="handleValidateFieldKeys"
                            @detect-report-fields="handleDetectReportFields"
                            @infer-types="handleInferTypes"
                            @enable-all="toggleAllFieldEnabled"
                          />
                        </a-form-item>
                      </a-col>
                    </a-row>


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
                        <a-form-item class="body-form-item">
                          <template #label>
                            <div class="body-label-row">
                              <span>请求体 Body (JSON 格式完整结构)</span>
                              <a-button size="small" @click="formatJsonBody">
                                🪄 格式化 JSON
                              </a-button>
                            </div>
                          </template>
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
                                @change="onPaginationNumberChange('step')"
                              />
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
                                @change="onPaginationNumberChange('endWhenLessThan')"
                              />
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

                <!-- 模式 B: 通用前置接口登录 (双请求)；选了源连接后只隐藏步骤一 -->
                <div v-else-if="formData.apiAuthType === 'CUSTOM_LOGIN'">
                  <!-- 步骤一：通用登录认证配置（由连接接管时隐藏） -->
                  <a-card
                    v-if="!selectedApiConn"
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

                  <!-- 数据抽取配置 -->
                  <a-card
                    title="📥 业务数据接口抽取配置"
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
                    <!-- 字段清单的三个弹窗：放在分支外层，金蝶与云表模板共用 -->
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

                    <!-- 字段名校验：拿金蝶元数据核对手抄错误 -->
                    <a-modal
                      v-model:open="fieldCheckVisible"
                      title="字段名校验"
                      :footer="null"
                      width="780px"
                    >
                      <div v-if="fieldCheckLoading" class="kf-loading">
                        <a-spin />
                        <span>正在读取金蝶表单元数据并比对字段名，请稍候…</span>
                      </div>
                      <a-alert
                        v-else-if="fieldCheckError"
                        type="error"
                        show-icon
                        :message="fieldCheckError"
                      />
                      <template v-else>
                        <a-alert
                          v-if="fieldCheckProbeNote"
                          type="warning"
                          show-icon
                          style="margin-bottom: 10px"
                          :message="fieldCheckProbeNote"
                        />
                        <div class="kf-bar">
                          <span class="fk-count">
                            可改名 {{ fieldCheckRenameCount }} 个 · 需停用 {{ fieldCheckDisableCount }} 个
                          </span>
                          <a-space :size="6">
                            <a-button size="small" @click="toggleAllFieldCheck(true)">全选</a-button>
                            <a-button size="small" @click="toggleAllFieldCheck(false)">清空</a-button>
                          </a-space>
                        </div>
                        <a-table
                          :columns="fieldCheckColumns"
                          :data-source="fieldCheckRows"
                          size="small"
                          row-key="rid"
                          :pagination="false"
                          :scroll="{ y: 360 }"
                          bordered
                        >
                          <template #bodyCell="{ column, record }">
                            <template v-if="column.dataIndex === 'checked'">
                              <a-checkbox v-model:checked="record.checked" />
                            </template>
                            <template v-else-if="column.dataIndex === 'current'">
                              <span class="fk-mono fk-bad">{{ record.current }}</span>
                            </template>
                            <template v-else-if="column.dataIndex === 'suggestion'">
                              <span v-if="record.suggestion" class="fk-mono">{{ record.suggestion }}</span>
                              <span v-else class="fk-hint-inline">—</span>
                            </template>
                            <template v-else-if="column.dataIndex === 'action'">
                              <a-tag :color="record.action === 'RENAME' ? 'blue' : 'orange'">
                                {{ record.action === 'RENAME' ? '改名' : '停用' }}
                              </a-tag>
                            </template>
                          </template>
                        </a-table>
                        <div class="kf-footer">
                          <span class="fk-hint-inline">
                            只改字段名/启用状态，不动中文名与类型；处理后自动重算 FieldKeys 与请求体
                          </span>
                          <a-space>
                            <a-button @click="fieldCheckVisible = false">取消</a-button>
                            <a-button type="primary" @click="applyFieldCheck">
                              应用选中处理
                            </a-button>
                          </a-space>
                        </div>
                      </template>
                    </a-modal>
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
                        <a-select-option v-for="ds in mysqlTargetList" :key="ds.id" :value="ds.id">
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
                  <!-- UPSERT 冲突键与 SYNC 比对主键都取目标表真实主键，不在界面上配置 -->
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
                      <a-form-item label="范围起点（>=）">
                        <a-auto-complete
                          v-model:value="syncConfig.scopeStart"
                          :options="scopeBoundOptions"
                          :filter-option="false"
                          placeholder="如 2026-11-01，或选变量 ${begindate}"
                          @change="syncSyncConfig"
                          @select="(v: any) => onScopeBoundSelect('scopeStart', v)"
                        >
                          <template #option="item">
                            <span>{{ item.label || item.value }}</span>
                          </template>
                        </a-auto-complete>
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="范围终点（<）">
                        <a-auto-complete
                          v-model:value="syncConfig.scopeEnd"
                          :options="scopeBoundOptions"
                          :filter-option="false"
                          placeholder="如 2026-11-07，或选变量 ${enddate}"
                          @change="syncSyncConfig"
                          @select="(v: any) => onScopeBoundSelect('scopeEnd', v)"
                        >
                          <template #option="item">
                            <span>{{ item.label || item.value }}</span>
                          </template>
                        </a-auto-complete>
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-row :gutter="16">
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
                    执行时：在「范围字段 ≥ 起点 且 &lt; 终点」的范围内，目标表有、本次抽取没有的行 → 标记删除；
                    本次数据按<b>目标表主键</b>合并写入，并把删除标记复位。第 3 步的转换 SQL 需<b>留空</b>，由系统生成脚本。
                    目标表需先具备主键与软删除列（如 <code>is_deleted TINYINT DEFAULT 0</code>、
                    <code>delete_time DATETIME NULL</code>），主键在「更新（新建）目标表」里配置。
                  </div>
                </div>

                <!-- 字段映射：左=抽取字段，右=目标表字段 -->
                <div class="map-box">
                  <div class="map-head">
                    <span class="map-title">字段映射</span>
                    <span class="map-sub">{{ mappingSummary }}</span>
                    <a-space :size="8">
                      <a-button size="small" :loading="mappingLoading" @click="refreshMapping(true)">
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
                      <span class="map-pk-head">主键</span>
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
                      <span class="map-pk-cell">
                        <a-tooltip v-if="row.primaryKey" title="目标表主键">
                          <key-outlined class="map-pk" />
                        </a-tooltip>
                        <span v-else class="map-na">—</span>
                      </span>
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

    <!-- 目标表结构变更确认：左右对照单表，一张表管完增删改列与主键 -->
    <a-modal
      v-model:open="schemaModal.visible"
      :title="`配置目标表结构：${formData.targetTable || ''}`"
      width="1120px"
      :confirm-loading="schemaModal.submitting"
      ok-text="确认执行"
      cancel-text="取消"
      :body-style="{ padding: '12px 16px 0' }"
      @ok="submitSchema"
    >
      <a-alert
        v-if="!schemaModal.tableExists"
        type="warning"
        show-icon
        message="目标表不存在，确认后将按左侧字段新建"
        description="主键可留空；若写入模式为 SYNC，建议把比对主键设为 PRIMARY KEY，否则合并写入会重复。"
        style="margin-bottom: 10px"
      />

      <div class="sch-summary">
        <span>共 <b>{{ schemaStat.total }}</b> 行</span>
        <span>一致 <b>{{ schemaStat.match }}</b></span>
        <span class="is-warn">类型不一致 <b>{{ schemaStat.diff }}</b></span>
        <span class="is-new">待新增 <b>{{ schemaStat.add }}</b></span>
        <span class="is-extra">待删除 <b>{{ schemaStat.drop }}</b></span>
        <span class="is-pk">主键 <b>{{ schemaStat.pk }}</b></span>
      </div>

      <div class="sch-toolbar">
        <a-input
          v-model:value="schemaModal.keyword"
          size="small"
          placeholder="搜索字段名"
          allow-clear
          class="sch-search"
        >
          <template #prefix><search-outlined /></template>
        </a-input>
        <a-radio-group v-model:value="schemaModal.filter" size="small" button-style="solid">
          <a-radio-button value="diff">仅差异 {{ schemaStat.diffTotal }}</a-radio-button>
          <a-radio-button value="all">全部 {{ schemaStat.total }}</a-radio-button>
        </a-radio-group>
        <a-select v-model:value="schemaModal.sortMode" size="small" class="sch-sort">
          <a-select-option value="diff">差异置顶</a-select-option>
          <a-select-option value="origin">按原始列序</a-select-option>
        </a-select>
        <span class="sch-toolbar-tip">
          勾选框控制本次是否执行该列的变更；钥匙点亮即设为主键（主键行在「仅差异」下也常驻，便于随时取消）
        </span>
      </div>

      <div class="sch-scroll">
        <table class="sch-table">
          <thead>
            <tr>
              <th class="c-check"></th>
              <th class="c-act"></th>
              <th class="c-name">源字段（抽取）</th>
              <th class="c-type">源类型</th>
              <th class="c-arrow"></th>
              <th class="c-name">目标字段</th>
              <th class="c-ttype">目标类型</th>
              <th class="c-pk">主键</th>
              <th class="c-status">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in schemaVisibleRows"
              :key="row.key"
              :class="['sch-row', `is-${row.status.toLowerCase()}`]"
            >
              <td class="c-check">
                <a-checkbox
                  v-if="row.status !== 'MATCH'"
                  :checked="row.checked"
                  :disabled="row.status === 'MISSING_IN_SOURCE' && row.pinned"
                  :title="row.pinned ? '当前主键列不可删除' : ''"
                  @change="(e: any) => onRowCheck(row, !!e.target.checked)"
                />
                <span v-else class="sch-na">—</span>
              </td>
              <td class="c-act">
                <span :class="['sch-act', `is-${row.status.toLowerCase()}`]">{{ actText(row) }}</span>
              </td>
              <td class="c-name">
                <span class="sch-mono">{{ row.sourceName || '—' }}</span>
              </td>
              <td class="c-type sch-mono">{{ row.sourceType || '—' }}</td>
              <td class="c-arrow">
                <span
                  v-if="row.status === 'MISSING_IN_TARGET'"
                  class="sch-arrow is-new">→</span>
                <span
                  v-else-if="row.status === 'MISSING_IN_SOURCE'"
                  class="sch-arrow is-extra">←</span>
                <span v-else class="sch-arrow">⇄</span>
              </td>
              <td class="c-name">
                <span v-if="row.targetName" class="sch-mono">{{ row.targetName }}</span>
                <span v-else class="sch-pending">待新增</span>
              </td>
              <td class="c-ttype">
                <a-select
                  v-if="row.typeEditable"
                  v-model:value="row.editType"
                  size="small"
                  show-search
                  :options="schemaModal.typeOptions"
                  class="sch-type-select"
                  :title="`当前 ${row.targetType || '（无此列）'} → 执行 ${row.editType}`"
                />
                <span v-else class="sch-mono sch-type-ro">{{ row.targetType || '—' }}</span>
              </td>
              <td class="c-pk">
                <button
                  type="button"
                  class="sch-pk"
                  :class="{ 'is-on': row.isPrimary }"
                  :disabled="row.status === 'MISSING_IN_SOURCE'"
                  :title="pkTitle(row)"
                  @click="togglePrimary(row)"
                >
                  <key-outlined />
                </button>
              </td>
              <td class="c-status">
                <span :class="['map-status', row.statusClass]">{{ statusLabel(row) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!schemaVisibleRows.length" class="sch-empty">
          {{
            schemaModal.rows.length
              ? '没有符合条件的字段，换个关键字或切到「全部」'
              : '暂无字段：左侧来自第 2 步抽取字段清单，请先维护字段'
          }}
        </div>
      </div>

      <div class="sch-plan">{{ schemaPlanText }}</div>
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
  TableOutlined,
  FileExcelOutlined,
  DownloadOutlined,
  FileAddOutlined,
  PlusOutlined,
  ApiOutlined,
  BulbOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  ThunderboltOutlined
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import SqlEditor from './SqlEditor.vue'
import ApiFieldList from './ApiFieldList.vue'
import {
  savePipeline,
  updatePipeline,
  saveStepPipeline,
  testWebapiLogin,
  previewWebapiExtract,
  queryKingdeeFormFields,
  verifyKingdeeFieldKeys,
  detectKingdeeForm,
  detectKingdeeReportFields,
  saveKingdeeCredential,
  getKingdeeCredentialDefault,
  evaluateVariable,
  evaluateLoop,
  describeSourceColumns,
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
import { columnNameMap, toColumnName } from '../utils/columnNaming'

const emit = defineEmits(['back', 'success', 'saved'])

const activeTab = ref('1')
const isEdit = ref(false)
const submitting = ref(false)
const currentId = ref<string>('')
const formRef = ref()

const datasourceList = ref<EtlDatasource[]>([])

/** 连接类型显示名 */
const connTypeLabel = (type?: string) =>
  type === 'KINGDEE_K3'
    ? '金蝶云星空'
    : type === 'IYUNBIAO'
      ? '云表 OpenAPI'
      : type === 'POSTGRESQL'
        ? 'PostgreSQL'
        : 'MySQL'

/** 当前选中的源连接（只认 API 类连接；MySQL 库 id 也存这个字段，不能混为一谈） */
const selectedApiConn = computed(() =>
  datasourceList.value.find(
    (d) =>
      d.id &&
      String(d.id) === String(formData.value.sourceDsId) &&
      d.connType &&
      d.connType !== 'MYSQL'
  )
)

/** 云表连接：走云表专用模板（不看 api_auth_type，那是金蝶那套的遗留字段） */
const isYunbiaoConn = computed(() => selectedApiConn.value?.connType === 'IYUNBIAO')

/**
 * 云表只让用户填「表单名称」，`openapi/1.0/` 前缀由后端补。
 * 回显时剥掉前缀；历史上有把金蝶接口地址误存进 api_url 的情况，这种脏值不显示，
 * 否则用户会以为那就是表单名，保存时又原样写回去。
 */
const yunbiaoFormName = computed({
  get: () => {
    const raw = String(formData.value.apiUrl || '').trim()
    if (raw.includes('/k3cloud/')) {
      return ''
    }
    return raw.replace(/^\/?openapi\/1\.0\//, '')
  },
  set: (value: string) => {
    formData.value.apiUrl = String(value || '').trim()
  }
})

// ===== 云表简化过滤条件（直接对应报文里的 formJson.filter 数组）=====
interface YunbiaoFilterRow {
  rid: string
  filterField: string
  Operator: string
  Value: string
  isAnd: boolean
}

const YUNBIAO_OPERATORS = [
  { label: '等于 ($e)', value: '$e' },
  { label: '不等于 ($ne)', value: '$ne' },
  { label: '大于 ($gt)', value: '$gt' },
  { label: '大于等于 ($gte)', value: '$gte' },
  { label: '小于 ($lt)', value: '$lt' },
  { label: '小于等于 ($lte)', value: '$lte' },
  { label: '包含 (Like=10)', value: '10' },
  { label: '不包含 (NotLike=11)', value: '11' },
  { label: '属于 (In=12)', value: '12' },
  { label: '不属于 (NotIn=15)', value: '15' },
  { label: '为空 (IsNull=13)', value: '13' },
  { label: '不为空 (IsNotNull=14)', value: '14' }
]

const yunbiaoFilter = ref<YunbiaoFilterRow[]>([])

const newFilterRid = () => `f_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`

/** 从报文里读出 formJson.filter，供简化编辑器展示 */
const loadYunbiaoFilter = () => {
  yunbiaoFilter.value = []
  try {
    const root = JSON.parse(formData.value.apiBody || '{}')
    const list = root?.formJson?.filter
    if (Array.isArray(list)) {
      yunbiaoFilter.value = list.map((item: any) => ({
        rid: newFilterRid(),
        filterField: String(item?.filterField ?? ''),
        Operator: String(item?.Operator ?? '$e'),
        Value: item?.Value == null ? '' : String(item.Value),
        isAnd: item?.isAnd !== false
      }))
    }
  } catch (e) {
    // 报文不是合法 JSON 时不报错，等用户自己修
  }
}

/** 把简化编辑器写回报文的 formJson.filter（报文非法时不动，避免覆盖用户的原文） */
const syncYunbiaoFilter = () => {
  try {
    const root = JSON.parse(formData.value.apiBody || '{}')
    if (!root.formJson) root.formJson = {}
    root.formJson.filter = yunbiaoFilter.value
      .filter((r) => r.filterField.trim())
      .map((r) => ({
        filterField: r.filterField.trim(),
        Operator: r.Operator,
        Value: r.Value,
        isAnd: r.isAnd
      }))
    formData.value.apiBody = JSON.stringify(root, null, 2)
    bodyManual.value = true
  } catch (e) {
    message.warning('请求体不是合法 JSON，过滤条件未写回，请先修正报文')
  }
}

const addYunbiaoFilter = () => {
  yunbiaoFilter.value.push({
    rid: newFilterRid(),
    filterField: '',
    Operator: '$e',
    Value: '',
    isAnd: true
  })
}

const removeYunbiaoFilter = (index: number) => {
  yunbiaoFilter.value.splice(index, 1)
  syncYunbiaoFilter()
}

/** 不需要值的算子（IsNull / IsNotNull） */
const filterNeedsValue = (op: string) => op !== '13' && op !== '14'

/** 云表报文体占位符 */
const yunbiaoBodyPlaceholder = `{
  "formJson": {
    "pageInfo": { "isUseRowIndex": true, "pageCount": 0, "pageIndex": 0, "pageSize": 200, "rowIndex": 0, "total": 0 },
    "filter": [],
    "paramList": []
  }
}`

/** 手改报文后重新解析过滤条件（用 @blur，避免边打字边重建数组） */
const onYunbiaoBodyChanged = () => {
  bodyManual.value = true
  loadYunbiaoFilter()
}

/** 云表默认模板存本地：表单名 + 报文 + 分页，下次新建直接带出 */
const YUNBIAO_TEMPLATE_KEY = 'etl.yunbiao.defaultTemplate'

/** 保存当前配置为云表默认模板 */
const saveYunbiaoTemplate = () => {
  if (!formData.value.apiUrl || !formData.value.apiBody) {
    message.warning('请先填写表单名称与请求体，再保存为默认模板')
    return
  }
  try {
    localStorage.setItem(
      YUNBIAO_TEMPLATE_KEY,
      JSON.stringify({
        formName: formData.value.apiUrl,
        apiBody: formData.value.apiBody,
        apiDataPath: formData.value.apiDataPath,
        apiPagination: pagination.value
      })
    )
    message.success('已保存为云表默认模板')
  } catch (e) {
    message.error('保存模板失败：浏览器存储不可用')
  }
}

/** 载入默认模板：优先用保存过的，没有就用内置的 formJson 模板 */
const applyYunbiaoTemplate = () => {
  let fromSaved = false
  try {
    const raw = localStorage.getItem(YUNBIAO_TEMPLATE_KEY)
    if (raw) {
      const tpl = JSON.parse(raw)
      if (tpl.formName) formData.value.apiUrl = tpl.formName
      if (tpl.apiBody) formData.value.apiBody = tpl.apiBody
      formData.value.apiDataPath = tpl.apiDataPath || '$.results'
      if (tpl.apiPagination) Object.assign(pagination.value, tpl.apiPagination)
      fromSaved = true
    }
  } catch (e) {
    // 模板损坏回退到内置
  }
  if (!fromSaved) {
    formData.value.apiMethod = 'POST'
    formData.value.apiBody = yunbiaoBodyPlaceholder
    formData.value.apiDataPath = '$.results'
    if (!pagination.value.offsetPath?.includes('formJson')) {
      pagination.value.offsetPath = 'formJson.pageInfo.rowIndex'
      pagination.value.limitPath = 'formJson.pageInfo.pageSize'
    }
  }
  bodyManual.value = false
  loadYunbiaoFilter()
  message.success(fromSaved ? '已载入云表默认模板' : '已载入内置 formJson 模板')
}

/** 云表分页开关：默认补上 pageInfo 的行号/每页条数路径 */
const onYunbiaoPagingToggle = (checked: boolean) => {
  pagination.value.enabled = checked
  if (!checked) {
    return
  }
  if (!pagination.value.offsetPath?.includes('formJson')) {
    pagination.value.offsetPath = 'formJson.pageInfo.rowIndex'
  }
  if (!pagination.value.limitPath?.includes('formJson')) {
    pagination.value.limitPath = 'formJson.pageInfo.pageSize'
  }
  if (!pagination.value.endWhenLessThan) {
    pagination.value.endWhenLessThan = pagination.value.pageSize
  }
}

/** JDBC 源库：MySQL / PostgreSQL 都可以作为抽取来源（云表/金蝶是 API 连接，不在其中） */
const jdbcSourceList = computed(() =>
  datasourceList.value.filter(
    (d) => !d.connType || d.connType === 'MYSQL' || d.connType === 'POSTGRESQL'
  )
)

/** 目标端只能是 MySQL（数仓是 MySQL；PG 本期只做只读源库） */
const mysqlTargetList = computed(() =>
  datasourceList.value.filter((d) => !d.connType || d.connType === 'MYSQL')
)

/** 「直连免登」哨兵值：只存在于界面上，选中即表示不选连接、走手工鉴权，不落库 */
const MANUAL_AUTH_VALUE = '__MANUAL__'

/** 当前是否处于「直连免登（手工配置鉴权）」状态 */
const manualAuth = ref(false)

/** 切换输入源类型：MySQL 选的是库、API 选的是连接，同一个字段不能串 */
const onSourceTypeChange = () => {
  formData.value.sourceDsId = undefined
  manualAuth.value = false
  // 字段清单跟着源类型走：MySQL 的 SQL 字段与 API 的字段清单不能混用
  fieldRows.value = []
}

/** 源连接下拉的绑定值：直连免登时显示哨兵值，实际 formData.sourceDsId 置空 */const sourceConnValue = computed({
  get: () => (manualAuth.value ? MANUAL_AUTH_VALUE : formData.value.sourceDsId),
  set: (value?: string) => {
    if (value === MANUAL_AUTH_VALUE) {
      manualAuth.value = true
      formData.value.sourceDsId = undefined
      return
    }
    manualAuth.value = false
    formData.value.sourceDsId = value
  }
})

/** 源端可选的 API 连接（金蝶 / 云表）；首项「直连免登」用于切回手工配置 */
const apiConnectionOptions = computed(() => [
  { label: '直连免登（手工配置鉴权）', value: MANUAL_AUTH_VALUE },
  ...datasourceList.value
    .filter((d) => d.connType && d.connType !== 'MYSQL')
    .map((d) => ({ label: `${d.dsName}（${connTypeLabel(d.connType)}）`, value: d.id as string }))
])

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
  // 显式切换取数方式：按所选模板整段重建，不做原位修补
  syncKingdeeQueryToBody(true)
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

// ===== 查询接口地址随取数方式切换 =====
// 单据走 DynamicFormService.ExecuteBillQuery，报表走 StockReportQueryService.GetReportData。
// 两者是不同服务，只改报文不改地址，金蝶会按旧服务去解析新报文，必然报错。
const K3_BILL_QUERY_PATH =
  'Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc'
const K3_REPORT_QUERY_PATH =
  'Kingdee.K3.SCM.WebApi.ServicesStub.StockReportQueryService.GetReportData,Kingdee.K3.SCM.WebApi.ServicesStub.common.kdsvc'

/**
 * 按当前取数方式把查询地址切到对应服务。
 * 只在这两个已知的金蝶服务之间互换：主机与 /k3cloud/ 之前的前缀保持原样；
 * 自定义接口地址（不含 /k3cloud/，或服务名不是这两个）一律不动，避免覆盖用户手填的地址。
 */
const syncQueryUrlToMode = () => {
  const url = (formData.value.apiUrl || '').trim()
  if (!url.includes('/k3cloud/')) return
  const isReport = url.includes('GetReportData')
  const isBill = url.includes('ExecuteBillQuery')
  if (!isReport && !isBill) return

  const target = bodyMode.value === 'REPORT' ? K3_REPORT_QUERY_PATH : K3_BILL_QUERY_PATH
  if (url.endsWith(target)) return

  const cut = url.indexOf('/k3cloud/') + '/k3cloud/'.length
  formData.value.apiUrl = url.substring(0, cut) + target
}

// 报表模式的条件预览
const quickConditionPreview = computed(() =>
  quickConditions.value
    .filter((c) => c.fieldName.trim())
    .map((c) => `${c.fieldName.trim()} = ${c.fieldValue}`)
    .join(' AND ')
)

// ===== 高级配置：分页取数（对应 t_etl_pipeline.api_pagination） =====
// 增长间隔 / 结束条件默认与「每页条数」保持一致，用户改过就不再自动跟随
const defaultPagination = () => ({
  enabled: false,
  offsetPath: 'data.StartRow',
  limitPath: 'data.Limit',
  start: 0,
  step: 2000,
  pageSize: 2000,
  maxPages: 200,
  endWhenLessThan: 2000,
  intervalMs: 0
})

const pagination = ref(defaultPagination())
/** 用户是否手工改过这两项（改过就不再被每页条数覆盖） */
const paginationCustom = ref({ step: false, endWhenLessThan: false })
// 默认展开，避免被当成一条不起眼的折叠项
const advancedActiveKeys = ref<string[]>(['pagination'])

/**
 * 增长间隔 / 结束条件必须大于 0：
 * 旧版本用 0 表示「与每页条数相同」，这类老配置在回显时已直接换算成每页条数，
 * 所以表单里再填 0 就是无效值，提示并还原为每页条数。
 */
const onPaginationNumberChange = (key: 'step' | 'endWhenLessThan') => {
  const value = Number(pagination.value[key])
  if (!value || value <= 0) {
    message.warning('请填写大于 0 的数')
    pagination.value[key] = pagination.value.pageSize
    paginationCustom.value[key] = false
  } else {
    paginationCustom.value[key] = value !== pagination.value.pageSize
  }
  syncPagination()
}

/** 每页条数变化时，未被用户改过的两项跟着走 */
const syncPaginationDefaults = (pageSize: number) => {
  if (!paginationCustom.value.step) pagination.value.step = pageSize
  if (!paginationCustom.value.endWhenLessThan) pagination.value.endWhenLessThan = pageSize
}

// api_pagination 在库里是 MySQL JSON 列，空字符串会被判为非法 JSON，关闭时写 {} 而不是 ''
const syncPagination = () => {
  formData.value.apiPagination = JSON.stringify(
    pagination.value.enabled ? pagination.value : {}
  )
}

const loadPagination = () => {
  pagination.value = defaultPagination()
  paginationCustom.value = { step: false, endWhenLessThan: false }
  if (formData.value.apiPagination) {
    try {
      const parsed = JSON.parse(formData.value.apiPagination)
      pagination.value = { ...pagination.value, ...parsed }
      advancedActiveKeys.value = ['pagination']
    } catch (e) {
      pagination.value = defaultPagination()
    }
  }
  // 每页条数以「单次抓取上限」为准，避免两处配置不一致；
  // 云表报文没有 Limit 字段，不要拿金蝶的 limit 把 rowIndex 分页的每页条数顶回 2000
  const pageSize = isYunbiaoConn.value
    ? pagination.value.pageSize || 200
    : kingdeeQuery.value.limit || pagination.value.pageSize || 2000
  pagination.value.pageSize = pageSize
  // 老配置的 0（= 与每页条数相同）直接换算成具体值；与每页条数相同的也视为未自定义
  paginationCustom.value = {
    step: pagination.value.step > 0 && pagination.value.step !== pageSize,
    endWhenLessThan:
      pagination.value.endWhenLessThan > 0 && pagination.value.endWhenLessThan !== pageSize
  }
  syncPaginationDefaults(pageSize)
}

// 开启分页时先把每页条数对齐到单次抓取上限
const onPaginationToggle = () => {
  if (pagination.value.enabled) {
    pagination.value.pageSize = kingdeeQuery.value.limit || 2000
    syncPaginationDefaults(pagination.value.pageSize)
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
// 删除判定范围用「起点 >= / 终点 <=」两个取值描述，取值可为常量、${变量} 或原生 SQL 表达式
const defaultSyncConfig = () => ({
  scopeField: '',
  scopeStart: '',
  scopeEnd: '',
  softDeleteColumn: 'is_deleted',
  softDeleteTimeColumn: 'delete_time',
  maxDeleteRatio: 0.5
})

const syncConfig = ref(defaultSyncConfig())

const syncSyncConfig = () => {
  formData.value.syncConfig = JSON.stringify(syncConfig.value)
}

// 范围起止支持从第 2 步的变量定义里直接挑，避免手抄 ${name} 抄错
const onScopeBoundSelect = (field: 'scopeStart' | 'scopeEnd', value: unknown) => {
  syncConfig.value[field] = String(value ?? '')
  syncSyncConfig()
}

const loadSyncConfig = () => {
  syncConfig.value = defaultSyncConfig()
  if (!formData.value.syncConfig) {
    return
  }
  try {
    const parsed = JSON.parse(formData.value.syncConfig)
    syncConfig.value = { ...syncConfig.value, ...parsed }
    // 兼容改造前的配置：只有窗口天数时折算成「近 N 天」的原生表达式，行为保持不变
    const legacyDays = Number((parsed as any)?.scopeDays)
    if (!syncConfig.value.scopeStart && !syncConfig.value.scopeEnd && legacyDays > 0) {
      syncConfig.value.scopeStart = `DATE_SUB(CURDATE(), INTERVAL ${legacyDays} DAY)`
    }
    delete (syncConfig.value as any).scopeDays
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
  /** 目标表该列是否为主键（来自 information_schema，点「重新获取」时刷新） */
  primaryKey?: boolean
  status: MappingStatus
  statusText: string
  statusClass: string
}

const targetTableOptions = ref<{ value: string }[]>([])
const targetColumns = ref<TargetTableColumn[]>([])
const mappingLoading = ref(false)
let lastMappingTable = ''

// 左侧：第 2 步抽取字段；增量同步模式自动补上软删除列
const sourceFields = computed(() => {
  const rows = fieldRows.value.filter((r) => r.fieldKey.trim() && r.enabled)
  // 金蝶标识可能带点（FSaleOrgId.FNumber）：发给金蝶用原名，建表/映射用换算后的列名
  const columnNames = columnNameMap(rows.map((r) => r.fieldKey.trim()))
  const list = rows.map((r) => {
    const key = r.fieldKey.trim()
    // 字段清单里手工填的「落库列名」优先，其次自动换算（中文数据项保留中文）
    const manual = (r.column || '').trim()
    const name = manual || columnNames.get(key) || key
    return { name, type: r.type || 'VARCHAR(255)' }
  })
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
        primaryKey: t.primaryKey,
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
      primaryKey: t.primaryKey,
      status: 'MISSING_IN_SOURCE',
      statusText: '目标表多余',
      statusClass: 'is-extra'
    })
  }
  return rows
})

const mappingSummary = computed(() => {
  // 第 2 步里填了字段名但没勾「启用」的，不会进映射——这类最容易被当成"字段丢了"
  const disabled = fieldRows.value.filter((r) => r.fieldKey.trim() && !r.enabled).length
  if (!formData.value.targetTable || !mappingRows.value.length) {
    if (disabled) {
      return `第 2 步有 ${disabled} 个字段未启用，未参与映射（回到第 2 步打开「启用」开关）`
    }
    return ''
  }
  if (!targetColumns.value.length) return '目标表不存在，可点「更新（新建）目标表」创建'
  const miss = mappingRows.value.filter((r) => r.status === 'MISSING_IN_TARGET').length
  const extra = mappingRows.value.filter((r) => r.status === 'MISSING_IN_SOURCE').length
  const diff = mappingRows.value.filter((r) => r.status === 'TYPE_DIFF').length
  const pks = mappingRows.value
    .filter((r) => r.primaryKey)
    .map((r) => r.targetName)
    .filter(Boolean)
  const parts = [`共 ${mappingRows.value.length} 行`]
  if (disabled) parts.push(`另 ${disabled} 个未启用`)
  if (miss) parts.push(`缺 ${miss} 列`)
  if (extra) parts.push(`多 ${extra} 列`)
  if (diff) parts.push(`类型不一致 ${diff}`)
  if (!miss && !extra && !diff) parts.push('结构与映射一致')
  // 主键来自 information_schema，目标表结构一变这里跟着变
  parts.push(pks.length ? `主键 ${pks.join(' + ')}` : '无主键')
  return parts.join(' · ')
})

const filterTableOption = (input: string, option: { value: string }) =>
  option.value.toLowerCase().includes((input || '').toLowerCase())

const refreshMapping = async (force = false) => {
  if (!formData.value.targetDsId) {
    targetTableOptions.value = []
    targetColumns.value = []
    // 原来这里静默返回，用户点按钮完全没感觉
    if (force) {
      message.warning('请先在下方选择「目标 MySQL 数仓连接」')
    }
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
    if (force) {
      if (!table) {
        message.info('请先填写目标数据表名，字段映射会自动与目标表对齐')
      } else if (!targetColumns.value.length) {
        message.warning(`目标表 ${table} 不存在，可点「更新（新建）目标表」创建`)
      } else {
        message.success(`已刷新：${table}，目标表 ${targetColumns.value.length} 列`)
      }
    }
  } catch (e) {
    targetColumns.value = []
    if (force) {
      message.error('读取目标表结构失败，请检查数据源连接')
    }
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

// ===== 目标表结构变更确认弹窗（左右对照单表）=====
interface SchemaRowVM {
  key: string
  order: number
  sourceName?: string
  sourceType?: string
  targetName?: string
  targetType?: string
  /** 本次要落到的目标类型：改类型/新增列默认对齐源类型 */
  editType: string
  status: MappingStatus
  statusClass: string
  /** 勾选=本次执行该列的变更 */
  checked: boolean
  /** 勾选=作为主键 */
  isPrimary: boolean
  /** 目标表当前的主键列，必须常驻列表，取消后也还能找回来 */
  pinned: boolean
  /** 目标类型是否可改（一致行、待删除行、自增列不可改） */
  typeEditable: boolean
  autoIncrement: boolean
  nullable: boolean
  comment?: string
}

// 差异置顶排序权重：一眼先看见要动的列
const SCHEMA_STATUS_WEIGHT: Record<string, number> = {
  TYPE_DIFF: 0,
  MISSING_IN_TARGET: 1,
  MISSING_IN_SOURCE: 2,
  MATCH: 3
}

const schemaModal = ref({
  visible: false,
  submitting: false,
  tableExists: false,
  rows: [] as SchemaRowVM[],
  keyword: '',
  filter: 'diff' as 'diff' | 'all',
  sortMode: 'diff' as 'diff' | 'origin',
  typeOptions: [] as { value: string; label: string }[]
})

const openSchemaModal = () => {
  const rows: SchemaRowVM[] = mappingRows.value.map((r, i) => {
    const col = r.targetName
      ? targetColumns.value.find((c) => c.name.toLowerCase() === (r.targetName as string).toLowerCase())
      : undefined
    const autoInc = !!col?.autoIncrement
    // 自增列不参与改类型：MODIFY COLUMN 会把 AUTO_INCREMENT 属性丢掉
    const canEditType =
      (r.status === 'TYPE_DIFF' && !autoInc) || r.status === 'MISSING_IN_TARGET'
    return {
      key: r.key,
      order: i,
      sourceName: r.sourceName,
      sourceType: r.sourceType,
      targetName: r.targetName,
      targetType: r.targetType,
      editType: (
        (canEditType ? r.sourceType : r.targetType || r.sourceType) || 'VARCHAR(255)'
      ).toUpperCase(),
      status: r.status,
      statusClass: r.statusClass,
      checked: canEditType,
      isPrimary: !!col?.primaryKey,
      pinned: !!col?.primaryKey,
      typeEditable: canEditType,
      autoIncrement: autoInc,
      nullable: col ? col.nullable : true,
      comment: col?.comment
    }
  })

  // 主键一律以目标表现有主键为准（isPrimary 已在上面的 map 里取 col.primaryKey），
  // 不再按流程配置预选

  const typeSet = new Set<string>(FIELD_TYPE_OPTIONS)
  targetColumns.value.forEach((c) => c.columnType && typeSet.add(c.columnType.toUpperCase()))
  rows.forEach((r) => r.editType && typeSet.add(r.editType))

  schemaModal.value = {
    visible: true,
    submitting: false,
    tableExists: targetColumns.value.length > 0,
    rows,
    keyword: '',
    // 默认只给差异：811 行一致的数据压在前面，等于让人滚 800 行去找那 41 个差异
    filter: 'diff',
    sortMode: 'diff',
    typeOptions: [...typeSet].map((t) => ({ value: t, label: t }))
  }
}

const schemaStat = computed(() => {
  const rows = schemaModal.value.rows
  return {
    total: rows.length,
    match: rows.filter((r) => r.status === 'MATCH').length,
    diff: rows.filter((r) => r.status === 'TYPE_DIFF').length,
    add: rows.filter((r) => r.status === 'MISSING_IN_TARGET').length,
    drop: rows.filter((r) => r.status === 'MISSING_IN_SOURCE').length,
    pk: rows.filter((r) => r.isPrimary).length,
    diffTotal: rows.filter((r) => r.status !== 'MATCH' || r.pinned).length
  }
})

const schemaVisibleRows = computed(() => {
  const { rows, keyword, filter, sortMode } = schemaModal.value
  const kw = keyword.trim().toLowerCase()
  const list = rows.filter((r) => {
    if (filter === 'diff' && r.status === 'MATCH' && !r.pinned) return false
    if (!kw) return true
    return `${r.sourceName || ''} ${r.targetName || ''}`.toLowerCase().includes(kw)
  })
  if (sortMode === 'origin') return list
  return [...list].sort(
    (a, b) => SCHEMA_STATUS_WEIGHT[a.status] - SCHEMA_STATUS_WEIGHT[b.status] || a.order - b.order
  )
})

const actText = (row: SchemaRowVM) => {
  if (row.status === 'TYPE_DIFF') return '改'
  if (row.status === 'MISSING_IN_TARGET') return '增'
  if (row.status === 'MISSING_IN_SOURCE') return '删'
  return '—'
}

const statusLabel = (row: SchemaRowVM) => {
  if (row.status === 'MATCH') return row.isPrimary ? '一致 · 主键' : '一致'
  if (row.status === 'TYPE_DIFF') return row.checked ? '类型不一致' : '跳过'
  if (row.status === 'MISSING_IN_TARGET') return row.checked ? '待新增' : '跳过'
  return row.checked ? '待删除' : '保留'
}

const pkTitle = (row: SchemaRowVM) => {
  if (row.status === 'MISSING_IN_SOURCE') return '目标表多余的列不能设为主键'
  return row.isPrimary ? '点击取消主键' : '设为主键'
}

const onRowCheck = (row: SchemaRowVM, checked: boolean) => {
  if (checked && row.status === 'MISSING_IN_SOURCE' && row.pinned) {
    message.warning(`${row.targetName} 是当前主键列，不能删除；请先取消它的主键`)
    return
  }
  row.checked = checked
}

const togglePrimary = (row: SchemaRowVM) => {
  if (row.status === 'MISSING_IN_SOURCE') {
    message.warning('目标表多余的列不能设为主键')
    return
  }
  if (row.isPrimary) {
    row.isPrimary = false
    return
  }
  const type = (row.editType || '').toUpperCase()
  // TEXT/BLOB 没有长度前缀不能建主键，MySQL 会报 1170
  if (type.startsWith('TEXT') || type.startsWith('BLOB')) {
    message.warning(
      `${row.targetName || row.sourceName} 是 ${type} 类型，不能直接做主键，请先把类型改成 VARCHAR(n)`
    )
    return
  }
  row.isPrimary = true
  if (row.status === 'MISSING_IN_TARGET') row.checked = true
}

const sameKeySet = (current: string[], desired: string[]) => {
  if (current.length !== desired.length) return false
  const lower = current.map((k) => k.toLowerCase())
  return desired.every((k) => lower.includes(k.toLowerCase()))
}

// 弹窗底部的变更摘要
const schemaPlanText = computed(() => {
  const rows = schemaModal.value.rows
  const add = rows.filter((r) => r.status === 'MISSING_IN_TARGET' && r.checked).length
  const mod = rows.filter((r) => r.status === 'TYPE_DIFF' && r.checked).length
  const drop = rows.filter((r) => r.status === 'MISSING_IN_SOURCE' && r.checked).length
  const keys = rows
    .filter((r) => r.isPrimary)
    .map((r) => r.targetName || r.sourceName || '')
    .filter(Boolean)
  const currentKeys = targetColumns.value.filter((c) => c.primaryKey).map((c) => c.name)
  const parts: string[] = []
  if (add) parts.push(`新增 ${add} 列`)
  if (mod) parts.push(`改类型 ${mod} 列`)
  if (drop) parts.push(`删除 ${drop} 列`)
  if (!keys.length && currentKeys.length) parts.push('清空主键')
  else if (keys.length && !sameKeySet(currentKeys, keys)) parts.push(`主键改为 ${keys.join(' + ')}`)
  return parts.length
    ? `拟执行：${parts.join(' · ')}`
    : '未检测到变更：勾选要执行的列，或用钥匙调整主键'
})

const submitSchema = async () => {
  if (!formData.value.targetDsId || !formData.value.targetTable) {
    message.warning('请先选择目标数据源与目标表')
    return
  }
  const rows = schemaModal.value.rows
  const addColumns = rows
    .filter((r) => r.status === 'MISSING_IN_TARGET' && r.checked && r.sourceName)
    .map((r) => ({ name: r.sourceName as string, type: r.editType || 'VARCHAR(255)' }))
  const modifyColumns = rows
    .filter((r) => r.status === 'TYPE_DIFF' && r.checked && r.targetName && !r.autoIncrement)
    .map((r) => ({
      name: r.targetName as string,
      type: r.editType || r.sourceType || 'VARCHAR(255)',
      comment: r.comment,
      // 主键列必须 NOT NULL，其余列保持原有约束
      nullable: r.isPrimary ? false : r.nullable
    }))
  const dropColumns = rows
    .filter((r) => r.status === 'MISSING_IN_SOURCE' && r.checked && r.targetName)
    .map((r) => r.targetName as string)
  const primaryKeys = rows
    .filter((r) => r.isPrimary)
    .map((r) => r.targetName || r.sourceName || '')
    .filter(Boolean)

  const currentKeys = targetColumns.value.filter((c) => c.primaryKey).map((c) => c.name)
  const pkChanged = primaryKeys.length > 0 && !sameKeySet(currentKeys, primaryKeys)
  // 原有主键全被取消且没选新的 = 清空主键；后端靠这个标记区分「不改」与「清空」
  const dropPrimaryKey = primaryKeys.length === 0 && currentKeys.length > 0

  if (
    !addColumns.length &&
    !dropColumns.length &&
    !modifyColumns.length &&
    !pkChanged &&
    !dropPrimaryKey
  ) {
    message.warning('没有需要变更的内容：勾选要执行的列，或调整主键')
    return
  }

  schemaModal.value.submitting = true
  try {
    const res = await applyTargetSchema({
      datasourceId: formData.value.targetDsId,
      table: formData.value.targetTable.trim(),
      addColumns,
      modifyColumns,
      dropColumns,
      primaryKeys,
      dropPrimaryKey
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

// 增量同步「范围起点/终点」下拉：选中即插入 ${变量名}，执行时在目标库上实时计算
const scopeBoundOptions = computed(() =>
  variableOptions.value.map((v) => ({
    value: '${' + v.value + '}',
    label: `${v.value}（变量）`
  }))
)

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
    loop: {
      enabled: loopConfig.value.enabled,
      sql: loopConfig.value.sql.trim(),
      maxIterations: loopConfig.value.maxIterations,
      intervalMs: loopConfig.value.intervalMs
    },
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

// ===== MySQL 源：变量定义（写回 filter_meta.variables，供抽取 SQL 里的 ${名称} 使用）=====
// 不复用金蝶的 applyFilterConditions：那条链路会顺带重建金蝶报文，MySQL 流程不需要
const syncMysqlVariables = () => {
  formData.value.filterMeta = buildFilterMeta()
}

const addMysqlVariable = () => {
  filterVariables.value.push(defaultFilterVariable())
  syncMysqlVariables()
}

const removeMysqlVariable = (index: number) => {
  filterVariables.value.splice(index, 1)
  syncMysqlVariables()
}

const onMysqlVariableEdited = (v: FilterVariable) => {
  v.result = undefined
  v.resultError = false
  v.elapsedMs = undefined
  syncMysqlVariables()
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
/** MySQL 源：正在解析抽取 SQL 的输出字段 */
const sourceColumnsLoading = ref(false)
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
  // 字段名限宽：金蝶标识很少超过 24 字符，留 260 足够，剩下的宽度让给中文名与列名
  { title: '字段名 (FieldKey)', dataIndex: 'fieldKey', width: 260 },
  { title: '中文名', dataIndex: 'label', width: 140 },
  // 云表数据项基本是中文，落库列名要能单独指定（也用于与数仓既有中文列名对齐）
  { title: '落库列名', dataIndex: 'column', width: 150 },
  // 180 才放得下 VARCHAR(255) 全称，132 会把下拉显示成 VARCHAR(...)
  { title: '类型', dataIndex: 'type', width: 170 },
  { title: '启用', dataIndex: 'enabled', width: 62, align: 'center' },
  { title: '操作', dataIndex: 'action', width: 140, align: 'center' }
]

/**
 * MySQL 源字段表：不提供「落库列名」。
 * 自动入库脚本按列名对齐临时表与目标表，改名会让该列对不上被跳过；
 * 需要改名请在 SQL 里用别名（AS xxx）解决。
 */
const mysqlFieldColumns: any[] = fieldColumns.filter((c) => c.dataIndex !== 'column')

// 字段名校验结果表
const fieldCheckColumns: any[] = [
  { title: '当前字段名', dataIndex: 'current', width: 220 },
  { title: '建议改为', dataIndex: 'suggestion', width: 220 },
  { title: '处理', dataIndex: 'action', width: 92, align: 'center' },
  { title: '说明', dataIndex: 'reason' },
  { title: '应用', dataIndex: 'checked', width: 64, align: 'center' }
]

const enabledFieldCount = computed(
  () => fieldRows.value.filter((r) => r.enabled && r.fieldKey.trim()).length
)

/** 字段标识含点号等特殊字符时，提示落库后的列名（发给金蝶仍是原名） */
const columnNameHint = (fieldKey: string) => {
  const raw = (fieldKey || '').trim()
  if (!raw) return ''
  const column = toColumnName(raw)
  return column && column !== raw ? column : ''
}

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

// 更改金蝶登录URL时，只替换主机前缀，服务路径按当前取数方式保留/纠正
const onKingdeeLoginUrlChange = () => {
  const host = extractHost(kingdeeConfig.value.loginUrl)
  const url = formData.value.apiUrl || ''
  if (host && url.includes('/k3cloud/')) {
    formData.value.apiUrl = `${host}${url.substring(url.indexOf('/k3cloud/'))}`
    syncQueryUrlToMode()
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

/** 标准查询模板 data 里会出现的键；出现别的（Model / SchemeId / IsVerifyBaseDataField…）说明是自定义报文 */
const STANDARD_BODY_KEYS = [
  'FormId',
  'FieldKeys',
  'FilterString',
  'OrderString',
  'TopRowCount',
  'Limit',
  'StartRow'
]

/**
 * 是否自定义报文：data 里带了标准模板之外的节点。
 *
 * <p>典型例子是 GetSysReportData 的 Model 报文——它同样有 data.FieldKeys，会被误判成标准查询；
 * 如果按标准模板重建，Model 整段会被冲掉，流程直接跑不起来。
 */
const isCustomBodyText = (text?: string) => {
  const raw = (text || '').trim()
  if (!raw.startsWith('{')) return false
  let root: any
  try {
    root = JSON.parse(raw)
  } catch (e) {
    return false
  }
  if (!root || typeof root !== 'object' || Array.isArray(root)) return false
  const d = root.data && typeof root.data === 'object' && !Array.isArray(root.data) ? root.data : null
  if (!d) return false
  return Object.keys(d).some((k) => !STANDARD_BODY_KEYS.includes(k))
}

/**
 * 自定义报文原位修补：只同步 FormId / FieldKeys / Limit / StartRow，
 * Model、SchemeId 等模板之外的节点原样保留。命中自定义报文时返回 true。
 */
const patchCustomBody = (): boolean => {
  const raw = (formData.value.apiBody || '').trim()
  if (!isCustomBodyText(raw)) return false
  let root: any
  try {
    root = JSON.parse(raw)
  } catch (e) {
    return false
  }
  const target = root.data && typeof root.data === 'object' ? root.data : root
  if ('FieldKeys' in target) target.FieldKeys = kingdeeQuery.value.fieldKeys || ''
  if ('Limit' in target) target.Limit = kingdeeQuery.value.limit || 2000
  if ('StartRow' in target) target.StartRow = 0
  if ('FormId' in target) {
    target.FormId = kingdeeQuery.value.formId || ''
  } else if ('FormId' in root) {
    root.FormId = kingdeeQuery.value.formId || ''
  }
  formData.value.apiBody = JSON.stringify(root, null, 2)
  bodySaveWarning.value = '自定义报文：已原位同步 FormId / FieldKeys / Limit，Model 等其余节点保持不变'
  return true
}

// 同步金蝶单据参数至 Body
// force=true 表示用户显式切换了取数方式，按新模板整段重建；否则自定义报文只做原位修补
const syncKingdeeQueryToBody = (force = false) => {
  // 自定义报文只做原位修补，不整段重建——否则 Model 之类的自有节点会被标准模板冲掉
  if (!force && patchCustomBody()) {
    return
  }
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
    syncPaginationDefaults(limit)
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

  // 查询接口地址跟随取数方式：单据 ExecuteBillQuery / 报表 GetReportData
  syncQueryUrlToMode()
}

// 由 FieldKeys 串重建表格行（保留已存在行的中文名/类型/启用/备注）
const rebuildFieldRows = () => {
  fieldRows.value = mergeRows(splitFieldKeys(kingdeeQuery.value.fieldKeys), fieldRows.value)
}

// 表格变更 -> 回写 FieldKeys -> 同步金蝶请求体
const applyFieldRows = () => {
  // MySQL 源的字段清单只落 field_meta，没有 FieldKeys / 金蝶报文的概念，不能反向污染
  if (formData.value.sourceType === 'MYSQL') {
    return
  }
  kingdeeQuery.value.fieldKeys = joinFieldKeys(fieldRows.value)
  // 云表的报文是 formJson，与字段清单无对应关系，套金蝶模板会把它冲掉
  if (isYunbiaoConn.value) {
    return
  }
  syncKingdeeQueryToBody()
}

const onFieldRowChanged = () => {
  applyFieldRows()
}

/** 批量启用/停用：导入的历史字段常是关闭的，逐个点太慢 */
const toggleAllFieldEnabled = (enabled: boolean) => {
  const rows = fieldRows.value.filter((r) => r.fieldKey.trim())
  if (!rows.length) {
    message.warning('还没有可切换的字段')
    return
  }
  rows.forEach((r) => {
    r.enabled = enabled
  })
  applyFieldRows()
  message.success(`已${enabled ? '启用' : '停用'} ${rows.length} 个字段`)
}

/**
 * MySQL 源：解析抽取 SQL 的输出字段。
 * 差量合并：按列名（忽略大小写）对齐——新增列追加、消失列删除、类型变化则更新，
 * 未变化的字段保留用户已改的类型/落库列名/备注/启用状态。
 */
const parseSourceColumns = async () => {
  const dsId = formData.value.sourceDsId
  if (!dsId) {
    message.warning('请先选择来源 MySQL 数据库')
    return
  }
  const sql = (formData.value.sourceQuerySql || '').trim()
  if (!sql) {
    message.warning('请先填写抽取 SQL 查询语句')
    return
  }
  sourceColumnsLoading.value = true
  try {
    const res = await describeSourceColumns({
      datasourceId: dsId,
      sql,
      pipelineId: currentId.value || undefined
    })
    const parsed = res.columns || []
    if (!parsed.length) {
      message.warning('这条 SQL 没有解析到任何列')
      return
    }

    const existing = new Map<string, FieldRow>()
    for (const row of fieldRows.value) {
      const key = row.fieldKey.trim().toLowerCase()
      if (key) existing.set(key, row)
    }

    let added = 0
    let updated = 0
    let unchanged = 0
    const merged: FieldRow[] = parsed.map((col) => {
      const key = col.name.trim().toLowerCase()
      const type = (col.type || 'VARCHAR(255)').toUpperCase()
      const old = existing.get(key)
      if (!old) {
        added += 1
        return createRow({ fieldKey: col.name, type, enabled: true })
      }
      existing.delete(key)
      if ((old.type || '').toUpperCase() !== type) {
        updated += 1
        return { ...old, type }
      }
      unchanged += 1
      return old
    })
    const removed = existing.size
    fieldRows.value = merged
    message.success(
      `解析完成：新增 ${added} 列，更新类型 ${updated} 列，删除 ${removed} 列，未变 ${unchanged} 列`
    )
    if (res.warnings && res.warnings.length) {
      message.warning(res.warnings.slice(0, 2).join('；'))
    }
  } catch (e: any) {
    // 错误已由请求拦截器统一提示
  } finally {
    sourceColumnsLoading.value = false
  }
}

/** 清空 MySQL 解析出来的字段清单 */
const clearSourceFields = () => {
  if (!fieldRows.value.length) return
  Modal.confirm({
    title: '清空抽取字段？',
    content: '第 4 步字段映射与「更新（新建）目标表」都会失去字段来源，需要重新解析。',
    okText: '清空',
    okType: 'danger',
    cancelText: '取消',
    onOk: () => {
      fieldRows.value = []
      message.success('字段清单已清空')
    }
  })
}

const onFieldKeysTextChange = () => {
  if (!isYunbiaoConn.value) {
    syncKingdeeQueryToBody()
  }
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

// ===== 字段名校验：用金蝶元数据抓 l/I、n/r 这类手抄错误 =====
interface FieldCheckRow {
  rid: string
  current: string
  suggestion: string
  reason: string
  /** RENAME=改成建议值；DISABLE=金蝶实测不认这个字段，需停用（不参与取数） */
  action: 'RENAME' | 'DISABLE'
  checked: boolean
}

const fieldCheckVisible = ref(false)
const fieldCheckLoading = ref(false)
const fieldCheckError = ref('')
const fieldCheckRows = ref<FieldCheckRow[]>([])
/** 存在性实测没跑成时的说明（只完成拼写核对） */
const fieldCheckProbeNote = ref('')

/** 点号后缀是手工补的基础资料属性，元数据里没有，只能按标准写法校验大小写 */
const K3_PROP_SUFFIXES = ['FNumber', 'FName', 'FCaption', 'FDataValue', 'FID', 'FGroup']

/** 易混字符归一：l/I/1 视为同一个，0/O 视为同一个，rn 与 m 视为同一个 */
const normalizeConfusable = (text: string) =>
  text
    .toLowerCase()
    .replace(/rn/g, 'm')
    .replace(/[li1]/g, 'i')
    .replace(/0/g, 'o')

/** 编辑距离：没有精确命中时找「最接近的字段」 */
const editDistance = (a: string, b: string) => {
  const m = a.length
  const n = b.length
  if (!m) return n
  if (!n) return m
  let prev = Array.from({ length: n + 1 }, (_, i) => i)
  for (let i = 1; i <= m; i++) {
    const cur: number[] = [i]
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(
        prev[j] + 1,
        cur[j - 1] + 1,
        prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)
      )
    }
    prev = cur
  }
  return prev[n]
}

const toggleAllFieldCheck = (checked: boolean) => {
  fieldCheckRows.value.forEach((row) => (row.checked = checked))
}

/** 校验结果分两类计数：能自动改名的 / 只能停用的 */
const fieldCheckRenameCount = computed(
  () => fieldCheckRows.value.filter((r) => r.action === 'RENAME').length
)
const fieldCheckDisableCount = computed(
  () => fieldCheckRows.value.filter((r) => r.action === 'DISABLE').length
)

/**
 * 用金蝶表单元数据核对字段清单：
 * 元数据只有字段标识（FSaleOrgId），点号后缀（.FNumber）是手工补的，
 * 所以头部按元数据比对，后缀只按标准写法校验大小写。
 */
const handleValidateFieldKeys = async () => {
  if (!kingdeeQuery.value.formId) {
    message.warning('请先填写业务对象表单标识 FormId')
    return
  }
  if (!kingdeeConfig.value.loginUrl) {
    message.warning('请先在上方填写金蝶登录接口地址')
    return
  }
  const rows = fieldRows.value.filter((r) => r.fieldKey.trim())
  if (!rows.length) {
    message.warning('字段清单为空，没有可校验的字段名')
    return
  }

  fieldCheckVisible.value = true
  fieldCheckLoading.value = true
  fieldCheckError.value = ''
  fieldCheckProbeNote.value = ''
  fieldCheckRows.value = []
  try {
    const list = await queryKingdeeFormFields({
      loginUrl: kingdeeConfig.value.loginUrl,
      acctID: kingdeeConfig.value.acctID,
      userName: kingdeeConfig.value.userName,
      password: effectivePassword() || undefined,
      lcid: kingdeeConfig.value.lcid,
      formId: kingdeeQuery.value.formId
    })
    if (!list || !list.length) {
      fieldCheckError.value = '金蝶未返回字段定义（报表类表单没有元数据），无法校验字段名'
      return
    }

    const validKeys = list.map((f) => (f.key || '').trim()).filter(Boolean)
    const canonical = new Map(validKeys.map((k) => [k.toUpperCase(), k]))
    const normalizedValid = validKeys.map((k) => ({ key: k, norm: normalizeConfusable(k) }))

    // 第二步：让金蝶用 ExecuteBillQuery 真查一次，逐个点名「元数据中标识为 X 的字段不存在」。
    // 只比元数据不够——QueryBusinessInfo 返回的字段并不完整，合法字段会被误判成不存在。
    const invalidSet = new Set<string>()
    let probeNote = ''
    try {
      const probe = await verifyKingdeeFieldKeys({
        loginUrl: kingdeeConfig.value.loginUrl,
        acctID: kingdeeConfig.value.acctID,
        userName: kingdeeConfig.value.userName,
        password: effectivePassword() || undefined,
        lcid: kingdeeConfig.value.lcid,
        formId: kingdeeQuery.value.formId,
        billQueryUrl: formData.value.apiUrl,
        fieldKeys: rows.map((r) => r.fieldKey.trim()).join(',')
      })
      ;(probe.invalid || []).forEach((k) => invalidSet.add(String(k).trim().toUpperCase()))
      if (probe.note) {
        probeNote = probe.note
      }
    } catch (e: any) {
      probeNote = `字段存在性实测未完成（${e?.message || '未知原因'}），以下只给拼写建议`
    }

    const problems: FieldCheckRow[] = []
    for (const row of rows) {
      const raw = row.fieldKey.trim()
      const dot = raw.indexOf('.')
      const head = dot >= 0 ? raw.slice(0, dot) : raw
      const suffix = dot >= 0 ? raw.slice(dot + 1) : ''
      let suggestion = ''
      let reason = ''

      // ① 金蝶实测不认：元数据里可能压根没这个标识，改名字也救不回来，只能停用
      if (invalidSet.has(raw.toUpperCase())) {
        problems.push({
          rid: row.rid,
          current: raw,
          suggestion: '',
          reason: '金蝶实测该字段不存在，运行时必报「字段不存在」；确认后可停用（保留行，不参与取数）',
          action: 'DISABLE',
          checked: true
        })
        continue
      }

      const canonHead = canonical.get(head.toUpperCase())
      if (!canonHead) {
        // 元数据里没有、但实测通过：子单据体/自定义字段常见（如 FMaterialName），不当作问题
        const normHead = normalizeConfusable(head)
        let best: string | null = null
        let bestDist = 99
        let confusable = false
        for (const item of normalizedValid) {
          if (item.norm === normHead) {
            best = item.key
            bestDist = 0
            confusable = true
            break
          }
          const dist = editDistance(normHead, item.norm)
          if (dist < bestDist) {
            bestDist = dist
            best = item.key
          }
        }
        if (best && bestDist <= 2) {
          suggestion = suffix ? `${best}.${suffix}` : best
          reason = confusable
            ? '字符混淆（l/I、1/I、0/O 之类）'
            : `疑似抄错，最接近 ${best}（差 ${bestDist} 个字符）`
        }
      } else {
        if (canonHead !== head) {
          suggestion = suffix ? `${canonHead}.${suffix}` : canonHead
          reason = '大小写不一致'
        }
        if (suffix) {
          const canonSuffix = K3_PROP_SUFFIXES.find(
            (s) => s.toLowerCase() === suffix.toLowerCase()
          )
          if (canonSuffix && canonSuffix !== suffix) {
            suggestion = `${canonHead}.${canonSuffix}`
            reason = reason
              ? `${reason}；后缀应为 ${canonSuffix}`
              : `后缀大小写应为 ${canonSuffix}`
          }
        }
      }

      if (suggestion && suggestion !== raw) {
        problems.push({ rid: row.rid, current: raw, suggestion, reason, action: 'RENAME', checked: true })
      }
    }

    fieldCheckProbeNote.value = probeNote
    fieldCheckRows.value = problems
    if (!problems.length) {
      fieldCheckVisible.value = false
      if (probeNote) {
        message.warning(`已核对 ${rows.length} 个字段，拼写一致；${probeNote}`)
      } else {
        message.success(`已核对 ${rows.length} 个字段，金蝶实测全部可用`)
      }
    }
  } catch (e: any) {
    fieldCheckError.value = e?.message || '校验失败，请检查登录配置'
  } finally {
    fieldCheckLoading.value = false
  }
}

const applyFieldCheck = () => {
  const picked = fieldCheckRows.value.filter((r) => r.checked)
  if (!picked.length) {
    message.warning('没有勾选要处理的字段')
    return
  }
  const byRid = new Map(picked.map((r) => [r.rid, r]))
  let renamed = 0
  let disabled = 0
  for (const row of fieldRows.value) {
    const hit = byRid.get(row.rid)
    if (!hit) continue
    if (hit.action === 'DISABLE') {
      // 停用而不是删行：保留中文名/类型，方便以后改名后直接启用
      if (row.enabled) {
        row.enabled = false
        disabled++
      }
    } else if (hit.suggestion && hit.suggestion !== row.fieldKey.trim()) {
      row.fieldKey = hit.suggestion
      renamed++
    }
  }
  applyFieldRows()
  fieldCheckVisible.value = false
  const parts: string[] = []
  if (renamed) parts.push(`修正 ${renamed} 个字段名`)
  if (disabled) parts.push(`停用 ${disabled} 个不存在的字段`)
  message.success(`${parts.join('，') || '没有变更'}；FieldKeys 与请求体已同步`)
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
    if (isYunbiaoConn.value) {
      // 云表：报文原样送去预览，套金蝶模板会把 formJson 冲掉
    } else if (formData.value.apiAuthType === 'KINGDEE_K3') {
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
/** 打开时的原始记录：编辑态「重置」就是回到这份配置 */
const originalRecord = ref<EtlPipeline>()

const open = async (record?: EtlPipeline, defaultFolderId?: string) => {
  originalRecord.value = record
  activeTab.value = '1'
  loginTestResult.value = null
  previewResult.value = null
  // 「直连免登」只是界面态：打开流程一律按库里有没有源连接来定，避免串上一条流程
  manualAuth.value = false

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
    } else if (!selectedApiConn.value) {
      // 老流程没存过鉴权配置时，套用已保存凭证，避免重复手填。
      // 选了云表连接的流程绝不能走这里——凭证里的金蝶 queryUrl 会把表单名称冲成 k3cloud 地址
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
      // 自定义报文（如 GetSysReportData 的 Model 报文）也按手工内容处理：
      // 它带 data.FieldKeys，会被识别成「标准查询」，不标记就会被保存时的模板重写
      if (record.apiBody.trim().startsWith('{') && (shape === 'UNKNOWN' || isCustomBodyText(record.apiBody))) {
        bodyManual.value = true
      }
    } else {
      fieldRows.value = []
    }
    // 中文名/类型/备注来自 field_meta（启用状态以 field_meta 里存的为准）
    if (record.fieldMeta) {
      // 云表报文没有 FieldKeys；MySQL 源也没有金蝶 FieldKeys。
      // kingdeeQuery 里残留的上一个金蝶流程字段会被当成「清单里没有的」追加到末尾，所以不能传
      const noFieldKeys = isYunbiaoConn.value || formData.value.sourceType === 'MYSQL'
      fieldRows.value = fieldRowsFromMeta(
        record.fieldMeta,
        noFieldKeys ? '' : kingdeeQuery.value.fieldKeys
      )
    }
    loadPagination()
    loadFilter()
    loadSyncConfig()
    loadYunbiaoFilter()
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
    paginationCustom.value = { step: false, endWhenLessThan: false }
    advancedActiveKeys.value = ['pagination']
    // 新建流程回到默认口径，避免沿用上一条流程的取数方式
    bodyMode.value = 'STANDARD'
    bodyModeSel.value = 'AUTO'
    detectResult.value = null
    detectError.value = ''
    syncKingdeeLoginBody()
    syncKingdeeQueryToBody()
    rebuildFieldRows()
    // 新建 MySQL 流程没有 FieldKeys，别把金蝶向导里的默认字段带进抽取清单
    if (formData.value.sourceType === 'MYSQL') {
      fieldRows.value = []
    }
    loadFilter()
    loadSyncConfig()
    loadYunbiaoFilter()
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

const handleReset = async () => {
  // 编辑态：放弃本次改动，重新载入库里已保存的配置
  if (isEdit.value && originalRecord.value) {
    await open(originalRecord.value)
    message.success('已恢复为上次保存的配置')
    return
  }
  if (!isEdit.value) {
    formData.value = initialFormData()
    kingdeeQuery.value = defaultKingdeeQuery()
    pagination.value = defaultPagination()
    paginationCustom.value = { step: false, endWhenLessThan: false }
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
    // 云表：表单名称不能是金蝶接口地址（历史脏数据就是这么写进去的）
    if (isYunbiaoConn.value && String(formData.value.apiUrl || '').includes('/k3cloud/')) {
      throw new Error('「表单名称」里是金蝶接口地址，请改成云表表单名称（如 表结合数采明细_IOT）')
    }
    // 服务地址的装配名分隔符纠正为逗号，避免保存后运行时报 "no found"
    formData.value.apiUrl = normalizeK3Url(formData.value.apiUrl || '')
    // 报文里出现 formJson 就是云表报文（金蝶没有这个字段）：一律按原文保存，
    // 不管 apiAuthType 是什么，都不能用金蝶模板覆盖它
    const yunbiaoBody = /"formJson"\s*:/.test(formData.value.apiBody || '')
    if (selectedApiConn.value || yunbiaoBody) {
      // 鉴权由连接提供：报文按用户填的原样保存。
      // 不能走下面的金蝶模板重建——云表的 formJson / Model 会被冲成标准报文
      formData.value.apiAuthConfig = ''
    } else if (formData.value.apiAuthType === 'KINGDEE_K3') {
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
    // MySQL 源：字段来自「解析 SQL 字段」，随流程落库，供映射/建表/临时表类型使用
    formData.value.fieldMeta = fieldRows.value.length ? fieldRowsToMetaJson(fieldRows.value) : ''
    // 变量定义 / 循环取数统一落在 filter_meta，保存时以当前面板值为准
    formData.value.filterMeta = buildFilterMeta()
  }
  // 增量同步配置统一由 syncConfig 序列化：旧格式（窗口天数）在打开时已折算成起点表达式，
  // 这里落库成新结构，避免下次又要再折算一次
  if (formData.value.writeMode === 'SYNC') {
    syncSyncConfig()
  }
}

/**
 * 保存成功后的收尾：
 * 1) 把当前表单值作为「重置」基线，否则重置会回到更早的旧值；
 * 2) 显式暂存时通知父级刷新列表/左树，避免返回后仍从旧行打开
 *    （静默暂存由「返回」时的刷新兜底，不重复请求）。
 */
const markSaved = (silent: boolean) => {
  originalRecord.value = { ...formData.value } as EtlPipeline
  if (!silent) emit('saved')
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
    markSaved(silent)
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
    // 全量保存同样要更新「重置」基线
    originalRecord.value = { ...formData.value } as EtlPipeline
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

/* 「请求体 Body」标签行：标题左、格式化按钮右 */
.body-form-item :deep(.ant-form-item-label > label) {
  width: 100%;
}
.body-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
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

/* MySQL 源：字段清单标题 + 解析按钮同一行 */
.field-list-label {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  line-height: 1.4;
}

/* 字段标识换算后的列名提示 */
.fk-col-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.4;
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

/* 校验结果里标红的问题字段名 */
.fk-bad {
  color: #cf1322;
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
  grid-template-columns: 1.4fr 1fr 40px 1.4fr 1fr 56px 110px;
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

/* 主键列：有钥匙才显示，其余留占位，保证各行对齐 */
.map-pk-cell {
  display: flex;
  align-items: center;
  justify-content: center;
}

.map-pk-head {
  text-align: center;
}

.map-na {
  color: #d9d9d9;
}

.map-pk {
  font-size: 13px;
  color: #d48806;
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

/* ===== 目标表结构变更弹窗：左右对照单表 ===== */
.sch-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding: 6px 10px;
  margin-bottom: 8px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  font-size: 12px;
  color: #8c8c8c;
}

.sch-summary b {
  font-weight: 600;
  color: #262626;
}

.sch-summary .is-warn b {
  color: #d46b08;
}

.sch-summary .is-new b {
  color: #237804;
}

.sch-summary .is-extra b {
  color: #cf1322;
}

.sch-summary .is-pk b {
  color: #185fa5;
}

.sch-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.sch-search {
  width: 200px;
}

.sch-sort {
  width: 118px;
}

.sch-toolbar-tip {
  font-size: 12px;
  color: #bfbfbf;
}

/* 唯一滚动区：弹窗高度就此封顶，不再随字段数线性增长 */
.sch-scroll {
  max-height: min(52vh, 460px);
  overflow: auto;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
}

.sch-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
  font-size: 12px;
}

.sch-table th {
  position: sticky;
  top: 0;
  z-index: 2;
  padding: 7px 6px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  font-weight: 600;
  color: #8c8c8c;
  text-align: left;
  white-space: nowrap;
}

.sch-table td {
  padding: 4px 6px;
  border-bottom: 1px solid #f5f5f5;
  vertical-align: middle;
}

.sch-table .c-check {
  width: 36px;
  padding-left: 10px;
}

.sch-table .c-act {
  width: 40px;
}

.sch-table .c-type {
  width: 104px;
}

.sch-table .c-arrow {
  width: 26px;
  text-align: center;
}

.sch-table .c-ttype {
  width: 158px;
}

.sch-table .c-pk {
  width: 46px;
  text-align: center;
}

.sch-table .c-status {
  width: 92px;
}

.sch-row:hover td {
  background: #fafafa;
}

.sch-row.is-type_diff td {
  background: #fffdf5;
}

.sch-row.is-missing_in_source td {
  background: #fff8f8;
}

.sch-mono {
  font-family: Consolas, Monaco, monospace;
}

.sch-na {
  color: #d9d9d9;
}

.sch-act {
  font-size: 12px;
  color: #bfbfbf;
}

.sch-act.is-type_diff {
  color: #d46b08;
}

.sch-act.is-missing_in_target {
  color: #237804;
}

.sch-act.is-missing_in_source {
  color: #cf1322;
}

.sch-pending {
  color: #bfbfbf;
  font-style: italic;
}

.sch-type-ro {
  color: #8c8c8c;
}

.sch-type-select {
  width: 100%;
}

.sch-arrow {
  color: #bfbfbf;
}

.sch-arrow.is-new {
  color: #237804;
}

.sch-arrow.is-extra {
  color: #cf1322;
}

.sch-pk {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: #d9d9d9;
  cursor: pointer;
}

.sch-pk:hover:enabled {
  background: #f0f5ff;
  color: #185fa5;
}

.sch-pk.is-on {
  background: #e6f1fb;
  border-color: #b5d4f4;
  color: #185fa5;
}

.sch-pk:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.sch-empty {
  padding: 24px;
  font-size: 12px;
  text-align: center;
  color: #bfbfbf;
}

.sch-plan {
  padding: 8px 0;
  font-size: 12px;
  color: #595959;
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
