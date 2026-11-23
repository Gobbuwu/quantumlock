import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.jsx'
import { createMockApi } from '../api/mock.js'
import { describeCount, formatDate } from './format.js'
import { GLYPH_SIZE, idBytes, latticeGlyph } from './lattice.js'
import LatticeGlyph, { glyphTone } from './LatticeGlyph.jsx'

const ID_A = '2dcaa927-566f-4cd1-917d-c1ac308fc2d7'
const ID_B = '32a3376b-91e0-4b2c-a7d4-0e9f61b2c3a8'

describe('Lattice glyph drawing', () => {
  it('draws the same picture for the same key ID', () => {
    expect(latticeGlyph(ID_A)).toEqual(latticeGlyph(ID_A))
  })

  it('draws different pictures for different key IDs', () => {
    expect(latticeGlyph(ID_A).points).not.toEqual(latticeGlyph(ID_B).points)
  })

  it('keeps every point and line inside the drawing, whatever the ID', () => {
    const ids = [ID_A, ID_B, ...Array.from({ length: 200 }, () => crypto.randomUUID()), '', 'not-a-uuid', '🔑']
    for (const id of ids) {
      const { lines, points } = latticeGlyph(id)
      expect(points).toHaveLength(16)
      expect(lines).toHaveLength(24)
      for (const { x, y, large } of points) {
        const r = large ? 3 : 1.9
        expect(x - r).toBeGreaterThanOrEqual(0)
        expect(y - r).toBeGreaterThanOrEqual(0)
        expect(x + r).toBeLessThanOrEqual(GLYPH_SIZE)
        expect(y + r).toBeLessThanOrEqual(GLYPH_SIZE)
      }
    }
  })

  it('reads a UUID as its 16 bytes and hashes any other ID to 16 stable bytes', () => {
    expect(idBytes('00010203-0405-0607-0809-0a0b0c0d0e0f')).toEqual([...Array(16).keys()])
    expect(idBytes('key-1')).toHaveLength(16)
    expect(idBytes('key-1')).toEqual(idBytes('key-1'))
    expect(idBytes('key-1')).not.toEqual(idBytes('key-2'))
  })
})

describe('Lattice glyph component', () => {
  it('is hidden from screen readers and coloured by a class, not an inline style', () => {
    const { container } = render(<LatticeGlyph id={ID_A} tone="kem" />)
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('aria-hidden', 'true')
    expect(svg).toHaveClass('glyph', 'glyph-kem')
    expect(container.querySelector('[style]')).toBeNull()
  })

  it('takes its colour from the algorithm while the key is active, grey after that', () => {
    expect(glyphTone({ algorithm: 'ML-KEM-1024', status: 'active' })).toBe('kem')
    expect(glyphTone({ algorithm: 'ML-DSA-65', status: 'active' })).toBe('dsa')
    expect(glyphTone({ algorithm: 'ML-KEM-1024', status: 'rotated' })).toBe('inactive')
    expect(glyphTone({ algorithm: 'ML-DSA-65', status: 'revoked' })).toBe('inactive')
    expect(glyphTone({ algorithm: 'SLH-DSA-SHA2-128s', status: 'active' })).toBe('other')
  })
})

describe('Key list with glyphs', () => {
  it('shows a glyph beside each key ID and plays the settle animation only for the key just generated', async () => {
    const user = userEvent.setup()
    const { container } = render(<App api={createMockApi({ delayMs: 0 })} />)
    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('row')).toHaveLength(4) // header + 3 made-up keys
    expect(table.querySelectorAll('svg.glyph')).toHaveLength(3)
    expect(container.querySelector('.glyph-settle')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Generate key pair' }))
    await screen.findByText(/Generated ML-KEM-1024 key pair/)
    const firstRow = within(screen.getByRole('table')).getAllByRole('row')[1]
    expect(firstRow.querySelectorAll('.glyph-settle')).toHaveLength(1)
    expect(container.querySelectorAll('.glyph-settle')).toHaveLength(1)
    expect(screen.getByText('4 key pairs, 3 active')).toBeInTheDocument()
  })
})

describe('Formatting', () => {
  it('writes dates as local year-month-day and 24-hour time', () => {
    expect(formatDate(new Date(2026, 10, 23, 1, 4).toISOString())).toBe('2026-11-23 01:04')
    expect(formatDate('not a date')).toBe('not a date')
  })

  it('counts key pairs and active ones', () => {
    expect(describeCount([{ status: 'active' }])).toBe('1 key pair, 1 active')
    expect(describeCount([{ status: 'active' }, { status: 'rotated' }])).toBe('2 key pairs, 1 active')
  })
})
