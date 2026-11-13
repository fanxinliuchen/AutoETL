import request from './request'
import type { EtlFolder } from '../types'

/**
 * 左侧树形目录
 * @param withPipelines true 时目录下会挂流程叶子节点（标题=流程名称），
 *                      仅左侧工作台使用；选择「所属业务目录」时保持 false。
 */
export function getFolderTree(withPipelines = false): Promise<any[]> {
  return request.get('/etl/folder/tree', { params: { withPipelines } })
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
