/**
 * What the dashboard offers for a key pair, mirroring the API's rules (KAN-30). The API still decides: a button
 * shown here can still be refused, for example when the grace period ends while the page is open.
 *
 * active: everything its algorithm can do. rotated: only verify (and decapsulate) until the grace period ends,
 * and revoke. revoked: nothing.
 */
export function allowedActions(key, now = Date.now()) {
  const signing = key.algorithm === 'ML-DSA-65'
  const inGrace =
    key.status === 'rotated' && key.gracePeriodEndsAt != null && now < new Date(key.gracePeriodEndsAt).getTime()
  return {
    sign: signing && key.status === 'active',
    verify: signing && (key.status === 'active' || inGrace),
    rotate: key.status === 'active',
    revoke: key.status !== 'revoked',
    inGrace,
  }
}

/** Limits of the API (FIPS 204 and the request size limit). */
export const MAX_MESSAGE_BYTES = 32 * 1024
export const MAX_CONTEXT_BYTES = 255
export const MAX_GRACE_DAYS = 90
export const DEFAULT_GRACE_DAYS = 7
