import { useMemo } from 'react'
import { ALGORITHMS } from './algorithms.js'
import { GLYPH_SIZE, latticeGlyph } from './lattice.js'

/** Colour of a key's glyph: its algorithm while active, grey once rotated or revoked or when the algorithm is new. */
export function glyphTone(keyPair) {
  if (keyPair.status !== 'active') return 'inactive'
  return ALGORITHMS.find((option) => option.name === keyPair.algorithm)?.tone ?? 'other'
}

/**
 * Draws the lattice glyph of a key pair ID (see lattice.js). Decorative: screen readers skip it, because the key ID
 * and algorithm next to it carry the same information in words. Colours come from the stylesheet (CSP: no inline
 * styles); `settle` plays the one-time animation for a key that was just generated.
 */
export default function LatticeGlyph({ id, tone = 'other', size = GLYPH_SIZE, settle = false, className = '' }) {
  const { lines, points } = useMemo(() => latticeGlyph(id), [id])
  const classes = ['glyph', `glyph-${tone}`, settle && 'glyph-settle', className].filter(Boolean).join(' ')
  return (
    <svg
      className={classes}
      width={size}
      height={size}
      viewBox={`0 0 ${GLYPH_SIZE} ${GLYPH_SIZE}`}
      aria-hidden="true"
      focusable="false"
    >
      <g className="glyph-lines" stroke="currentColor" strokeWidth="0.8">
        {lines.map((line, i) => (
          <line key={i} {...line} />
        ))}
      </g>
      <g className="glyph-points" fill="currentColor">
        {points.map((point, i) => (
          <circle key={i} cx={point.x} cy={point.y} r={point.large ? 3 : 1.9} />
        ))}
      </g>
    </svg>
  )
}
