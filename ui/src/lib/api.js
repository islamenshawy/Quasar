import { Notify } from 'quasar'
import { session } from './session.js'

export class ApiError extends Error {
  constructor (status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

/**
 * Calls cms-core. Business errors ({code, message}) are shown as a notification unless
 * `quiet` is set, and always rethrown so callers can stop.
 */
async function request (method, path, body, { quiet = false } = {}) {
  let res
  try {
    res = await fetch('/api' + path, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-Operator': session.operator || 'unknown' },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (e) {
    const err = new ApiError(0, 'NETWORK', 'Cannot reach the CMS server')
    if (!quiet) Notify.create({ type: 'negative', message: err.message })
    throw err
  }
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  if (!res.ok) {
    const err = new ApiError(res.status, data?.code || 'HTTP_' + res.status,
      data?.message || data?.error || res.statusText || 'Request failed')
    if (!quiet) Notify.create({ type: 'negative', message: err.message, caption: err.code })
    throw err
  }
  return data
}

export const api = {
  get: (p, o) => request('GET', p, undefined, o),
  post: (p, b, o) => request('POST', p, b ?? {}, o),
  put: (p, b, o) => request('PUT', p, b, o)
}

/** Query string from an object, skipping empty values. */
export function qs (params) {
  const u = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') u.set(k, v)
  }
  const s = u.toString()
  return s ? '?' + s : ''
}
