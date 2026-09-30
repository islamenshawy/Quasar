<template>
  <q-page padding class="page">
    <PageHeader title="Accounts" subtitle="Search by account number, CIF or customer name" />

    <q-card flat bordered>
      <q-card-section class="row q-col-gutter-sm">
        <div class="col-12 col-md-5">
          <q-input v-model="filters.q" dense outlined debounce="300" placeholder="Search" clearable autofocus>
            <template #prepend><q-icon name="search" /></template>
          </q-input>
        </div>
        <div class="col-4 col-md-3">
          <q-select v-model="filters.status" dense outlined clearable label="Status" emit-value map-options
                    :options="reference.accountStatuses.map(s => ({ label: label(s), value: s }))" />
        </div>
        <div class="col-4 col-md-2">
          <q-select v-model="filters.type" dense outlined clearable label="Type" emit-value map-options
                    :options="reference.accountTypes.map(t => ({ label: t.name, value: t.code }))" />
        </div>
        <div class="col-4 col-md-2">
          <q-select v-model="filters.currency" dense outlined clearable label="Currency" emit-value map-options
                    :options="reference.currencies.map(c => c.code)" />
        </div>
      </q-card-section>
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" v-model:pagination="pagination"
               :rows-per-page-options="[25, 50, 100]" class="clickable-rows"
               @request="onRequest" @row-click="(e, r) => $router.push(`/accounts/${r.id}`)">
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
import { date, label, money } from '../lib/format.js'

const route = useRoute()
const router = useRouter()
const rows = ref([])
const loading = ref(false)
const filters = reactive({
  q: route.query.q || '', status: route.query.status || null, type: route.query.type || null, currency: route.query.currency || null
})
const pagination = ref({ page: 1, rowsPerPage: 25, rowsNumber: 0 })

const columns = [
  { name: 'accountNumber', label: 'Account', field: 'accountNumber', align: 'left', classes: 'mono' },
  { name: 'customer', label: 'Customer', field: 'customerName', align: 'left' },
  { name: 'customerRef', label: 'CIF', field: 'customerRef', align: 'left', classes: 'mono' },
  { name: 'type', label: 'Type', field: r => r.accountTypeName || r.accountTypeCode, align: 'left' },
  { name: 'currencyCode', label: 'Ccy', field: 'currencyCode', align: 'left' },
  { name: 'ledger', label: 'Ledger balance', field: r => money(r.ledgerBalance, r.exponent), align: 'right' },
  { name: 'available', label: 'Available', field: r => money(r.availableBalance, r.exponent), align: 'right' },
  { name: 'liveCards', label: 'Cards', field: 'liveCards', align: 'right' },
  { name: 'createdAt', label: 'Opened', field: 'createdAt', format: date, align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function onRequest ({ pagination: p }) {
  loading.value = true
  try {
    const page = await api.get('/admin/accounts' + qs({ ...filters, page: p.page - 1, size: p.rowsPerPage }))
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

onMounted(() => { loadReference(); reload() })
</script>
