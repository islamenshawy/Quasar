<template>
  <div class="row q-col-gutter-lg">
    <div class="col-12 col-md-7">
      <q-form v-if="!issued" @submit="issue" class="q-gutter-md">
        <q-banner v-if="blocker" dense rounded class="bg-orange-1 text-dark">
          <template #avatar><q-icon name="warning" color="warning" /></template>
          {{ blocker }}
        </q-banner>
        <q-select v-model="form.productCode" :options="productOptions" emit-value map-options outlined dense
                  label="Card product *" :loading="loading" :disable="!!blocker"
                  :rules="[v => !!v || 'Choose a product']"
                  :hint="products.length ? 'Only products allowed for this account type, segment and currency' : ''">
          <template #no-option>
            <q-item><q-item-section class="text-grey">
              No product is allowed for this account type / segment / currency. Check Setup → Card products → Eligibility.
            </q-item-section></q-item>
          </template>
        </q-select>
        <div class="row q-col-gutter-md">
          <div class="col-12 col-sm-7">
            <q-input v-model="form.embossingName" outlined dense label="Name on card *" maxlength="26" input-class="mono"
                     :rules="[v => /^[A-Za-z .\-/]{1,26}$/.test(v || '') || 'Letters, space . - / only, max 26']"
                     @update:model-value="v => form.embossingName = (v || '').toUpperCase()" />
          </div>
          <div class="col-12 col-sm-5">
            <q-input v-model="form.branchId" outlined dense label="Branch / kiosk *" maxlength="32"
                     :rules="[v => !!(v && v.trim()) || 'Required']" />
          </div>
        </div>
        <q-btn type="submit" unelevated no-caps color="primary" icon="add_card" label="Issue card"
               :loading="busy" :disable="!!blocker" />
      </q-form>

      <q-card v-else flat bordered class="issued">
        <q-card-section>
          <div class="text-subtitle1 text-weight-medium">Card issued. Enter this number in Dexxis to print.</div>
          <div class="mono text-h5 q-my-sm" style="letter-spacing: .06em">
            {{ panVisible ? grouped(issued.pan) : grouped(issued.maskedPan) }}
          </div>
          <div class="row q-gutter-sm">
            <q-btn v-if="panVisible" outline no-caps icon="content_copy" label="Copy card number" @click="copy" />
            <q-btn v-if="panVisible" outline no-caps icon="visibility_off" label="Hide number" @click="hide" />
            <q-btn flat no-caps icon="open_in_new" label="Open card" :to="`/cards/${issued.cardId}`" />
            <q-btn flat no-caps icon="add" label="Issue another" @click="reset" />
          </div>
          <div class="text-caption muted q-mt-sm">
            Shown once and not stored in this browser. The card stays pending until Dexxis prints it and the
            PIN is set at the kiosk.
          </div>
        </q-card-section>
      </q-card>
    </div>

    <div class="col-12 col-md-5">
      <CardPreview :pan="issued ? (panVisible ? issued.pan : issued.maskedPan) : ''"
                   :name="form.embossingName" :expiry-yymm="issued?.expiryYYMM"
                   :product-name="product?.name" :scheme="product?.scheme" :tier="product?.cardTier"
                   :status="issued?.status" />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Notify, copyToClipboard } from 'quasar'
import CardPreview from './CardPreview.vue'
import { api } from '../lib/api.js'

const props = defineProps({
  account: { type: Object, required: true },
  customer: { type: Object, required: true }
})
const emit = defineEmits(['issued'])

const BRANCH_KEY = 'cms.branch'
const products = ref([])
const loading = ref(false)
const busy = ref(false)
const issued = ref(null)
const panVisible = ref(false)
const form = reactive({ productCode: null, embossingName: '', branchId: '' })

const product = computed(() => products.value.find(p => p.code === form.productCode))
const productOptions = computed(() => products.value.map(p => ({
  label: `${p.name} (${p.code})`, value: p.code
})))
const blocker = computed(() => {
  if (props.customer.status !== 'ACTIVE') return `Customer is ${props.customer.status}; cards can only be issued to active customers.`
  if (props.account.status !== 'ACTIVE') return `Account is ${props.account.status}; cards can only be issued on active accounts.`
  return ''
})

async function load () {
  loading.value = true
  try {
    products.value = await api.get(`/admin/accounts/${props.account.id}/eligible-products`)
    if (products.value.length === 1) form.productCode = products.value[0].code
  } finally {
    loading.value = false
  }
}

function reset () {
  hide()
  issued.value = null
  form.productCode = products.value.length === 1 ? products.value[0].code : null
  form.embossingName = props.customer.embossingName
}

watch(() => props.account.id, () => { reset(); load() }, { immediate: true })
try { form.branchId = localStorage.getItem(BRANCH_KEY) || '' } catch { /* ignore */ }

async function issue () {
  busy.value = true
  try {
    try { localStorage.setItem(BRANCH_KEY, form.branchId.trim()) } catch { /* ignore */ }
    issued.value = await api.post(`/admin/accounts/${props.account.id}/cards`, {
      productCode: form.productCode, embossingName: form.embossingName, branchId: form.branchId.trim()
    })
    panVisible.value = true
    Notify.create({ type: 'positive', message: `Card ${issued.value.maskedPan} issued, waiting for print` })
    emit('issued', issued.value)
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

const grouped = p => (p || '').replace(/(.{4})(?=.)/g, '$1 ')

function copy () {
  copyToClipboard(issued.value.pan)
    .then(() => Notify.create({ type: 'info', message: 'Card number copied. Paste it into Dexxis, then clear your clipboard.' }))
}

// the full PAN lives only in this component's memory and is dropped when hidden or left
function hide () {
  panVisible.value = false
  if (issued.value) issued.value = { ...issued.value, pan: null }
}
onBeforeUnmount(hide)
</script>

<style scoped>
.issued { border: 2px solid var(--q-positive); }
</style>
