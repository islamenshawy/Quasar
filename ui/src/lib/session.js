import { reactive } from 'vue'

// TEST ONLY: the operator id is sent as X-Operator until real authentication lands (CMS-060).
const KEY = 'cms.operator'

function read () {
  try { return localStorage.getItem(KEY) || '' } catch { return '' }
}

export const session = reactive({ operator: read() })

export function setOperator (id) {
  session.operator = (id || '').trim()
  try { localStorage.setItem(KEY, session.operator) } catch { /* private mode */ }
}
