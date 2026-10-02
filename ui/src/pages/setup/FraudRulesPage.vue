<template>
  <q-page padding class="page">
    <PageHeader title="Fraud rules" subtitle="Screened on every card transaction after the PIN check. Matching rules add their score; the strongest action wins.">
      <template #actions>
        <q-btn v-if="can.supervise" unelevated no-caps color="primary" icon="add" label="Add rule" @click="edit(null)" />
      </template>
    </PageHeader>

    <q-banner rounded class="bg-indigo-1 text-dark q-mb-md">
      <template #avatar><q-icon name="shield" color="primary" /></template>
      <b>Alert</b> records the case for the fraud desk and lets the transaction continue.
      <b>Decline</b> answers 102 (suspected fraud). <b>Decline and block</b> also blocks the card.
      A total score of <b class="mono">{{ declineScore }}</b> or more declines even when every rule only alerts
      (Setup → Numbering &amp; settings). Advices from the switch are scored but never declined.
    </q-banner>

    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" row-key="code" :loading="loading" class="clickable-rows"
               :pagination="{ rowsPerPage: 50 }" :row-class="r => r.active ? '' : 'inactive-row'" @row-click="(e, r) => edit(r)">
        <template #body-cell-name="p">
          <q-td :props="p">
            <div class="text-weight-medium">{{ p.row.name }}</div>
            <div class="text-caption muted ellipsis" style="max-width: 420px">{{ describe(p.row.conditions) }}</div>
          </q-td>
        </template>
        <template #body-cell-action="p">
          <q-td :props="p"><q-badge :color="actionColor(p.value)" :label="actionLabel(p.value)" /></q-td>
        </template>
        <template #body-cell-active="p">
          <q-td :props="p"><q-badge :color="p.value ? 'positive' : 'grey-6'" :label="p.value ? 'Active' : 'Off'" /></q-td>
        </template>
      </q-table>
    </q-card>

    <q-dialog v-model="dialog" persistent>
      <q-card style="width: 860px; max-width: 96vw">
        <q-form @submit="save">
          <q-card-section class="row items-center">
            <div class="text-h6">{{ editing ? `Rule ${editing.code}` : 'New fraud rule' }}</div>
            <q-space />
            <q-btn flat round dense icon="close" v-close-popup aria-label="Close" />
          </q-card-section>
          <q-card-section class="q-pt-none">
            <div class="row q-col-gutter-md">
              <q-input v-model="form.code" class="col-12 col-sm-4" outlined dense label="Code *" :readonly="!!editing" maxlength="32" input-class="mono"
                       :rules="[v => /^[A-Z0-9_]{2,32}$/.test(v || '') || 'A-Z, 0-9, _']" @update:model-value="v => form.code = (v || '').toUpperCase()" />
              <q-input v-model="form.name" class="col-12 col-sm-8" outlined dense label="Name *" maxlength="80" :rules="[v => !!v || 'Required']" />
              <q-input v-model="form.description" class="col-12" outlined dense label="Description" maxlength="256" />
              <q-select v-model="form.action" class="col-12 col-sm-4" outlined dense label="Action *" emit-value map-options :options="actionOptions" />
              <q-input v-model.number="form.score" class="col-6 col-sm-3" outlined dense type="number" label="Score" :rules="[v => (v >= 0 && v <= 1000) || '0-1000']" />
              <q-input v-model.number="form.priority" class="col-6 col-sm-3" outlined dense type="number" label="Order" hint="Lower first" />
              <q-toggle v-model="form.active" class="col-12 col-sm-2" label="Active" />
            </div>

            <div class="text-subtitle2 q-mt-md q-mb-sm">Conditions <span class="text-caption muted">— all that you fill must match; leave blank to ignore</span></div>
            <div class="row q-col-gutter-md">
              <q-select v-model="c.types" class="col-12 col-sm-4" outlined dense multiple use-chips emit-value map-options clearable label="Transaction types" :options="typeOptions" />
              <q-select v-model="c.channels" class="col-12 col-sm-4" outlined dense multiple use-chips clearable label="Channels" :options="['ATM', 'POS', 'ECOM']" />
              <q-select v-model="c.products" class="col-12 col-sm-4" outlined dense multiple use-chips clearable label="Card products" :options="productOptions" />
              <q-input v-model.number="c.minAmount" class="col-6 col-sm-3" outlined dense type="number" step="any" label="Amount from" hint="Major units" />
              <q-input v-model.number="c.maxAmount" class="col-6 col-sm-3" outlined dense type="number" step="any" label="Amount up to" />
              <q-input v-model.number="c.hourFrom" class="col-6 col-sm-3" outlined dense type="number" label="From hour" hint="0-23, local" />
              <q-input v-model.number="c.hourTo" class="col-6 col-sm-3" outlined dense type="number" label="Before hour" hint="Wraps midnight" />
              <q-input v-model="c.mccIn" class="col-12 col-sm-6" outlined dense label="Merchant categories (MCC)" hint="Comma separated, e.g. 7995, 4829" input-class="mono" />
              <q-input v-model="c.mccNotIn" class="col-12 col-sm-6" outlined dense label="Except MCC" input-class="mono" />
              <q-input v-model="c.countryIn" class="col-12 col-sm-4" outlined dense label="Acquirer countries" hint="ISO numeric, e.g. 840, 784" input-class="mono" />
              <q-input v-model="c.countryNotIn" class="col-12 col-sm-4" outlined dense label="Except countries" input-class="mono" />
              <q-select v-model="c.foreign" class="col-12 col-sm-4" outlined dense emit-value map-options clearable label="Domestic / foreign"
                        :options="[{ label: 'Foreign only', value: true }, { label: 'Domestic only', value: false }]" />
              <q-toggle v-model="c.newCountry" class="col-12 col-sm-6" label="First use in a country (last 90 days)" />
              <q-input v-model.number="c.cardAgeDaysUnder" class="col-12 col-sm-6" outlined dense type="number" label="Card issued less than … days ago" />
            </div>

            <div class="text-subtitle2 q-mt-md q-mb-sm">Velocity</div>
            <div class="row q-col-gutter-md">
              <q-input v-model.number="c.velocityMaxCount" class="col-6 col-sm-3" outlined dense type="number" label="More than … transactions" />
              <q-input v-model.number="c.velocityMinutes" class="col-6 col-sm-3" outlined dense type="number" label="within minutes" />
              <q-input v-model.number="c.amountWindowMax" class="col-6 col-sm-3" outlined dense type="number" step="any" label="Spend over" hint="Approved, major units" />
              <q-input v-model.number="c.amountWindowMinutes" class="col-6 col-sm-3" outlined dense type="number" label="within minutes" />
              <q-input v-model.number="c.declineCount" class="col-6 col-sm-3" outlined dense type="number" label="At least … declines" />
              <q-input v-model.number="c.declineWindowMinutes" class="col-6 col-sm-3" outlined dense type="number" label="within minutes" />
            </div>
            <div class="text-caption muted q-mt-md">Reads as: {{ describe(toConditions()) || 'every screened transaction' }}</div>
            <div v-if="editing" class="text-caption muted">Matched {{ editing.hits }} time(s){{ editing.lastHitAt ? ', last ' + dateTime(editing.lastHitAt) : '' }} · changed by {{ editing.updatedBy || '—' }}</div>
          </q-card-section>
          <q-card-actions align="right" class="q-pa-md">
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn v-if="can.supervise" type="submit" unelevated no-caps color="primary" label="Save" :loading="busy" />
            <span v-else class="text-caption muted">Changes need a supervisor</span>
          </q-card-actions>
        </q-form>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../../components/PageHeader.vue'
import { api, pending } from '../../lib/api.js'
import { can } from '../../lib/session.js'
import { dateTime, label } from '../../lib/format.js'

const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const editing = ref(null)
const busy = ref(false)
const declineScore = ref('100')
const productOptions = ref([])
const form = reactive({})
const c = reactive({})

const actionOptions = [
  { label: 'Alert only', value: 'ALERT' }, { label: 'Decline (102)', value: 'DECLINE' }, { label: 'Decline and block the card', value: 'DECLINE_BLOCK' }]
const typeOptions = ['WITHDRAWAL', 'PURCHASE', 'PREAUTH', 'COMPLETION', 'BALANCE_INQUIRY', 'PIN_CHANGE'].map(t => ({ label: label(t), value: t }))
const actionLabel = a => actionOptions.find(o => o.value === a)?.label || a
const actionColor = a => ({ ALERT: 'warning', DECLINE: 'negative', DECLINE_BLOCK: 'deep-purple-7' }[a] || 'grey-6')

const columns = [
  { name: 'priority', label: 'Order', field: 'priority', align: 'right', sortable: true },
  { name: 'code', label: 'Code', field: 'code', align: 'left', classes: 'mono', sortable: true },
  { name: 'name', label: 'Rule', field: 'name', align: 'left' },
  { name: 'action', label: 'Action', field: 'action', align: 'left' },
  { name: 'score', label: 'Score', field: 'score', align: 'right', sortable: true },
  { name: 'hits', label: 'Hits', field: 'hits', align: 'right', sortable: true },
  { name: 'lastHitAt', label: 'Last hit', field: 'lastHitAt', format: v => v ? dateTime(v) : '—', align: 'left' },
  { name: 'active', label: 'Status', field: 'active', align: 'left' }
]

const LISTS = ['mccIn', 'mccNotIn', 'countryIn', 'countryNotIn']
const MONEY = ['minAmount', 'maxAmount', 'amountWindowMax']
const list = v => (v || '').split(/[\s,]+/).map(s => s.trim()).filter(Boolean)
const money = v => (v / 100).toLocaleString(undefined, { maximumFractionDigits: 2 })

/** Plain-language summary of a rule's conditions. */
function describe (k) {
  if (!k) return ''
  const p = []
  if (k.types?.length) p.push(k.types.map(t => label(t).toLowerCase()).join(' / '))
  if (k.channels?.length) p.push('on ' + k.channels.join(' / '))
  if (k.products?.length) p.push('products ' + k.products.join(', '))
  if (k.minAmount != null) p.push('amount ≥ ' + money(k.minAmount))
  if (k.maxAmount != null) p.push('amount ≤ ' + money(k.maxAmount))
  if (k.mccIn?.length) p.push('MCC ' + k.mccIn.join(', '))
  if (k.mccNotIn?.length) p.push('not MCC ' + k.mccNotIn.join(', '))
  if (k.countryIn?.length) p.push('country ' + k.countryIn.join(', '))
  if (k.countryNotIn?.length) p.push('not country ' + k.countryNotIn.join(', '))
  if (k.foreign === true) p.push('foreign acquirer')
  if (k.foreign === false) p.push('domestic acquirer')
  if (k.newCountry) p.push('first use in a country')
  if (k.hourFrom != null) p.push(`between ${k.hourFrom}:00 and ${k.hourTo}:00`)
  if (k.cardAgeDaysUnder != null) p.push(`card younger than ${k.cardAgeDaysUnder} days`)
  if (k.velocityMaxCount != null) p.push(`more than ${k.velocityMaxCount} transactions in ${k.velocityMinutes} min`)
  if (k.amountWindowMax != null) p.push(`spend over ${money(k.amountWindowMax)} in ${k.amountWindowMinutes} min`)
  if (k.declineCount != null) p.push(`${k.declineCount}+ declines in ${k.declineWindowMinutes} min`)
  return p.join(' · ')
}

function toConditions () {
  const out = {}
  for (const [k, v] of Object.entries(c)) {
    if (v === null || v === undefined || v === '' || (Array.isArray(v) && !v.length) || (k === 'newCountry' && !v)) continue
    if (LISTS.includes(k)) { const l = list(v); if (l.length) out[k] = l; continue }
    out[k] = MONEY.includes(k) ? Math.round(Number(v) * 100) : v
  }
  return out
}

function edit (row) {
  editing.value = row
  Object.assign(form, row
    ? { code: row.code, name: row.name, description: row.description || '', action: row.action, score: row.score, priority: row.priority, active: row.active }
    : { code: '', name: '', description: '', action: 'ALERT', score: 50, priority: 100, active: false })
  for (const k of Object.keys(c)) delete c[k]
  const k = row?.conditions || {}
  Object.assign(c, Object.fromEntries(Object.entries(k).filter(([, v]) => v !== null)))
  for (const l of LISTS) if (Array.isArray(c[l])) c[l] = c[l].join(', ')
  for (const m of MONEY) if (c[m] != null) c[m] = c[m] / 100
  dialog.value = true
}

async function save () {
  busy.value = true
  try {
    const body = { ...form, conditions: toConditions() }
    const r = editing.value
      ? await api.put(`/admin/setup/fraud-rules/${editing.value.code}`, body)
      : await api.post('/admin/setup/fraud-rules', body)
    if (!pending(r)) Notify.create({ type: 'positive', message: `Rule ${r.code} saved` })
    dialog.value = false
    load()
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

async function load () {
  loading.value = true
  try {
    const [r, s, p] = await Promise.all([api.get('/admin/setup/fraud-rules'), api.get('/admin/setup/settings'), api.get('/admin/setup/products')])
    rows.value = r
    declineScore.value = s.find(x => x.key === 'fraud.decline_score')?.value || '100'
    productOptions.value = p.map(x => x.code)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
