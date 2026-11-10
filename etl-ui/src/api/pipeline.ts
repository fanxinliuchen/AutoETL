import request from './request'
import type { CommonQueryRequestDTO, EtlPipeline, PageResult } from '../types'

export function getPipelinePage(data: CommonQueryRequestDTO): Promise<PageResult<EtlPipeline>> {
  return request.post('/etl/pipeline/page', data)
}

export function savePipeline(data: Partial<EtlPipeline>): Promise<boolean> {
  return request.post('/etl/pipeline', data)
}

export function saveStepPipeline(data: Partial<EtlPipeline>): Promise<EtlPipeline> {
  return request.post('/etl/pipeline/save-step', data)
}

export function updatePipeline(id: string, data: Partial<EtlPipeline>): Promise<boolean> {
  return request.put(`/etl/pipeline/${id}/update`, data)
}

export function deletePipeline(id: string): Promise<boolean> {
  return request.delete(`/etl/pipeline/${id}/del`)
}

export function getPipelineDetail(id: string): Promise<EtlPipeline> {
  return request.get(`/etl/pipeline/${id}/detail`)
}

export function runPipeline(id: string): Promise<void> {
  return request.post(`/etl/pipeline/${id}/run`)
}

export function togglePipelineSchedule(id: string, enabled: boolean): Promise<void> {
  return request.put(`/etl/pipeline/${id}/schedule/toggle?enabled=${enabled}`)
}

export function cancelPipeline(id: string): Promise<boolean> {
  return request.post(`/etl/pipeline/${id}/cancel`)
}

export function validateSql(sql: string): Promise<string> {
  return request.post('/etl/pipeline/sql/validate', { sql })
}

export function testWebapiLogin(data: { authType: string; authConfig: string }): Promise<any> {
  return request.post('/etl/pipeline/webapi/test-login', data)
}

export function previewWebapiExtract(data: Partial<EtlPipeline>): Promise<{ total: number; records: any[]; log: string }> {
  return request.post('/etl/pipeline/webapi/preview', data)
}

export interface KingdeeFieldMeta {
  key: string
  label: string
  type: string
  rawType?: string
}

/** 金蝶元数据类接口的公共入参（登录信息 + 表单标识） */
export interface KingdeeFormQueryParams {
  loginUrl: string
  acctID?: string
  userName?: string
  password?: string
  lcid?: number
  formId: string
}

export function queryKingdeeFormFields(data: KingdeeFormQueryParams): Promise<KingdeeFieldMeta[]> {
  // 失败原因在「从金蝶拉取字段」弹窗内就地展示，这里不再弹全局提示
  return request.post('/etl/pipeline/webapi/form-fields', data, { silent: true })
}

/** 表单类型识别结果：BILL=单据（标准查询），REPORT=报表（parameters 方案） */
export interface FormTypeDetectResult {
  formId: string
  type: 'BILL' | 'REPORT'
  fieldCount: number
  reason: string
}

/**
 * 识别表单属于单据还是报表。
 * 判据是 QueryBusinessInfo 能否吐出字段定义，而不是 FormId 的命名。
 */
export function detectKingdeeForm(data: KingdeeFormQueryParams): Promise<FormTypeDetectResult> {
  // 识别失败在「取数方式」旁边就地提示，不弹全局错误
  return request.post('/etl/pipeline/webapi/detect-form', data, { silent: true })
}

/** 报表字段推断结果：报表没有列定义接口，只能试取 1 行按 key 反推 */
export interface ReportFieldDetectResult {
  fields: KingdeeFieldMeta[]
  fieldCount: number
  totalCount: number
  message: string
}

/**
 * 报表方案：用 GetReportData 试取 1 行推断字段。
 * FieldKeys 留空时金蝶按过滤方案返回全部列，行对象的 key 就是字段标识。
 */
export function detectKingdeeReportFields(data: {
  loginUrl: string
  acctID?: string
  userName?: string
  password?: string
  lcid?: number
  formId: string
  schemeId: string
  /** 报表取数接口地址；留空由登录地址推导，点号装配名会被后端纠正为逗号 */
  reportUrl?: string
  /** 试取时一并带上当前条件，方案需要日期/组织才能出数时很有用 */
  quicklyConditions?: Record<string, unknown>[]
  moreConditions?: Record<string, unknown>[]
}): Promise<ReportFieldDetectResult> {
  return request.post('/etl/pipeline/webapi/report-fields', data, { silent: true })
}

export interface KingdeeCredentialMasked {
  loginUrl?: string
  queryUrl?: string
  acctID?: string
  userName?: string
  lcid?: number
  hasPassword?: boolean
  updateTime?: string
}

export function saveKingdeeCredential(data: {
  loginUrl: string
  queryUrl?: string
  acctID?: string
  userName?: string
  password?: string
  lcid?: number
}): Promise<KingdeeCredentialMasked> {
  return request.post('/etl/kingdee-credential/save', data)
}

export function getKingdeeCredentialDefault(): Promise<KingdeeCredentialMasked | null> {
  return request.get('/etl/kingdee-credential/default')
}

export interface VarEvalResult {
  name: string
  value: string
  elapsedMs: number
}

export interface LoopEvalResult {
  columns: string[]
  rowCount: number
  rows: Record<string, string>[]
}

/** 循环数据集试算：返回行数与预览，不落库 */
export function evaluateLoop(data: {
  targetDsId: number | string
  sql: string
  limit?: number
}): Promise<LoopEvalResult> {
  return request.post('/etl/pipeline/webapi/eval-loop', data)
}

/** 变量 SQL 试算：在目标数据源上执行一次取值表达式，不落库 */
export function evaluateVariable(data: {
  // 雪花 ID 超出 JS 安全整数范围，必须按字符串传，避免精度丢失
  targetDsId: number | string
  name?: string
  sql: string
}): Promise<VarEvalResult> {
  // 试算失败时在变量行下方就地显示红色原因，不再叠加全局提示
  return request.post('/etl/pipeline/webapi/eval-var', data, { silent: true })
}

// ===== 目标表结构 =====

export interface TargetTableColumn {
  name: string
  columnType: string
  nullable: boolean
  defaultValue?: string
  comment?: string
  primaryKey: boolean
  autoIncrement: boolean
}

export interface SchemaApplyResult {
  table: string
  created: boolean
  statements: number
  executedSql: string[]
}

/** 目标库中的表清单 */
export function listTargetTables(datasourceId: number | string): Promise<string[]> {
  return request.post('/etl/datasource/tables', { datasourceId })
}

/** 目标表结构；表不存在返回空数组 */
export function describeTargetTable(
  datasourceId: number | string,
  table: string
): Promise<TargetTableColumn[]> {
  return request.post('/etl/datasource/table-columns', { datasourceId, table })
}

/** 按映射结果变更目标表结构 */
export function applyTargetSchema(data: {
  datasourceId: number | string
  table: string
  addColumns: { name: string; type: string; comment?: string }[]
  dropColumns: string[]
  primaryKeys: string[]
  dropPrimaryKey: boolean
}): Promise<SchemaApplyResult> {
  return request.post('/etl/datasource/apply-schema', data)
}
