/** Text and Base64 helpers for signing. Nothing here keeps a copy of what it converts. */

const encoder = new TextEncoder()

/** Size of the text in bytes once encoded as UTF-8, which is what the API receives. */
export function utf8Length(text) {
  return encoder.encode(text).length
}

/** The text's UTF-8 bytes as standard Base64. */
export function utf8ToBase64(text) {
  return bytesToBase64(encoder.encode(text))
}

export function bytesToBase64(bytes) {
  let binary = ''
  // In slices, so a 32 KiB message does not overflow the argument list.
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return btoa(binary)
}

/** Standard Base64 with padding, as the API sends it, so a pasted signature can be checked before it is sent. */
export function isBase64(text) {
  return text.length > 0 && text.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(text)
}
