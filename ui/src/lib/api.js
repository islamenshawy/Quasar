import { Notify } from 'quasar'
import { clearUser } from './session.js'

export class ApiError extends Error {
  constructor (status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

// set by the router so an expired session / forced password change can redirect without a circular import
let onAuthProblem = () => {}
export function setAuthProblemHandler (fn) {
  onAuthProblem = fn
}

function csrfToken () {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : null
}

/**
 * Calls cms-core with the session cookie and the CSRF token.
 * - business errors ({code, message}) are shown unless `quiet`, then rethrown
 * - 401 / PASSWORD_CHANGE_REQUIRED hand over to the router (sign-in / change password)
 * - 202 means the action went to maker-checker: shown, and returned as { approvalPending: true, requestId }
 */
async function request (method, path, body, { quiet = false } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = csrfToken()
  if (token && method !== 'GET') headers['X-XSRF-TOKEN'] = token
  let res
  try {
    res = await fetch('/api' + path, {
      method,
      headers,
      credentials: 'same-origin',
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

  if (res.status === 401 && !path.startsWith('/auth/')) {
    clearUser()
    onAuthProblem('signin')
    throw new ApiError(401, 'UNAUTHENTICATED', 'Sign in required')
  }
  if (res.status === 403 && data?.code === 'PASSWORD_CHANGE_REQUIRED') {
    onAuthProblem('password')
    throw new ApiError(403, data.code, data.message)
  }
  if (!res.ok) {
    const err = new ApiError(res.status, data?.code || 'HTTP_' + res.status,
      data?.message || data?.error || res.statusText || 'Request failed')
    if (!quiet) Notify.create({ type: 'negative', message: err.message, caption: err.code })
    throw err
  }
  if (res.status === 202 && data?.approvalPending) {
    Notify.create({
      type: 'info', icon: 'how_to_reg', timeout: 5000,
      message: 'Sent for approval', caption: `Request #${data.requestId}: a second supervisor must approve it`
    })
  }
  return data
}

export const api = {
  get: (p, o) => request('GET', p, undefined, o),
  post: (p, b, o) => request('POST', p, b ?? {}, o),
  put: (p, b, o) => request('PUT', p, b, o),
  del: (p, o) => request('DELETE', p, undefined, o)
}

/** True when the response is a maker-checker request rather than the result. */
export const pending = r => !!(r && r.approvalPending)

/** Query string from an object, skipping empty values. */
export function qs (params) {
  const u = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') u.set(k, v)
  }
  const s = u.toString()
  return s ? '?' + s : ''
}
