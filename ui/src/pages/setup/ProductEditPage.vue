<template>
  <q-page padding class="page">
    <PageHeader :title="isNew ? 'New card product' : `${form.name || code}`" back="/setup/products"
                :subtitle="isNew ? 'BIN, PAN range, keys and limits' : `${code} · ${product?.cardsIssued ?? 0} card(s) issued`">
      <template #badge>
        <q-badge v-if="!isNew" :color="form.active ? 'positive' : 'grey-6'" :label="form.active ? 'Active' : 'Inactive'" />
      </template>
      <template #actions>
        <q-btn flat no-caps label="Cancel" to="/setup/products" />
        <q-btn v-if="can.supervise" unelevated no-caps color="primary" icon="save" label="Save product" :loading="busy" @click="submit" />
      </template>
    </PageHeader>

    <q-form ref="formRef" @submit.prevent="save">
      <div class="row q-col-gutter-md">
        <div class="col-12 col-lg-8 q-gutter-y-md">
          <q-card v-for="s in sections" :key="s.title" flat bordered>
            <q-card-section>
              <div class="text-subtitle1 text-weight-medium">{{ s.title }}</div>
              <div v-if="s.note" class="text-caption muted q-mb-sm">{{ s.note }}</div>
              <DynamicForm :fields="s.fields" :model-value="form" :editing="!isNew" class="q-mt-xs" />
            </q-card-section>
          </q-card>
        </div>

        <div class="col-12 col-lg-4 q-gutter-y-md">
          <CardPreview :product-name="form.name" :scheme="form.scheme" :tier="form.cardTier"
                       :pan="panExample" name="NAME ON CARD" />
          <q-card v-if="product" flat bordered>
            <q-card-section>
              <div class="text-subtitle1 text-weight-medium">PAN range</div>
              <dl class="dl q-mt-sm">
                <dt>Allocated</dt><dd>{{ (product.nextSequence - product.rangeStart).toLocaleString() }}</dd>
                <dt>Remaining</dt><dd>{{ product.rangeRemaining.toLocaleString() }}</dd>
                <dt>Next number</dt><dd class="mono">{{ product.nextSequence }}</dd>
              </dl>
            </q-card-section>
          </q-card>
        </div>
      </div>
    </q-form>

    <q-card v-if="!isNew" flat bordered class="q-mt-md">
      <q-card-section class="row items-center">
        <div>
          <div class="text-subtitle1 text-weight-medium">Eligibility</div>
          <div class="text-caption muted">
            Which account type and customer segment combinations may receive this product
            (the account currency must also be {{ form.currencyCode }}).
          </div>
        </div>
        <q-space />
        <q-btn v-if="can.supervise" outline no-caps color="primary" icon="save" label="Save eligibility" :loading="eligBusy" @click="saveEligibility" />
      </q-card-section>
      <q-card-section class="q-pt-none" style="overflow-x: auto">
        <q-markup-table flat dense separator="cell" style="min-width: 520px">
          <thead>
            <tr>
              <th class="text-left">Account type ↓ / Segment →</th>
              <th v-for="s in segments" :key="s.code" class="text-center">
                <div>{{ s.code }}</div>
                <q-btn flat dense size="xs" no-caps label="all" @click="toggleColumn(s.code)" />
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in accountTypes" :key="t.code">
              <td>
                {{ t.name }} <span class="mono text-caption muted">{{ t.code }}</span>
                <q-icon v-if="!t.currencies.includes(form.currencyCode)" name="info" color="warning" class="q-ml-xs">
                  <q-tooltip>{{ t.code }} is not offered in {{ form.currencyCode }}, so this row never applies</q-tooltip>
                </q-icon>
              </td>
              <td v-for="s in segments" :key="s.code" class="text-center">
                <q-checkbox :model-value="!!matrix[`${t.code}|${s.code}`]" dense
                            @update:model-value="v => matrix[`${t.code}|${s.code}`] = v" />
              </td>
            </tr>
          </tbody>
        </q-markup-table>
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Notify } from 'quasar'
import PageHeader from '../../components/PageHeader.vue'
import DynamicForm from '../../components/DynamicForm.vue'
import CardPreview from '../../components/CardPreview.vue'
import { api, pending } from '../../lib/api.js'
import { can } from '../../lib/session.js'
import { reloadReference } from '../../lib/reference.js'
import { toMajor, toMinor } from '../../lib/format.js'

const props = defineProps({ code: { type: String, default: null } })
const router = useRouter()
const isNew = computed(() => !props.code)

const formRef = ref(null)
const busy = ref(false)
const eligBusy = ref(false)
const product = ref(null)
const currencies = ref([])
const keys = ref([])
const accountTypes = ref([])
const feePlans = ref([])
const segments = ref([])
const matrix = reactive({})
const form = reactive({
  code: '', name: '', description: '', cardType: 'DEBIT', cardTier: 'CLASSIC', scheme: 'MEEZA',
  currencyCode: 'EGP', bin: '', panLength: 16, rangeStart: 1, rangeEnd: null, serviceCode: '221',
  validityMonths: 36, chipProfile: '', pvki: '1', pvkKeyName: null, cvkKeyName: null, imkAcKeyName: null,
  pinTryLimit: 3, dailyWdCount: 10, dailyWdAmount: null, perTxnWdMax: null, maxCardsPerAccount: 1, active: true,
  // usage settings (flattened here, nested as "usage" in the API)
  atmEnabled: true, posEnabled: true, ecomEnabled: false, dailyPosCount: 20, dailyPosAmount: null, perTxnPosMax: null,
  wdFee: 0, biFee: 0, verifyCvv: false, preauthHoldDays: 7, coreStipLimit: 0, feePlanCode: '', fxAllowed: false,
  autoRenew: true, leadDays: 30, samePan: true, pendingPrintMaxDays: 30,
  emvScheme: 'EMV_CSK', emvDataList: '9F02,9F03,9F1A,95,5F2A,9A,9C,9F37,82,9F36,9F10:CVR',
  imkSmiKeyName: null, contactlessEnabled: true, contactlessTxnLimit: null, contactlessCvmLimit: null, contactlessCumulativeLimit: null, verifyCvv2: false
})
const USAGE_MONEY = ['dailyPosAmount', 'perTxnPosMax', 'wdFee', 'biFee', 'coreStipLimit']

const exponent = computed(() => currencies.value.find(c => c.code === form.currencyCode)?.exponent ?? 2)
const keyOptions = type => keys.value.filter(k => k.keyType === type).map(k => ({ label: `${k.keyName} · KCV ${k.kcv}`, value: k.keyName }))
const maxRange = computed(() => {
  const body = (form.panLength || 16) - (form.bin || '').length - 1
  return body > 0 ? 10 ** body - 1 : 0
})
const panExample = computed(() => {
  if (!/^\d{6,8}$/.test(form.bin || '')) return ''
  const body = (form.panLength || 16) - form.bin.length - 1
  return (form.bin + String(form.rangeStart ?? 0).padStart(body, '0') + 'X').slice(0, form.panLength)
})

const sections = computed(() => [
  {
    title: 'General',
    fields: [
      { name: 'code', label: 'Product code', required: true, uppercase: true, maxlength: 32, mono: true, lockedOnEdit: true,
        rules: [v => /^[A-Z0-9_]{1,32}$/.test(v || '') || 'A-Z, 0-9, _ only'] },
      { name: 'name', label: 'Name', required: true, maxlength: 128 },
      { name: 'cardType', label: 'Card type', type: 'select', required: true, options: ['DEBIT', 'PREPAID', 'CREDIT'] },
      { name: 'cardTier', label: 'Tier', required: true, uppercase: true, maxlength: 16, hint: 'e.g. CLASSIC, GOLD, PLATINUM' },
      { name: 'scheme', label: 'Scheme', type: 'select', required: true, options: ['MEEZA', 'VISA', 'MASTERCARD', 'PRIVATE'] },
      { name: 'currencyCode', label: 'Currency', type: 'select', required: true, lockedOnEdit: true,
        options: currencies.value.filter(c => c.active || c.code === form.currencyCode).map(c => ({ label: `${c.code} – ${c.name}`, value: c.code })) },
      { name: 'description', label: 'Description', type: 'textarea', maxlength: 256, col: 'col-12' },
      { name: 'active', label: 'Active (can be issued)', type: 'toggle', col: 'col-12' }
    ]
  },
  {
    title: 'PAN range and card data',
    note: isNew.value ? 'BIN, PAN length and range start cannot change after the product is created.'
      : 'BIN, PAN length and range start are fixed. The range end can grow, or shrink down to the last allocated number.',
    fields: [
      { name: 'bin', label: 'BIN', required: true, maxlength: 8, mono: true, lockedOnEdit: true,
        rules: [v => /^(\d{6}|\d{8})$/.test(v || '') || '6 or 8 digits'] },
      { name: 'panLength', label: 'PAN length', type: 'number', required: true, lockedOnEdit: true,
        rules: [v => (v >= 13 && v <= 19) || '13 to 19'] },
      { name: 'rangeStart', label: 'Range start', type: 'number', required: true, lockedOnEdit: true, mono: true },
      { name: 'rangeEnd', label: 'Range end', type: 'number', required: true, mono: true, hint: `Max ${maxRange.value.toLocaleString()}`,
        rules: [v => v >= form.rangeStart || 'Must be ≥ range start', v => v <= maxRange.value || 'Too many digits for this PAN length'] },
      { name: 'serviceCode', label: 'Service code', required: true, maxlength: 3, mono: true,
        rules: [v => /^\d{3}$/.test(v || '') || '3 digits'] },
      { name: 'validityMonths', label: 'Validity', type: 'number', required: true, suffix: 'months',
        rules: [v => (v >= 1 && v <= 120) || '1 to 120'] },
      { name: 'chipProfile', label: 'Dexxis chip profile', maxlength: 32 },
      { name: 'maxCardsPerAccount', label: 'Live cards per account', type: 'number', required: true,
        rules: [v => (v >= 1 && v <= 99) || '1 to 99'] }
    ]
  },
  {
    title: 'PIN and keys',
    note: 'Keys are referenced by name; cryptograms stay in the HSM key table.',
    fields: [
      { name: 'pvkKeyName', label: 'PVK', type: 'select', required: true, options: keyOptions('PVK') },
      { name: 'cvkKeyName', label: 'CVK', type: 'select', required: true, options: keyOptions('CVK') },
      { name: 'imkAcKeyName', label: 'IMK-AC (EMV)', type: 'select', options: keyOptions('IMK_AC') },
      { name: 'pvki', label: 'PVKI', required: true, maxlength: 1, mono: true, rules: [v => /^\d$/.test(v || '') || 'One digit'] },
      { name: 'pinTryLimit', label: 'PIN tries before block', type: 'number', required: true, rules: [v => (v >= 1 && v <= 9) || '1 to 9'] }
    ]
  },
  {
    title: 'ATM withdrawal limits',
    note: `Amounts in ${form.currencyCode}.`,
    fields: [
      { name: 'dailyWdCount', label: 'Withdrawals per day', type: 'number', required: true, rules: [v => v >= 0 || '≥ 0'] },
      { name: 'dailyWdAmount', label: 'Daily amount', type: 'number', required: true, prefix: form.currencyCode, step: 'any',
        rules: [v => v >= 0 || '≥ 0'] },
      { name: 'perTxnWdMax', label: 'Per withdrawal max', type: 'number', required: true, prefix: form.currencyCode, step: 'any',
        rules: [v => v >= 0 || '≥ 0', v => v <= form.dailyWdAmount || 'Cannot exceed the daily amount'] }
    ]
  },
  {
    title: 'Channels, purchases, fees and currencies',
    note: `A channel switched off here is off for every card of the product. Amounts in ${form.currencyCode}.`,
    fields: [
      { name: 'atmEnabled', label: 'ATM', type: 'toggle', col: 'col-4' },
      { name: 'posEnabled', label: 'POS', type: 'toggle', col: 'col-4' },
      { name: 'ecomEnabled', label: 'E-commerce', type: 'toggle', col: 'col-4' },
      { name: 'dailyPosCount', label: 'Purchases per day', type: 'number', required: true, col: 'col-12 col-sm-4', rules: [v => v >= 0 || '≥ 0'] },
      { name: 'dailyPosAmount', label: 'Daily purchase amount', type: 'number', required: true, step: 'any', prefix: form.currencyCode, col: 'col-12 col-sm-4', rules: [v => v >= 0 || '≥ 0'] },
      { name: 'perTxnPosMax', label: 'Per purchase max', type: 'number', required: true, step: 'any', prefix: form.currencyCode, col: 'col-12 col-sm-4',
        rules: [v => v >= 0 || '≥ 0', v => v <= form.dailyPosAmount || 'Cannot exceed the daily purchase amount'] },
      { name: 'feePlanCode', label: 'Fee plan', type: 'select', col: 'col-12 col-sm-8',
        hint: 'What the cards pay: issuance, ATM, purchases, FX markup, monthly / annual (Setup → Fee plans)',
        options: [{ label: 'No fees', value: '' }, ...feePlans.value.filter(p => p.active || p.code === form.feePlanCode).map(p => ({ label: `${p.name} (${p.code})`, value: p.code }))] },
      { name: 'fxAllowed', label: 'Accept other currencies (FX rates)', type: 'toggle', col: 'col-12 col-sm-4' },
      { name: 'preauthHoldDays', label: 'Pre-auth hold', type: 'number', required: true, suffix: 'days', rules: [v => (v >= 1 && v <= 45) || '1 to 45'] },
      { name: 'coreStipLimit', label: 'Core banking stand-in limit', type: 'number', required: true, step: 'any', prefix: form.currencyCode,
        hint: 'Cards on core banking accounts: approve up to this per transaction while core does not answer (0 = decline 911)',
        col: 'col-12 col-sm-8', rules: [v => v >= 0 || '≥ 0'] },
      { name: 'verifyCvv', label: 'Verify CVV / iCVV from track 2 (needs the final track layout, IN-04)', type: 'toggle', col: 'col-12' }
    ]
  },
  {
    title: 'Chip (EMV) cryptograms',
    note: 'ARQC is verified by the HSM (KQ) when the product has an IMK-AC key (PIN and keys). The data list must match the card\'s CDOL1.',
    fields: [
      { name: 'emvScheme', label: 'Cryptogram version', type: 'select', required: true,
        options: [{ label: 'EMV common session key (M/Chip, Visa CVN18)', value: 'EMV_CSK' }, { label: 'Visa CVN10 (card key)', value: 'VISA_CVN10' },
          { label: 'Visa CVN17 (qVSDC contactless)', value: 'VISA_CVN17' }] },
      { name: 'emvDataList', label: 'Data list (tags in CDOL1 order)', required: true, col: 'col-12', mono: true,
        hint: '9F10:CVR = bytes 4-7 of the issuer application data; 9F10:B5 = byte 5 (CVN17: 9F02,9F37,9F36,9F10:B5)',
        rules: [v => /^([0-9A-Fa-f]{2,6}(:CVR|:B[0-9]{1,2})?)(\s*,\s*[0-9A-Fa-f]{2,6}(:CVR|:B[0-9]{1,2})?)*$/.test(v || '') || 'Comma-separated tags'] },
      { name: 'imkSmiKeyName', label: 'IMK-SMI (issuer scripts)', type: 'select', options: [{ label: 'None: no chip commands', value: null }, ...keyOptions('IMK_SMI')] }
    ]
  },
  {
    title: 'Contactless and card verification',
    note: `Contactless taps: the no-PIN limit and the cumulative no-PIN spend decide when a PIN is asked (112). Amounts in ${form.currencyCode}, blank = no limit.`,
    fields: [
      { name: 'contactlessEnabled', label: 'Contactless allowed', type: 'toggle', col: 'col-12' },
      { name: 'contactlessTxnLimit', label: 'Per tap max', type: 'number', step: 'any', prefix: form.currencyCode, col: 'col-12 col-sm-4' },
      { name: 'contactlessCvmLimit', label: 'No-PIN up to', type: 'number', step: 'any', prefix: form.currencyCode, col: 'col-12 col-sm-4' },
      { name: 'contactlessCumulativeLimit', label: 'No-PIN total before a PIN', type: 'number', step: 'any', prefix: form.currencyCode, col: 'col-12 col-sm-4' },
      { name: 'verifyCvv2', label: 'Online purchases must carry a valid CVV2', type: 'toggle', col: 'col-12' }
    ]
  },
  {
    title: 'Renewal and printing',
    note: 'Used by the CARD_RENEWAL and STALE_PENDING_PRINT batch jobs.',
    fields: [
      { name: 'autoRenew', label: 'Renew cards automatically before they expire', type: 'toggle', col: 'col-12' },
      { name: 'leadDays', label: 'Renew this many days before expiry', type: 'number', required: true, suffix: 'days', rules: [v => (v >= 1 && v <= 180) || '1 to 180'] },
      { name: 'samePan', label: 'Renewal keeps the card number', type: 'toggle' },
      { name: 'pendingPrintMaxDays', label: 'Cancel cards not printed within', type: 'number', required: true, suffix: 'days', rules: [v => (v >= 1 && v <= 365) || '1 to 365'] }
    ]
  }
])

async function load () {
  const [c, k, t, s, fp] = await Promise.all([
    api.get('/admin/setup/currencies'), api.get('/admin/setup/hsm-keys'),
    api.get('/admin/setup/account-types'), api.get('/admin/setup/segments'), api.get('/admin/setup/fee-plans')])
  feePlans.value = fp
  currencies.value = c
  keys.value = k
  accountTypes.value = t
  segments.value = s
  if (!isNew.value) {
    const [p, elig] = await Promise.all([
      api.get(`/admin/setup/products/${props.code}`), api.get(`/admin/setup/products/${props.code}/eligibility`)])
    setProduct(p)
    for (const e of elig) matrix[`${e.accountTypeCode}|${e.segmentCode}`] = true
  }
}

function setProduct (p) {
  product.value = p
  const exp = currencies.value.find(c => c.code === p.currencyCode)?.exponent ?? 2
  const u = p.usage || {}
  const ch = p.chip || {}
  Object.assign(form, p, u, p.renewal || {}, { emvScheme: p.emv?.scheme, emvDataList: p.emv?.dataList, feePlanCode: u.feePlanCode || '' }, {
    imkSmiKeyName: ch.imkSmiKeyName || null, contactlessEnabled: ch.contactlessEnabled !== false, verifyCvv2: !!ch.verifyCvv2,
    contactlessTxnLimit: toMajor(ch.contactlessTxnLimit, exp), contactlessCvmLimit: toMajor(ch.contactlessCvmLimit, exp),
    contactlessCumulativeLimit: toMajor(ch.contactlessCumulativeLimit, exp)
  }, {
    description: p.description || '', chipProfile: p.chipProfile || '',
    dailyWdAmount: toMajor(p.dailyWdAmount, exp), perTxnWdMax: toMajor(p.perTxnWdMax, exp)
  })
  for (const k of USAGE_MONEY) form[k] = toMajor(u[k], exp)
}

const blankMinor = v => v === null || v === undefined || v === '' ? null : toMinor(Number(v), exponent.value)

async function submit () {
  if (await formRef.value.validate()) save()
}

async function save () {
  busy.value = true
  const body = {
    ...form,
    dailyWdAmount: toMinor(form.dailyWdAmount, exponent.value),
    perTxnWdMax: toMinor(form.perTxnWdMax, exponent.value),
    emv: { scheme: form.emvScheme, dataList: form.emvDataList },
    chip: {
      imkSmiKeyName: form.imkSmiKeyName || null, contactlessEnabled: form.contactlessEnabled, verifyCvv2: form.verifyCvv2,
      contactlessTxnLimit: blankMinor(form.contactlessTxnLimit), contactlessCvmLimit: blankMinor(form.contactlessCvmLimit),
      contactlessCumulativeLimit: blankMinor(form.contactlessCumulativeLimit)
    },
    renewal: { autoRenew: form.autoRenew, leadDays: form.leadDays, samePan: form.samePan, pendingPrintMaxDays: form.pendingPrintMaxDays },
    usage: {
      atmEnabled: form.atmEnabled, posEnabled: form.posEnabled, ecomEnabled: form.ecomEnabled,
      dailyPosCount: form.dailyPosCount, verifyCvv: form.verifyCvv, preauthHoldDays: form.preauthHoldDays,
      feePlanCode: form.feePlanCode || '', fxAllowed: form.fxAllowed,
      ...Object.fromEntries(USAGE_MONEY.map(k => [k, toMinor(form[k], exponent.value)]))
    }
  }
  try {
    const p = isNew.value
      ? await api.post('/admin/setup/products', body)
      : await api.put(`/admin/setup/products/${props.code}`, body)
    if (pending(p)) return
    Notify.create({ type: 'positive', message: `Product ${p.code} saved` })
    reloadReference().catch(() => {})
    if (isNew.value) router.replace(`/setup/products/${p.code}`)
    else setProduct(p)
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

function toggleColumn (seg) {
  const on = !accountTypes.value.every(t => matrix[`${t.code}|${seg}`])
  for (const t of accountTypes.value) matrix[`${t.code}|${seg}`] = on
}

async function saveEligibility () {
  eligBusy.value = true
  const rows = Object.entries(matrix).filter(([, v]) => v).map(([k]) => {
    const [accountTypeCode, segmentCode] = k.split('|')
    return { accountTypeCode, segmentCode }
  })
  try {
    const res = await api.put(`/admin/setup/products/${props.code}/eligibility`, rows)
    if (!pending(res)) Notify.create({ type: 'positive', message: `Eligibility saved: ${rows.length} combination(s)` })
  } catch { /* shown */ } finally {
    eligBusy.value = false
  }
}

onMounted(load)
</script>
