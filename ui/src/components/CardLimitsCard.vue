<template>
  <q-card flat bordered>
    <q-card-section class="row items-center">
      <div class="text-subtitle1 text-weight-medium">Controls & limits</div>
      <q-space />
      <q-btn v-if="editable" flat dense no-caps color="primary" icon="tune" label="Change" @click="openEdit" />
    </q-card-section>
    <q-card-section v-if="l" class="q-pt-none">
      <div class="row q-gutter-sm q-mb-md">
        <q-chip v-for="c in channels" :key="c.key" dense square :icon="c.on ? 'check' : 'block'"
                :color="c.on ? 'positive' : 'grey-5'" text-color="white">
          {{ c.label }}<q-tooltip v-if="c.productOff">Switched off for the whole product</q-tooltip>
        </q-chip>
      </div>
      <q-markup-table flat dense separator="horizontal">
        <thead><tr><th class="text-left">Limit</th><th class="text-right">Card</th><th class="text-right">Used today</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.label">
            <td>{{ row.label }}</td>
            <td class="text-right">{{ row.value }} <q-badge v-if="row.override" outline color="primary" label="override" /></td>
            <td class="text-right">{{ row.used ?? '' }}</td>
          </tr>
        </tbody>
      </q-markup-table>
    </q-card-section>

    <q-dialog v-model="dialog" persistent>
      <q-card style="width: 560px; max-width: 95vw">
        <q-form @submit="save">
          <q-card-section><div class="text-h6">Controls & limits</div>
            <div class="text-body2 muted">Blank limit = product value. Amounts in {{ l?.currencyCode }}.</div></q-card-section>
          <q-card-section class="q-pt-none">
            <div class="row q-gutter-md q-mb-sm">
              <q-toggle v-model="f.atmEnabled" label="ATM" :disable="!l.product.atmEnabled" />
              <q-toggle v-model="f.posEnabled" label="POS" :disable="!l.product.posEnabled" />
              <q-toggle v-model="f.ecomEnabled" label="E-commerce" :disable="!l.product.ecomEnabled" />
            </div>
            <div class="row q-col-gutter-sm">
              <div v-for="x in fields" :key="x.key" class="col-12 col-sm-4">
                <q-input v-model="f[x.key]" outlined dense type="number" :step="x.count ? 1 : 'any'" :label="x.label"
                         :placeholder="x.product" stack-label clearable />
              </div>
            </div>
            <q-input v-model="f.reason" outlined dense label="Reason *" class="q-mt-md" maxlength="128"
                     :rules="[v => !!(v && v.trim()) || 'Required']" />
          </q-card-section>
          <q-card-actions align="right" class="q-pa-md">
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn type="submit" unelevated no-caps color="primary" label="Save" :loading="busy" />
          </q-card-actions>
        </q-form>
      </q-card>
    </q-dialog>
  </q-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Notify } from 'quasar'
import { api, pending } from '../lib/api.js'
import { money, toMajor, toMinor } from '../lib/format.js'

const props = defineProps({ cardId: { type: [Number, String], required: true }, editable: { type: Boolean, default: true } })
const l = ref(null)
const dialog = ref(false)
const busy = ref(false)
const f = reactive({})

const m = v => money(v, l.value.exponent)
const eff = (card, product) => card ?? product

const channels = computed(() => [
  { key: 'atm', label: 'ATM', on: l.value.atmEnabled && l.value.product.atmEnabled, productOff: !l.value.product.atmEnabled },
  { key: 'pos', label: 'POS', on: l.value.posEnabled && l.value.product.posEnabled, productOff: !l.value.product.posEnabled },
  { key: 'ecom', label: 'E-commerce', on: l.value.ecomEnabled && l.value.product.ecomEnabled, productOff: !l.value.product.ecomEnabled }
])

const rows = computed(() => {
  const x = l.value, p = x.product, u = x.today
  return [
    { label: 'Withdrawals per day', value: eff(x.dailyWdCountLimit, p.dailyWdCount), override: x.dailyWdCountLimit != null, used: u.wdCount },
    { label: 'Withdrawal amount per day', value: m(eff(x.dailyWdAmountLimit, p.dailyWdAmount)), override: x.dailyWdAmountLimit != null, used: m(u.wdAmount) },
    { label: 'Per withdrawal', value: m(eff(x.perTxnWdLimit, p.perTxnWdMax)), override: x.perTxnWdLimit != null },
    { label: 'Purchases per day', value: eff(x.dailyPosCountLimit, p.dailyPosCount), override: x.dailyPosCountLimit != null, used: u.posCount },
    { label: 'Purchase amount per day', value: m(eff(x.dailyPosAmountLimit, p.dailyPosAmount)), override: x.dailyPosAmountLimit != null, used: m(u.posAmount) },
    { label: 'Per purchase', value: m(eff(x.perTxnPosLimit, p.perTxnPosMax)), override: x.perTxnPosLimit != null }
  ]
})

const fields = computed(() => {
  const p = l.value.product
  return [
    { key: 'dailyWdCountLimit', label: 'Withdrawals/day', count: true, product: String(p.dailyWdCount) },
    { key: 'dailyWdAmountLimit', label: 'Withdrawn/day', product: m(p.dailyWdAmount) },
    { key: 'perTxnWdLimit', label: 'Per withdrawal', product: m(p.perTxnWdMax) },
    { key: 'dailyPosCountLimit', label: 'Purchases/day', count: true, product: String(p.dailyPosCount) },
    { key: 'dailyPosAmountLimit', label: 'Purchased/day', product: m(p.dailyPosAmount) },
    { key: 'perTxnPosLimit', label: 'Per purchase', product: m(p.perTxnPosMax) }
  ]
})

async function load () {
  l.value = await api.get(`/admin/cards/${props.cardId}/limits`)
}

function openEdit () {
  const x = l.value, e = x.exponent
  Object.assign(f, {
    atmEnabled: x.atmEnabled, posEnabled: x.posEnabled, ecomEnabled: x.ecomEnabled,
    dailyWdCountLimit: x.dailyWdCountLimit, dailyWdAmountLimit: toMajor(x.dailyWdAmountLimit, e), perTxnWdLimit: toMajor(x.perTxnWdLimit, e),
    dailyPosCountLimit: x.dailyPosCountLimit, dailyPosAmountLimit: toMajor(x.dailyPosAmountLimit, e), perTxnPosLimit: toMajor(x.perTxnPosLimit, e),
    reason: ''
  })
  dialog.value = true
}

const num = v => v === '' || v === null || v === undefined ? null : Number(v)

async function save () {
  busy.value = true
  const e = l.value.exponent
  try {
    const res = await api.put(`/admin/cards/${props.cardId}/limits`, {
      atmEnabled: f.atmEnabled, posEnabled: f.posEnabled, ecomEnabled: f.ecomEnabled,
      dailyWdCountLimit: num(f.dailyWdCountLimit), dailyWdAmountLimit: toMinor(num(f.dailyWdAmountLimit), e),
      perTxnWdLimit: toMinor(num(f.perTxnWdLimit), e), dailyPosCountLimit: num(f.dailyPosCountLimit),
      dailyPosAmountLimit: toMinor(num(f.dailyPosAmountLimit), e), perTxnPosLimit: toMinor(num(f.perTxnPosLimit), e),
      reason: f.reason
    })
    if (pending(res)) await load()
    else { l.value = res; Notify.create({ type: 'positive', message: 'Controls updated' }) }
    dialog.value = false
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

onMounted(load)
defineExpose({ reload: load })
</script>
