/** Minor units -> display amount, e.g. money(123456, 2, 'EGP') = 'EGP 1,234.56'. */
export function money (minor, exponent = 2, currency = '') {
  if (minor === null || minor === undefined) return '—'
  const n = Number(minor) / 10 ** exponent
  const s = n.toLocaleString(undefined, { minimumFractionDigits: exponent, maximumFractionDigits: exponent })
  return currency ? `${currency} ${s}` : s
}

export const toMajor = (minor, exponent = 2) => minor == null ? null : Number(minor) / 10 ** exponent
export const toMinor = (major, exponent = 2) => major == null || major === '' ? null : Math.round(Number(major) * 10 ** exponent)

/** Card expiry YYMM -> MM/YY. */
export const expiry = yymm => yymm && yymm.length === 4 ? `${yymm.slice(2)}/${yymm.slice(0, 2)}` : (yymm || '—')

export function dateTime (iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export function date (iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' })
}

/** PENDING_PRINT -> Pending print */
/** Codes whose generated label reads badly. */
const LABELS = {
  TXN_APPROVED: 'Transaction approved', TXN_DECLINED: 'Transaction declined', OTP: 'One-time password',
  CONFIRMED_FRAUD: 'Fraud confirmed', FALSE_POSITIVE: 'Cleared (genuine)', ECOM: 'E-commerce', ATM: 'ATM', POS: 'POS'
}
export const label = code => code ? LABELS[code] || code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, ' ') : ''

const COLORS = {
  OPEN: 'warning',
  CONFIRMED_FRAUD: 'negative',
  FALSE_POSITIVE: 'positive',
  ACTIVE: 'positive',
  PENDING_PRINT: 'warning',
  PRINTED: 'info',
  SUSPENDED: 'orange-8',
  BLOCKED: 'orange-8',
  DEBIT_BLOCKED: 'orange-7',
  PIN_BLOCKED: 'orange-8',
  LOST: 'negative',
  STOLEN: 'negative',
  CLOSED: 'grey-7',
  CANCELLED: 'grey-7',
  EXPIRED: 'grey-7'
}
export const statusColor = s => COLORS[s] || 'grey-6'

/** Verb shown on a status-change action. */
const VERBS = {
  ACTIVE: 'Reactivate',
  SUSPENDED: 'Suspend',
  CLOSED: 'Close',
  BLOCKED: 'Block',
  DEBIT_BLOCKED: 'Block debits',
  LOST: 'Report lost',
  STOLEN: 'Report stolen',
  CANCELLED: 'Cancel card'
}
export const verb = s => VERBS[s] || label(s)

const ICONS = {
  ACTIVE: 'check_circle',
  SUSPENDED: 'pause_circle',
  CLOSED: 'lock',
  BLOCKED: 'block',
  DEBIT_BLOCKED: 'remove_circle',
  LOST: 'help',
  STOLEN: 'report',
  CANCELLED: 'cancel'
}
export const statusIcon = s => ICONS[s] || 'swap_horiz'
