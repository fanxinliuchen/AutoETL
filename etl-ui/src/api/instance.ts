import request from './request'
import type { CommonQueryRequestDTO, EtlTaskInstance, PageResult } from '../types'

export function getInstancePage(data: CommonQueryRequestDTO): Promise<PageResult<EtlTaskInstance>> {
  return request.post('/etl/instance/page', data)
}

export function getInstanceDetail(id: string): Promise<EtlTaskInstance> {
  return request.get(`/etl/instance/${id}/detail`)
}
