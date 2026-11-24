import request from './request'
import type { EtlDatasource } from '../types'

export function getDatasourceList(): Promise<EtlDatasource[]> {
  return request.post('/etl/datasource/list', {})
}

/** 连接管理列表：后端解密出「连接目标 + 账号」，密码 / AppKey 不出 */
export interface ConnectionSummary {
  id: string
  dsName: string
  connType?: 'MYSQL' | 'POSTGRESQL' | 'KINGDEE_K3' | 'IYUNBIAO'
  status?: number
  remark?: string
  connectionTarget: string
  account: string
  /** JDBC 类连接回传的原始配置，用于「编辑」回显（不含密码） */
  host?: string
  port?: number
  databaseName?: string
  encoding?: string
  driverClass?: string
  jdbcUrl?: string
}

export function getConnectionList(): Promise<ConnectionSummary[]> {
  return request.post('/etl/datasource/connections', {})
}

export function saveDatasource(data: Partial<EtlDatasource>): Promise<boolean> {
  return request.post('/etl/datasource', data)
}

export function updateDatasource(id: string, data: Partial<EtlDatasource>): Promise<boolean> {
  return request.put(`/etl/datasource/${id}/update`, data)
}

export function deleteDatasource(id: string): Promise<boolean> {
  return request.delete(`/etl/datasource/${id}/del`)
}

/** 读取连接配置：密码 / AppKey 已打码，其余原样 */
export function getDatasourceConfig(
  id: string
): Promise<{ connType: string; config: Record<string, any> }> {
  return request.get(`/etl/datasource/${id}/config`)
}

export function testDatasourceConnection(data: {
  id?: string
  connType?: string
  configJson?: string
  driverClass?: string
  host?: string
  port?: number
  databaseName?: string
  encoding?: string
  jdbcUrl?: string
  username?: string
  password?: string
}): Promise<string> {
  return request.post('/etl/datasource/test-connection', data)
}
