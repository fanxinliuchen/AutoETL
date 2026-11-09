import request from './request'
import type { EtlDatasource } from '../types'

export function getDatasourceList(): Promise<EtlDatasource[]> {
  return request.post('/etl/datasource/list', {})
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

export function testDatasourceConnection(data: {
  id?: string
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
