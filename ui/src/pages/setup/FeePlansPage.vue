<template>
  <q-page padding class="page">
    <PageHeader title="Fee plans" subtitle="What cards pay. A product points to one plan; each rule is fixed + percent, bounded by min / max, after the free uses of the month.">
      <template #actions>
        <q-btn v-if="can.supervise" unelevated no-caps color="primary" icon="add" label="Add plan" @click="edit(null)" />
      </template>
    </PageHeader>

    <div class="row q-col-gutter-md">
      <div v-for="p in plans" :key="p.code" class="col-12 col-md-6">
        <q-card flat bordered class="full-height cursor-pointer" :class="{ 'inactive-row': !p.active }" @click="edit(p)">
          <q-card-section class="row items-center no-wrap">
            <div class="qz-tile-icon tone-amber q-mr-md"><q-icon name="request_quote" size="22px" /></div>
            <div class="col">
              <div class="text-subtitle1 text-weight-medium">{{ p.name }} <span class="mono text-caption muted">{{ p.code }}</span></div>
              <div class="text-caption muted">
                {{ p.products.length ? 'Products ' + p.products.join(', ') : 'Not used by a product yet' }}{{ p.active ? '' : ' · inactive' }}
              </div>
            </div>
          </q-card-section>
          <q-markup-table flat dense separator="horizontal" class="q-mx-md q-mb-md fee-table">
            <tbody>
              <tr v-for="r in p.rules" :key="r.event + r.region">
                <td>{{ eventLabel(r.event) }}<span v-if="r.region !== 'ANY'" class="text-caption muted"> · {{ r.region.toLowerCase() }}</span></td>
                <td class="text-right mono">{{ describe(r) }}</td>
              </tr>
              <tr v-if="!p.rules.length"><td class="muted">No fees</td></tr>
            </tbody>
          </q-markup-table>
        </q-card>
      </div>
    </div>

    <q-dialog v-model="dialog" persistent>
      <q-card style="width: 980px; max-width: 97vw">
        <q-form @submit="save">
          <q-card-section class="row items-center">
            <div class="text-h6">{{ editing ? `Fee plan ${editing.code}` : 'New fee plan' }}</div>
            <q-space />
            <q-btn flat round dense icon="close" v-close-popup aria-label="Close" />
          </q-card-section>
          <q-card-section class="q-pt-none">
            <div class="row q-col-gutter-md q-mb-md">
              <q-input v-model="form.code" class="col-12 col-sm-3" outlined dense label="Code *" :readonly="!!editing" input-class="mono"
                       :rules="[v => /^[A-Z0-9_]{2,32}$/.test(v || '') || 'A-Z, 0-9, _']" @update:model-value="v => form.code = (v || '').toUpperCase()" />
              <q-input v-model="form.name" class="col-12 col-sm-6" outlined dense label="Name *" :rules="[v => !!v || 'Required']" />
              <q-toggle v-model="form.active" class="col-12 col-sm-3" label="Active" />
            </div>
            <div class="text-caption muted q-mb-sm">Amounts in the account currency (major units). Percent applies to the transaction amount after conversion; FX markup to the converted amount.</div>
            <q-markup-table flat bordered dense class="rule-grid">
              <thead>
                <tr><th class="text-left">Event</th><th class="text-left">Region</th><th>Fixed</th><th>Percent</th><th>Min</th><th>Max</th><th>Free / month</th><th /></tr>
              </thead>
              <tbody>
                <tr v-for="(r, i) in form.rules" :key="i">
                  <td style="min-width: 190px"><q-select v-model="r.event" dense borderless emit-value map-options :options="eventOptions" /></td>
                  <td style="min-width: 140px"><q-select v-model="r.region" dense borderless emit-value map-options :options="regionOptions" /></td>
                  <td><q-input v-model.number="r.fixed" dense borderless type="number" step="any" input-class="text-right mono" /></td>
                  <td><q-input v-model.number="r.percent" dense borderless type="number" step="any" suffix="%" input-class="text-right mono" /></td>
                  <td><q-input v-model.number="r.min" dense borderless type="number" step="any" input-class="text-right mono" placeholder="—" /></td>
                  <td><q-input v-model.number="r.max" dense borderless type="number" step="any" input-class="text-right mono" placeholder="—" /></td>
                  <td><q-input v-model.number="r.freePerMonth" dense borderless type="number" input-class="text-right mono"
                               :disable="!FREE.includes(r.event)" /></td>
                  <td><q-btn flat dense round size="sm" icon="close" @click="form.rules.splice(i, 1)" /></td>
                </tr>
              </tbody>
            </q-markup-table>
            <q-btn flat no-caps color="primary" icon="add" label="Add rule" class="q-mt-sm"
                   @click="form.rules.push({ event: 'ATM_WITHDRAWAL', region: 'ANY', fixed: 0, percent: 0, min: null, max: null, freePerMonth: 0 })" />
          </q-card-section>
          <q-card-actions align="right" class="q-pa-md">
            <span v-if="editing?.products?.length" class="text-caption muted q-mr-md">Used by {{ editing.products.join(', ') }}</span>
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn v-if="can.supervise" type="submit" unelevated no-caps color="primary" label="Save" :loading="busy" />
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

const EVENTS = {
  ISSUANCE: 'Card issuance', REPLACEMENT: 'Replacement', RENEWAL: 'Renewal', ANNUAL: 'Annual fee', MONTHLY: 'Monthly fee',
  ATM_WITHDRAWAL: 'ATM withdrawal', ATM_BALANCE_INQUIRY: 'ATM balance inquiry', POS_PURCHASE: 'POS purchase',
  ECOM_PURCHASE: 'Online purchase', PIN_CHANGE: 'PIN change', FX_MARKUP: 'FX markup'
}
const FREE = ['ATM_WITHDRAWAL', 'ATM_BALANCE_INQUIRY', 'POS_PURCHASE', 'ECOM_PURCHASE', 'PIN_CHANGE']
const eventOptions = Object.entries(EVENTS).map(([value, label]) => ({ label, value }))
const regionOptions = [{ label: 'Anywhere', value: 'ANY' }, { label: 'Domestic', value: 'DOMESTIC' }, { label: 'International', value: 'INTERNATIONAL' }]
const eventLabel = e => EVENTS[e] || e

const plans = ref([])
const dialog = ref(false)
const editing = ref(null)
const busy = ref(false)
const form = reactive({ code: '', name: '', active: true, rules: [] })

const major = v => v == null ? null : v / 100
const minor = v => v === null || v === undefined || v === '' ? null : Math.round(Number(v) * 100)
const fmt = v => (v / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function describe (r) {
  const parts = []
  if (r.fixedAmount) parts.push(fmt(r.fixedAmount))
  if (Number(r.percent)) parts.push(`${Number(r.percent)}%`)
  let s = parts.join(' + ') || '0.00'
  if (r.minAmount != null) s += ` (min ${fmt(r.minAmount)})`
  if (r.maxAmount != null) s += ` (max ${fmt(r.maxAmount)})`
  if (r.freePerMonth) s += ` · ${r.freePerMonth} free/month`
  return s
}

function edit (p) {
  editing.value = p
  Object.assign(form, p
    ? { code: p.code, name: p.name, active: p.active,
        rules: p.rules.map(r => ({ event: r.event, region: r.region, fixed: major(r.fixedAmount), percent: Number(r.percent), min: major(r.minAmount), max: major(r.maxAmount), freePerMonth: r.freePerMonth })) }
    : { code: '', name: '', active: true, rules: [] })
  dialog.value = true
}

async function save () {
  busy.value = true
  try {
    const body = {
      code: form.code, name: form.name, active: form.active,
      rules: form.rules.map(r => ({ event: r.event, region: r.region, fixedAmount: minor(r.fixed) || 0, percent: Number(r.percent) || 0,
        minAmount: minor(r.min), maxAmount: minor(r.max), freePerMonth: FREE.includes(r.event) ? Number(r.freePerMonth) || 0 : 0 }))
    }
    const res = editing.value ? await api.put(`/admin/setup/fee-plans/${editing.value.code}`, body) : await api.post('/admin/setup/fee-plans', body)
    if (!pending(res)) Notify.create({ type: 'positive', message: `Fee plan ${res.code} saved` })
    dialog.value = false
    load()
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

async function load () {
  plans.value = await api.get('/admin/setup/fee-plans')
}

onMounted(load)
</script>

<style scoped>
.fee-table td { padding: 5px 4px; }
.rule-grid th { font-size: 11px; }
.rule-grid td { padding: 0 6px; }
</style>
