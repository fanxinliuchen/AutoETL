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

export function queryKingdeeFormFields(data: {
  loginUrl: string
  acctID?: string
  userName?: string
  password?: string
  lcid?: number
  formId: string
}): Promise<KingdeeFieldMeta[]> {
  return request.post('/etl/pipeline/webapi/form-fields', data)
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

/** 变量 SQL 试算：在目标数据源上执行一次取值表达式，不落库 */
export function evaluateVariable(data: {
  // 雪花 ID 超出 JS 安全整数范围，必须按字符串传，避免精度丢失
  targetDsId: number | string
  name?: string
  sql: string
}): Promise<VarEvalResult> {
  return request.post('/etl/pipeline/webapi/eval-var', data)
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
