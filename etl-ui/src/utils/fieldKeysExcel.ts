import * as XLSX from 'xlsx'

/**
 * 抽取字段清单 - Excel 导入 / 导出 / 模板 工具
 * 仅在前端处理，不依赖后端：最终产物仍是 api_body.data.FieldKeys 逗号串。
 */

export interface FieldRow {
  rid: string
  fieldKey: string
  label: string
  type: string
  enabled: boolean
  remark: string
}

export interface ParseResult {
  rows: FieldRow[]
  errors: string[]
  warnings: string[]
}

/** 预留类型列（P0 只记录不生效，P1 才会参与临时表建表） */
export const FIELD_TYPE_OPTIONS = [
  'TEXT',
  'VARCHAR(255)',
  'BIGINT',
  'INT',
  'DECIMAL(18,4)',
  'DECIMAL(18,2)',
  'DATE',
  'DATETIME'
]

/** 金蝶字段标识：字母或下划线开头，允许字母/数字/下划线/点（如 FSupplierId.FNumber） */
const FIELD_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_.]*$/

const HEADERS = ['序号', '字段名', '中文名', '类型', '启用', '备注']

let ridSeed = 0

export function createRow(patch: Partial<FieldRow> = {}): FieldRow {
  ridSeed += 1
  return {
    rid: `fr_${ridSeed}`,
    fieldKey: '',
    label: '',
    // 默认 VARCHAR(255)：TEXT 列无法建索引、临时表体积也大，绝大多数金蝶字段 255 足够
    type: 'VARCHAR(255)',
    enabled: true,
    remark: '',
    ...patch
  }
}

/** 逗号串 -> 字段名数组 */
export function splitFieldKeys(raw?: string | null): string[] {
  if (!raw) return []
  return String(raw)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 字段行 -> 逗号串（只取启用且字段名非空的行，顺序即列序） */
export function joinFieldKeys(rows: FieldRow[]): string {
  return rows
    .filter((r) => r.enabled && r.fieldKey.trim())
    .map((r) => r.fieldKey.trim())
    .join(',')
}

/** 按新的字段名列表重建行，尽量保留已有行的中文名/类型/启用/备注 */
export function mergeRows(keys: string[], prev: FieldRow[] = []): FieldRow[] {
  const byKey = new Map<string, FieldRow>()
  prev.forEach((r) => {
    const k = r.fieldKey.trim().toUpperCase()
    if (k && !byKey.has(k)) byKey.set(k, r)
  })
  return keys.map((k) => {
    const hit = byKey.get(k.trim().toUpperCase())
    return hit ? { ...hit, fieldKey: k } : createRow({ fieldKey: k })
  })
}

function stamp(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
}

function safeName(s?: string): string {
  return (s || '未指定').replace(/[\\/:*?"<>|]/g, '_')
}

/** 导出当前清单为 xlsx */
export function exportFieldRows(rows: FieldRow[], formId?: string) {
  const aoa: any[][] = [
    HEADERS,
    ...rows.map((r, i) => [
      i + 1,
      r.fieldKey,
      r.label || '',
      r.type || '',
      r.enabled ? '是' : '否',
      r.remark || ''
    ])
  ]
  const ws = XLSX.utils.aoa_to_sheet(aoa)
  ws['!cols'] = [{ wch: 6 }, { wch: 34 }, { wch: 20 }, { wch: 16 }, { wch: 8 }, { wch: 30 }]
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '字段清单')
  XLSX.writeFile(wb, `抽取字段_${safeName(formId)}_${stamp()}.xlsx`)
}

/** 下载空白模板（含示例行与填写说明） */
export function downloadFieldTemplate() {
  const sheet: any[][] = [
    HEADERS,
    [1, 'FID', '单据内码', 'BIGINT', '是', '单据主键，建议保留'],
    [2, 'FBillNo', '单据编号', 'VARCHAR(255)', '是', ''],
    [3, 'FSupplierId.FNumber', '供应商编码', 'VARCHAR(255)', '是', '基础资料字段用点号取子字段'],
    [4, 'FRemarks', '备注', 'TEXT', '否', '示例：暂不启用']
  ]
  const ws = XLSX.utils.aoa_to_sheet(sheet)
  ws['!cols'] = [{ wch: 6 }, { wch: 34 }, { wch: 20 }, { wch: 16 }, { wch: 8 }, { wch: 30 }]

  const notes: any[][] = [
    ['填写说明'],
    ['1. 只需填写「字段名」列，其余列可留空。'],
    ['2. 一行一个字段；行顺序就是 FieldKeys 顺序，也是临时表的列顺序。'],
    ['3. 字段名只允许字母、数字、下划线和点，不能含空格、逗号或中文。'],
    ['4. 「启用」填否的行不进入 FieldKeys，但会保留在清单里，可随时恢复。'],
    ['5. 「类型」留空默认 VARCHAR(255)；可选 TEXT / BIGINT / INT / DECIMAL(n,m) / DATE / DATETIME。TEXT 只建议给超长文本，它不能建索引、临时表也更大。'],
    ['6. 重复字段名只保留第一条，重复行会给出提示。'],
    ['7. 表头行可保留，也可删除；系统按表头名称识别各列。']
  ]
  const wsNotes = XLSX.utils.aoa_to_sheet(notes)
  wsNotes['!cols'] = [{ wch: 78 }]

  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '字段清单')
  XLSX.utils.book_append_sheet(wb, wsNotes, '填写说明')
  XLSX.writeFile(wb, '抽取字段清单_模板.xlsx')
}

/**
 * 按字段名推断临时表类型（与后端 KingdeeMetadataClient.inferTypeByKey 保持同一口径）
 * 用于元数据缺失时的兜底，以及「推断类型」批量填充
 */
export function inferFieldType(fieldKey: string): string {
  const key = (fieldKey || '').toUpperCase()
  if (!key) return 'VARCHAR(255)'
  const leaf = key.includes('.') ? key.slice(key.lastIndexOf('.') + 1) : key
  if (leaf.endsWith('ID') || leaf === 'FID') return 'BIGINT'
  // 日期统一 DATETIME：金蝶返回 2026-11-09T10:30:00，落 DATE 列会静默丢掉时分秒
  if (leaf.includes('DATE') || leaf.includes('TIME')) return 'DATETIME'
  // 金蝶布尔字段用 FIsXxx 命名（含税/赠品等），须排在金额规则之前
  if (
    leaf.startsWith('FIS') ||
    leaf.startsWith('IS') ||
    leaf.includes('INCLUDED') ||
    leaf.includes('EXCLUDED')
  ) {
    return 'VARCHAR(16)'
  }
  if (
    ['AMOUNT', 'PRICE', 'QTY', 'RATE', 'TAX', 'COST', 'WEIGHT', 'VOLUME', 'BALANCE'].some((t) =>
      leaf.includes(t)
    )
  ) {
    return 'DECIMAL(18,4)'
  }
  if (leaf.includes('COUNT') || leaf.includes('SEQ')) return 'INT'
  if (leaf.includes('STATUS') || leaf.includes('FLAG') || leaf.includes('ENABLE')) return 'VARCHAR(32)'
  return 'VARCHAR(255)'
}

/** 字段行 -> field_meta JSON（随流程持久化，供后端解析临时表列类型） */export function fieldRowsToMetaJson(rows: FieldRow[]): string {
  return JSON.stringify({
    fields: rows.map((r) => ({
      k: r.fieldKey.trim(),
      label: r.label || '',
      type: r.type || 'VARCHAR(255)',
      enabled: !!r.enabled,
      remark: r.remark || ''
    }))
  })
}

/**
 * field_meta JSON + FieldKeys -> 字段行
 * 启用状态以 FieldKeys 为准（它才真正参与抽取），meta 提供中文名/类型/备注与行顺序
 */
export function fieldRowsFromMeta(metaJson: string | undefined, fieldKeys: string): FieldRow[] {
  const keys = splitFieldKeys(fieldKeys)
  const keySet = new Set(keys.map((k) => k.toUpperCase()))

  let metaFields: any[] = []
  if (metaJson) {
    try {
      const parsed = JSON.parse(metaJson)
      if (Array.isArray(parsed?.fields)) metaFields = parsed.fields
    } catch (e) {
      metaFields = []
    }
  }
  if (!metaFields.length) return mergeRows(keys, [])

  const rows: FieldRow[] = []
  const seen = new Set<string>()
  metaFields.forEach((f) => {
    const key = String(f?.k ?? '').trim()
    if (!key) return
    const upper = key.toUpperCase()
    if (seen.has(upper)) return
    seen.add(upper)
    rows.push(
      createRow({
        fieldKey: key,
        label: String(f?.label ?? '').slice(0, 64),
        type: String(f?.type ?? 'VARCHAR(255)').toUpperCase() || 'VARCHAR(255)',
        enabled: keySet.has(upper),
        remark: String(f?.remark ?? '').slice(0, 128)
      })
    )
  })

  keys.forEach((key) => {
    const upper = key.toUpperCase()
    if (seen.has(upper)) return
    seen.add(upper)
    rows.push(createRow({ fieldKey: key, enabled: true }))
  })

  return rows
}

function normCell(v: any): string {
  if (v === null || v === undefined) return ''
  return String(v).trim()
}

function normHeader(v: any): string {
  return normCell(v)
    .replace(/[\s()（）:：]/g, '')
    .toLowerCase()
}

const ALIASES = {
  key: ['字段名', '字段', '字段标识', 'fieldkey', 'field_key', 'key'],
  label: ['中文名', '字段名称', '名称', '描述', 'caption', 'label', 'name'],
  type: ['类型', '数据类型', 'type', 'datatype'],
  enabled: ['启用', '是否启用', 'enabled'],
  remark: ['备注', '说明', 'remark', 'note', 'comment']
}

function findCol(normHeaders: string[], aliases: string[]): number {
  for (const a of aliases) {
    const i = normHeaders.indexOf(a)
    if (i >= 0) return i
  }
  for (const a of aliases) {
    const i = normHeaders.findIndex((h) => h.includes(a))
    if (i >= 0) return i
  }
  return -1
}

function parseEnabled(v: any): boolean {
  const s = normCell(v).toLowerCase()
  if (!s) return true
  if (['否', 'n', 'no', 'false', '0', '停用', '禁用'].includes(s)) return false
  return true
}

/** 解析二维表（Excel sheet 或粘贴内容都走这里） */
export function parseMatrix(aoa: any[][]): ParseResult {
  const errors: string[] = []
  const warnings: string[] = []
  const rows: FieldRow[] = []

  const hasContent = (r: any[]) => Array.isArray(r) && r.some((c) => normCell(c) !== '')
  if (!aoa || !aoa.some(hasContent)) {
    return { rows, errors: ['文件中没有可识别的数据'], warnings }
  }

  let headerIdx = -1
  let cols = { key: 0, label: -1, type: -1, enabled: -1, remark: -1 }
  const scanLimit = Math.min(aoa.length, 5)
  for (let i = 0; i < scanLimit; i++) {
    if (!hasContent(aoa[i])) continue
    const nh = aoa[i].map(normHeader)
    const k = findCol(nh, ALIASES.key)
    if (k >= 0) {
      headerIdx = i
      cols = {
        key: k,
        label: findCol(nh, ALIASES.label),
        type: findCol(nh, ALIASES.type),
        enabled: findCol(nh, ALIASES.enabled),
        remark: findCol(nh, ALIASES.remark)
      }
      break
    }
  }

  if (headerIdx < 0) {
    const first = aoa.find(hasContent) || []
    const c0 = normCell(first[0])
    const c1 = normCell(first[1])
    if (/^\d+$/.test(c0) && FIELD_KEY_PATTERN.test(c1)) {
      cols.key = 1
      cols.label = 2
    } else {
      cols.key = 0
    }
    warnings.push('未识别到表头，已按「第一列（或第二列）= 字段名」解析')
  }

  const startIdx = headerIdx >= 0 ? headerIdx + 1 : 0
  const seen = new Set<string>()

  for (let i = startIdx; i < aoa.length; i++) {
    const row = aoa[i]
    if (!hasContent(row)) continue
    const rowNo = i + 1

    const rawKey = normCell(row[cols.key])
    if (!rawKey) {
      const filledOther = [cols.label, cols.type, cols.remark].some(
        (c) => c >= 0 && normCell(row[c])
      )
      if (filledOther) warnings.push(`第 ${rowNo} 行：缺少字段名，已跳过`)
      continue
    }

    const key = rawKey
      .replace(/，/g, ',')
      .replace(/["'`]/g, '')
      .trim()

    if (key.includes(',')) {
      errors.push(`第 ${rowNo} 行：「${key}」含逗号，请一行只填一个字段`)
      continue
    }
    if (!FIELD_KEY_PATTERN.test(key)) {
      errors.push(`第 ${rowNo} 行：「${key}」含非法字符，只允许字母、数字、下划线和点`)
      continue
    }
    const upper = key.toUpperCase()
    if (seen.has(upper)) {
      warnings.push(`第 ${rowNo} 行：「${key}」与前面的字段重复，已忽略`)
      continue
    }
    seen.add(upper)

    let type = normCell(cols.type >= 0 ? row[cols.type] : '').toUpperCase()
    if (type && !FIELD_TYPE_OPTIONS.includes(type)) {
      warnings.push(`第 ${rowNo} 行：类型「${type}」不在推荐列表，已置为 VARCHAR(255)`)
      type = 'VARCHAR(255)'
    }

    rows.push(
      createRow({
        fieldKey: key,
        label: normCell(cols.label >= 0 ? row[cols.label] : '').slice(0, 64),
        type: type || 'VARCHAR(255)',
        enabled: cols.enabled >= 0 ? parseEnabled(row[cols.enabled]) : true,
        remark: normCell(cols.remark >= 0 ? row[cols.remark] : '').slice(0, 128)
      })
    )
  }

  return { rows, errors, warnings }
}

/** 解析上传的 xlsx / xls */
export async function parseFieldExcel(file: File): Promise<ParseResult> {
  try {
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf, { type: 'array' })
    const firstSheet = wb.SheetNames.find((n) => n.includes('字段')) || wb.SheetNames[0]
    const ws = wb.Sheets[firstSheet]
    if (!ws) return { rows: [], errors: ['未找到可读取的工作表'], warnings: [] }
    const aoa = XLSX.utils.sheet_to_json<any[]>(ws, {
      header: 1,
      defval: '',
      blankrows: false
    }) as any[][]
    return parseMatrix(aoa)
  } catch (e: any) {
    return { rows: [], errors: [`Excel 解析失败：${e?.message || e}`], warnings: [] }
  }
}

/** 解析粘贴文本：单行按逗号拆，多行按「字段名[制表符/逗号]中文名」拆 */
export function parseFieldPaste(text: string): ParseResult {
  const t = (text || '').replace(/\r\n/g, '\n').trim()
  if (!t) return { rows: [], errors: ['粘贴内容为空'], warnings: [] }

  const lines = t.split('\n').map((l) => l.trim()).filter(Boolean)
  const aoa: any[][] = []

  if (lines.length === 1) {
    aoa.push(['字段名'])
    t.split(/[,，]/)
      .map((s) => s.trim())
      .filter(Boolean)
      .forEach((k) => aoa.push([k]))
  } else {
    aoa.push(['字段名', '中文名'])
    lines.forEach((line) => {
      const parts = line.split(/[\t,，]/).map((s) => s.trim())
      aoa.push([parts[0] || '', parts[1] || ''])
    })
  }
  return parseMatrix(aoa)
}
