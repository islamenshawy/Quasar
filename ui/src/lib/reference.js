import { reactive } from 'vue'
import { api } from './api.js'

/** Active reference data for forms, cached; call reloadReference() after a setup change. */
export const reference = reactive({
  loaded: false,
  segments: [],
  accountTypes: [],
  currencies: [],
  products: [],
  customerTypes: [],
  cifSource: 'EITHER',
  customerStatuses: [],
  accountStatuses: [],
  cardStatuses: []
})

let pending = null

export function loadReference (force = false) {
  if (reference.loaded && !force) return Promise.resolve(reference)
  if (!pending) {
    pending = api.get('/admin/reference')
      .then(r => { Object.assign(reference, r, { loaded: true }); return reference })
      .finally(() => { pending = null })
  }
  return pending
}

export const reloadReference = () => loadReference(true)

export const options = (list, label = x => `${x.code} – ${x.name}`) =>
  list.map(x => ({ label: label(x), value: x.code }))
