import axios from 'axios'
import { message } from 'ant-design-vue'
import type { R } from '../types'

const service = axios.create({
  baseURL: '',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

service.interceptors.response.use(
  (response) => {
    const res: R = response.data
    // 团队统一规范: code === 0 为成功
    if (res.code === 0) {
      return res.data
    } else {
      message.error(res.message || '请求处理失败')
      return Promise.reject(new Error(res.message || 'Error'))
    }
  },
  (error) => {
    const msg = error.response?.data?.message || error.message || '网络连接或服务异常'
    message.error(msg)
    return Promise.reject(error)
  }
)

export default service
