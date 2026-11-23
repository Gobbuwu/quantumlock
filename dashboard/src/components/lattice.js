/**
 * The lattice glyph of a key pair (KAN-28): a small picture that makes keys easy to tell apart, like SSH randomart.
 * A skewed 4 x 4 lattice whose points are pushed a little off their grid spots, a nod to the small "error" that
 * ML-KEM and ML-DSA hide in their lattices. It is drawn from the public key pair ID only, never from key material,
 * and it does not verify a key.
 */

export const GLYPH_SIZE = 40
const N = 4 // points per row and column
// Distance between grid spots. 7 keeps the widest skew plus the largest error and dot inside the box (tested).
const CELL = 7
const ERROR = 1.6 // how far a point may sit off its spot, in each direction

/**
 * 16 bytes from the ID. A UUID gives its own bytes; any other string is hashed (FNV-1a), so an unexpected ID
 * from a newer API still draws a stable picture instead of failing.
 */
export function idBytes(id) {
  const hex = String(id ?? '').replace(/-/g, '')
  if (/^[0-9a-f]{32}$/i.test(hex)) {
    return Array.from({ length: 16 }, (_, i) => parseInt(hex.slice(i * 2, i * 2 + 2), 16))
  }
  const bytes = []
  let hash = 0x811c9dc5
  for (let round = 0; bytes.length < 16; round++) {
    for (const char of `${round}:${id}`) {
      hash ^= char.codePointAt(0)
      hash = Math.imul(hash, 0x01000193) >>> 0
    }
    bytes.push(hash & 0xff, (hash >>> 8) & 0xff, (hash >>> 16) & 0xff, hash >>> 24)
  }
  return bytes.slice(0, 16)
}

const round2 = (value) => Math.round(value * 100) / 100

/**
 * The glyph's drawing in a 40 x 40 box: `lines` join the grid spots, `points` are the shifted lattice points.
 * The same ID always gives the same drawing.
 */
export function latticeGlyph(id) {
  const b = idBytes(id)
  const skew = 0.25 + (b[0] / 255) * 0.5 // the second basis vector leans by a quarter to three quarters of a cell
  const span = (N - 1) * CELL
  const left = (GLYPH_SIZE - span - span * skew * 0.5) / 2
  const top = (GLYPH_SIZE - span) / 2

  const spots = []
  const points = []
  for (let row = 0; row < N; row++) {
    for (let col = 0; col < N; col++) {
      const k = row * N + col
      const x = left + col * CELL + (N - 1 - row) * CELL * skew * 0.5
      const y = top + row * CELL
      const error = b[(k + 1) % 16] ^ b[(k * 7 + 3) % 16]
      spots.push([x, y])
      points.push({
        x: round2(x + ((error & 15) / 15 - 0.5) * ERROR * 2),
        y: round2(y + ((error >> 4) / 15 - 0.5) * ERROR * 2),
        large: (b[k] & 3) === 0,
      })
    }
  }

  const lines = []
  for (let row = 0; row < N; row++) {
    for (let col = 0; col < N; col++) {
      const k = row * N + col
      const [x1, y1] = spots[k].map(round2)
      if (col < N - 1) lines.push({ x1, y1, x2: round2(spots[k + 1][0]), y2: round2(spots[k + 1][1]) })
      if (row < N - 1) lines.push({ x1, y1, x2: round2(spots[k + N][0]), y2: round2(spots[k + N][1]) })
    }
  }
  return { lines, points }
}
