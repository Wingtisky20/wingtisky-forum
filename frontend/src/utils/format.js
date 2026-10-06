/**
 * 后端返回的时间是 `2026-10-06T00:01:58` 这种形式（`LocalDateTime` 直接序列化）。
 * 直接显示会把那个 `T` 露在界面上。
 *
 * <p>**这里不做"几分钟前"那种相对时间**：那需要根据当前时间反复重算，
 * 而列表是不定时刷新的——不重算就会出现"5 分钟前"挂了一小时。
 * 它属于体验优化，M2 不做。
 */
export function formatTime(value) {
  if (!value) {
    return ''
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

/** 数字太大时收成 `1.2k`，免得卡片上的计数把标题挤变形。 */
export function formatCount(value) {
  const n = Number(value) || 0
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n)
}
