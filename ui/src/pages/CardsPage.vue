<template>
  <q-page padding class="page">
    <PageHeader title="Cards" subtitle="Search by last 4 digits, BIN, name on card, CIF, customer or account">
      <template #actions>
        <q-form class="row no-wrap q-gutter-x-sm" @submit="lookup">
          <q-input v-model="pan" dense outlined :type="showPan ? 'text' : 'password'" placeholder="Find by full card number"
                   autocomplete="off" maxlength="19" input-class="mono" style="min-width: 250px"
                   :rules="[v => !v || /^\d{13,19}$/.test(v) || '13-19 digits']" hide-bottom-space>
            <template #append>
              <q-icon :name="showPan ? 'visibility_off' : 'visibility'" class="cursor-pointer" @click="showPan = !showPan" />
            </template>
          </q-input>
          <q-btn type="submit" outline no-caps color="primary" icon="search" label="Find" :loading="finding" />
        </q-form>
      </template>
    </PageHeader>

    <q-card flat bordered>
      <q-card-section class="row q-col-gutter-sm">
        <div class="col-12 col-md-6">
          <q-input v-model="filters.q" dense outlined debounce="300" placeholder="Search" clearable autofocus>
            <template #prepend><q-icon name="search" /></template>
          </q-input>
        </div>
        <div class="col-6 col-md-3">
          <q-select v-model="filters.status" dense outlined clearable label="Status" emit-value map-options
                    :options="reference.cardStatuses.map(s => ({ label: label(s), value: s }))" />
        </div>
        <div class="col-6 col-md-3">
          <q-select v-model="filters.product" dense outlined clearable label="Product" emit-value map-options
                    :options="reference.products.map(p => ({ label: `${p.name} (${p.code})`, value: p.code }))" />
        </div>
      </q-card-section>
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" v-model:pagination="pagination"
               :rows-per-page-options="[25, 50, 100]" class="clickable-rows"
               @request="onRequest" @row-click="(e, r) => $router.push(`/cards/${r.id}`)">
        <template #body-cell-status="p"><q-td :props="p"><StatusBadge :status="p.value" /></q-td></template>
      </q-table>
    </q-card>
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { api, qs } from '../lib/api.js'
import { loadReference, reference } from '../lib/reference.js'
import { date, expiry, label } from '../lib/format.js'

const route = useRoute()
const router = useRouter()
const rows = ref([])
const loading = ref(false)
const pan = ref('')
const showPan = ref(false)
const finding = ref(false)
const filters = reactive({ q: route.query.q || '', status: route.query.status || null, product: route.query.product || null })
const pagination = ref({ page: 1, rowsPerPage: 25, rowsNumber: 0 })

const columns = [
  { name: 'maskedPan', label: 'Card', field: 'maskedPan', align: 'left', classes: 'mono' },
  { name: 'product', label: 'Product', field: 'productName', align: 'left' },
  { name: 'embossingName', label: 'Name on card', field: 'embossingName', align: 'left', classes: 'mono' },
  { name: 'customer', label: 'Customer', field: 'customerName', align: 'left' },
  { name: 'accountNumber', label: 'Account', field: 'accountNumber', align: 'left', classes: 'mono' },
  { name: 'expiry', label: 'Expiry', field: 'expiryYYMM', format: expiry, align: 'left' },
  { name: 'createdAt', label: 'Issued', field: 'createdAt', format: date, align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function onRequest ({ pagination: p }) {
  loading.value = true
  try {
    const page = await api.get('/admin/cards' + qs({ ...filters, page: p.page - 1, size: p.rowsPerPage }))
    rows.value = page.items
    pagination.value = { ...p, rowsNumber: page.total }
  } finally {
    loading.value = false
  }
}

const reload = () => onRequest({ pagination: { ...pagination.value, page: 1 } })

watch(filters, () => {
  router.replace({ query: Object.fromEntries(Object.entries(filters).filter(([, v]) => v)) })
  reload()
})

// full PAN goes in the POST body only, and is cleared from the field right away
async function lookup () {
  if (!pan.value) return
  finding.value = true
  try {
    const { cardId } = await api.post('/admin/cards/lookup', { pan: pan.value })
    pan.value = ''
    router.push(`/cards/${cardId}`)
  } catch { /* shown */ } finally {
    finding.value = false
  }
}

onMounted(() => { loadReference(); reload() })
</script>
