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
