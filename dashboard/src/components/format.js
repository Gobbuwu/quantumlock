/** First 8 characters of a key pair id, which is enough to tell keys apart on screen. */
export function shortId(id) {
  return id.slice(0, 8)
}

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function formatDate(iso) {
  return dateFormat.format(new Date(iso))
}
