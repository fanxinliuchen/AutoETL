import request from './request'
import type { EtlFolder } from '../types'

export function getFolderTree(): Promise<any[]> {
  return request.get('/etl/folder/tree')
}

export function saveFolder(data: Partial<EtlFolder>): Promise<boolean> {
  return request.post('/etl/folder', data)
}

export function updateFolder(id: string, data: Partial<EtlFolder>): Promise<boolean> {
  return request.put(`/etl/folder/${id}/update`, data)
}

export function deleteFolder(id: string): Promise<boolean> {
  return request.delete(`/etl/folder/${id}/del`)
}
