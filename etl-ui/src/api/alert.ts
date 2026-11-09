import request from './request'
import type { EtlAlertConfig } from '../types'

export function getAlertConfigList(): Promise<EtlAlertConfig[]> {
  return request.post('/etl/alert/list', {})
}

export function saveAlertConfig(data: Partial<EtlAlertConfig>): Promise<boolean> {
  return request.post('/etl/alert', data)
}

export function updateAlertConfig(id: string, data: Partial<EtlAlertConfig>): Promise<boolean> {
  return request.put(`/etl/alert/${id}/update`, data)
}

export function deleteAlertConfig(id: string): Promise<boolean> {
  return request.delete(`/etl/alert/${id}/del`)
}
