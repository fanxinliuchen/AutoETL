/**
 * 抽取字段标识 -> 数仓列名（与后端 ColumnNaming 保持同一套规则）。
 *
 * 金蝶的 FieldKeys 允许带点：`FSaleOrgId.FNumber` 取基础资料属性，换算成 `FSaleOrgId_FNumber`。
 * 云表的数据项基本是中文：`公司页眉`、`压机日总产量` —— 数仓列名直接保留中文
 * （后端 `ColumnNaming` 用 `Character.isLetterOrDigit` 判断，前端必须同一套规则，
 * 否则第 4 步字段映射会全显示成 `_`、`_2`）。
 *
 * 规则：非字母/数字/下划线（Unicode 口径）-> 下划线；连续下划线折叠；空串兜底 col；
 * 数字开头补 c_；超过 64 截断；同批重名按顺序追加 _2、_3。
 */

const MAX_LENGTH = 64

export function toColumnName(fieldKey: string): string {
  const raw = (fieldKey || '').trim()
  if (!raw) return ''
  // \p{L}=任意语言的字母（含中文），\p{N}=数字 —— 与后端 Character.isLetterOrDigit 对齐
  let column = raw.replace(/[^\p{L}\p{N}_]/gu, '_').replace(/_+/g, '_')
  if (!column) column = 'col'
  if (/^\d/.test(column)) column = 'c_' + column
  return column.length > MAX_LENGTH ? column.slice(0, MAX_LENGTH) : column
}

/** 一批字段标识 -> 「标识 -> 列名」，按出现顺序去重 */
export function columnNameMap(fieldKeys: string[]): Map<string, string> {
  const map = new Map<string, string>()
  const used = new Map<string, number>()
  for (const key of fieldKeys || []) {
    const raw = (key || '').trim()
    if (!raw || map.has(raw)) continue
    const base = toColumnName(raw)
    const seq = (used.get(base.toLowerCase()) || 0) + 1
    used.set(base.toLowerCase(), seq)
    map.set(raw, seq > 1 ? `${base}_${seq}` : base)
  }
  return map
}
