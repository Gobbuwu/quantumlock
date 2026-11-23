/** First 8 characters of a key pair id, which is enough to tell keys apart on screen. */
export function shortId(id) {
  return id.slice(0, 8)
}

// The Swedish locale writes the ISO order (2026-11-23 01:04) in local time: same width on every row, easy to compare.
const dateFormat = new Intl.DateTimeFormat('sv-SE', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

export function formatDate(iso) {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? String(iso) : dateFormat.format(date)
}

/** "3 key pairs, 2 active" for the list header. */
export function describeCount(keys) {
  const active = keys.filter((key) => key.status === 'active').length
  return `${keys.length} key ${keys.length === 1 ? 'pair' : 'pairs'}, ${active} active`
}
