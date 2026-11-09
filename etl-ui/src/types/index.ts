// 统一响应信封 (规范 07 §2)
export interface R<T = any> {
  code: number
  message: string
  data: T
  timestamp: number
}

// 分页信封 (规范 07 §4.2)
export interface PageResult<T = any> {
  list: T[]
  total: number
  page: number
  size: number
}

// 统一动态查询入参
export interface CommonQueryRequestDTO {
  current?: number
  size?: number
  query?: {
    filters?: Array<{
      fieldName: string
      condition?: string
      value: string
    }>
    softs?: Array<{
      fieldName: string
      orderCode?: 'asc' | 'desc'
    }>
  }
}

// 目录实体
export interface EtlFolder {
  id?: string
  parentId?: string
  folderName: string
  folderSort?: number
}

// 数据源实体
export interface EtlDatasource {
  id?: string
  dsName: string
  dsType: 'MYSQL' | 'MYSQL_DW'
  driverClass?: string
  host?: string
  port?: number
  databaseName?: string
  encoding?: string
  jdbcUrl?: string
  username: string
  passwordCipher?: string
  maxPoolSize?: number
  status?: number
  remark?: string
}

// 流程实体
export interface EtlPipeline {
  id?: string
  folderId?: string
  pipelineCode: string
  pipelineName: string
  description?: string
  sourceType: 'MYSQL' | 'WEB_API'
  sourceDsId?: string
  sourceQuerySql?: string
  apiAuthType?: 'NONE' | 'KINGDEE_K3' | 'CUSTOM_LOGIN'
  apiAuthConfig?: string
  apiUrl?: string
  apiMethod?: 'GET' | 'POST'
  apiHeaders?: string
  apiBody?: string
  apiDataPath?: string
  apiPagination?: string
  transformSql: string
  targetDsId?: string
  targetTable: string
  writeMode: 'APPEND' | 'OVERWRITE' | 'UPSERT'
  upsertKeys?: string
  fieldMeta?: string
  cronExpr?: string
  scheduleStatus?: number
  timeoutSeconds?: number
  maxRetryCount?: number
  alertConfigId?: string
  lastRunStatus?: string
  lastRunTime?: string
  createTime?: string
}

// 任务实例实体
export interface EtlTaskInstance {
  id?: string
  pipelineId?: string
  pipelineCode?: string
  pipelineName?: string
  triggerType?: 'MANUAL' | 'CRON'
  taskStatus: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'CANCELED'
  startTime?: string
  endTime?: string
  durationMs?: number
  readCount?: number
  writeCount?: number
  retryCount?: number
  errorMessage?: string
  logDetail?: string
}

// 告警配置实体
export interface EtlAlertConfig {
  id?: string
  channelName: string
  channelType: 'WECHAT_WORK' | 'DINGTALK' | 'FEISHU' | 'EMAIL'
  webhookUrl: string
  secretToken?: string
  emailReceivers?: string
  status?: number
}
