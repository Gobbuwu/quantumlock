// tone names the colour of the algorithm's lattice glyphs in styles.css.
export const ALGORITHMS = [
  {
    name: 'ML-KEM-1024',
    use: 'Key exchange',
    detail: 'Agree on a shared secret with another party.',
    standard: 'FIPS 203',
    tone: 'kem',
  },
  {
    name: 'ML-DSA-65',
    use: 'Signatures',
    detail: 'Sign data and let others verify it.',
    standard: 'FIPS 204',
    tone: 'dsa',
  },
]
