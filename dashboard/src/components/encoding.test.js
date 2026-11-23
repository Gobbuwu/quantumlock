import { describe, expect, it } from 'vitest'
import { isBase64, utf8Length, utf8ToBase64 } from './encoding.js'

describe('encoding', () => {
  it('encodes text as UTF-8, then Base64', () => {
    expect(utf8ToBase64('hi')).toBe('aGk=')
    expect(utf8ToBase64('€')).toBe('4oKs')
    expect(utf8Length('€')).toBe(3)
    expect(utf8Length('')).toBe(0)
  })

  it('handles a full 32 KiB message', () => {
    const text = 'a'.repeat(32 * 1024)
    expect(atob(utf8ToBase64(text))).toBe(text)
  })

  it.each([
    ['aGk=', true],
    ['4oKs', true],
    ['', false],
    ['aGk', false],
    ['aG k=', false],
    ['aGk=====', false],
    ['aG-_', false],
  ])('isBase64(%j) is %s', (text, expected) => {
    expect(isBase64(text)).toBe(expected)
  })
})
