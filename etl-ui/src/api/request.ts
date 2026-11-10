import axios from 'axios'
import { message } from 'ant-design-vue'
import type { R } from '../types'

// 允许单个请求关闭全局错误提示：错误已在页面/弹窗内就地展示时，避免同一条错误弹两次
declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean
  }
}

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
      if (!response.config.silent) {
        message.error(res.message || '请求处理失败')
      }
      return Promise.reject(new Error(res.message || 'Error'))
    }
  },
  (error) => {
    const msg = error.response?.data?.message || error.message || '网络连接或服务异常'
    if (!error.config?.silent) {
      message.error(msg)
    }
    // 统一把后端文案透出给调用方，页面内展示时不会只看到 "status code 500"
    return Promise.reject(new Error(msg))
  }
)

export default service
