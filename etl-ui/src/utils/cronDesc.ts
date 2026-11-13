/**
 * Cron 表达式中文释义。
 *
 * 本项目后端用 Spring 的 CronExpression 校验，语义按实测（spring-context 6.2.7）：
 * 6 段 = 秒 分 时 日 月 周（7 段多一个「年」）；
 * 周的数字 0 和 7 = 周日，1 = 周一 … 6 = 周六（实测 3 → WEDNESDAY，7 → SUNDAY）。
 *
 * 只覆盖常见写法：识别不出来的（月限定、日与周同时限定、无法解析的步长等）
 * 返回空字符串，界面降级为只显示原表达式，不猜。
 */

const DAY_NAMES: Record<string, string> = {
  SUN: '周日',
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
  '0': '周日',
  '1': '周一',
  '2': '周二',
  '3': '周三',
  '4': '周四',
  '5': '周五',
  '6': '周六',
  '7': '周日'
}

/** 单值数字；'*'、'?'、区间、列表、步长都返回 null */
function num(field: string): number | null {
  return /^\d+$/.test(field) ? Number(field) : null
}

function isAny(field: string): boolean {
  return field === '*' || field === '?'
}

// 步长写法：通配符或数字 + "/n"，例如每 5 分钟写成 0/5 或 * 加 /5
function stepOf(field: string): number | null {
  const m = /^(?:\*|\d+)\/(\d+)$/.exec(field)
  return m ? Number(m[1]) : null
}

/** '8-18' 取区间端点 */
function rangeOf(field: string): [number, number] | null {
  const m = /^(\d+)-(\d+)$/.exec(field)
  return m ? [Number(m[1]), Number(m[2])] : null
}

/** '8,20' 取数字列表；含非数字返回 null */
function numberList(field: string): number[] | null {
  const nums = field.split(',').map(num)
  return nums.every(n => n !== null) ? (nums as number[]) : null
}

function periodOf(hour: number): string {
  if (hour <= 4) return '凌晨'
  if (hour <= 8) return '早上'
  if (hour <= 11) return '上午'
  if (hour === 12) return '中午'
  if (hour <= 17) return '下午'
  return '晚上'
}

/** 8:00 -> 早上8点；14:30 -> 下午2点30分；0:00 -> 零点 */
function clockText(hour: number, minute: number, second: number): string {
  if (hour === 0 && minute === 0 && second === 0) return '零点'
  const display = hour <= 12 ? hour : hour - 12
  let text = `${periodOf(hour)}${display}点`
  if (minute > 0) text += `${minute}分`
  if (second > 0) text += `${second}秒`
  return text
}

function weekdayName(token: string): string | null {
  return DAY_NAMES[token.toUpperCase()] || null
}

/** MON-FRI -> 周一至周五；1,3 -> 周一、周三 */
function describeWeek(field: string): string {
  if (field.includes('-')) {
    const [from, to] = field.split('-')
    const a = weekdayName(from)
    const b = weekdayName(to)
    return a && b ? `${a}至${b}` : ''
  }
  const names = field.split(',').map(weekdayName)
  return names.every(Boolean) ? names.join('、') : ''
}

// 1 -> 1日；1,15 -> 1日、15日；步长写法（0/n）-> 每n天
function describeMonthDay(field: string): string {
  const step = stepOf(field)
  if (step) return `每${step}天`
  const range = rangeOf(field)
  if (range) return `${range[0]}日到${range[1]}日`
  const list = numberList(field)
  return list ? list.map(d => `${d}日`).join('、') : ''
}

/**
 * 只描述时间维度。
 * 返回值可能自带「每」，调用方需按情况决定是否再加「每天」前缀。
 */
function describeClock(hour: string, minute: string, second: string): string {
  const m = num(minute)
  const s = num(second)
  const h = num(hour)
  const seconds = s !== null && s > 0 ? `${s}秒` : ''

  // 固定时刻：8:00、14:30
  if (h !== null && m !== null && s !== null) {
    return clockText(h, m, s)
  }

  // 小时列表：8,20 -> 早上8点、晚上8点
  if (m !== null && s !== null) {
    const hours = numberList(hour)
    if (hours && hours.length > 0) {
      return hours.map(x => clockText(x, m, s)).join('、')
    }
  }

  // 小时区间：8-18
  if (m !== null && s !== null) {
    const range = rangeOf(hour)
    if (range) {
      const tail = m === 0 && s === 0 ? '每小时整点' : `每小时第${m}分`
      return `${range[0]}点到${range[1]}点之间${tail}`
    }
  }

  // 小时步长：*/2、0/2
  const hourStep = stepOf(hour)
  if (hourStep && m !== null && s !== null) {
    return `每${hourStep}小时` + (m === 0 ? '整点' : `第${m}分`)
  }

  if (isAny(hour)) {
    // 分钟步长：*/5
    const minuteStep = stepOf(minute)
    if (minuteStep) return `每${minuteStep}分钟${seconds}`

    // 全通配：按秒步长或每分钟
    if (isAny(minute)) {
      const secondStep = stepOf(second)
      if (secondStep) return `每${secondStep}秒`
      return '每分钟'
    }

    // 固定分钟：每小时整点 / 每小时第15分
    if (m !== null) {
      if (m === 0) return `每小时整点${seconds}`
      return `每小时第${m}分${seconds}`
    }
  }

  return ''
}

/**
 * 把 Spring / Quartz 风格 cron 表达式翻成中文，识别不了返回空串。
 * 例：0 0 8 * * ? -> 每天早上8点
 */
export function describeCron(expr?: string | null): string {
  if (!expr) return ''
  const fields = expr.trim().replace(/\s+/g, ' ').split(' ')
  // 兼容 5 段 crontab（无秒），补一个 0 秒
  const normalized = fields.length === 5 ? ['0', ...fields] : fields
  if (normalized.length !== 6 && normalized.length !== 7) return ''

  const [second, minute, hour, dom, month, dow] = normalized
  if (!second || !minute || !hour || !dom || !month || !dow) return ''
  // 月份限定（如每年 3 月）不做猜测，避免释义与真实触发时间不符
  if (!isAny(month)) return ''

  const domAny = isAny(dom)
  const dowAny = isAny(dow)

  // 日与周同时限定，组合含义太灵活，不猜
  if (!domAny && !dowAny) return ''

  // 按周：每周一凌晨1点 / 每周一至周五上午9点
  if (!dowAny) {
    const week = describeWeek(dow)
    const clock = describeClock(hour, minute, second)
    return week && clock ? `每${week}${clock}` : ''
  }

  // 按月：每月1日零点
  if (!domAny) {
    const day = describeMonthDay(dom)
    const clock = describeClock(hour, minute, second)
    return day && clock ? `每月${day}${clock}` : ''
  }

  const clock = describeClock(hour, minute, second)
  if (!clock) return ''
  // 已是「每5分钟」「每小时整点」这类表达，不再加「每天」
  if (isAny(hour) || stepOf(hour)) return clock
  return `每天${clock}`
}
