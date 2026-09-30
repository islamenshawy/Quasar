<template>
  <q-page padding class="page">
    <PageHeader title="Customers" subtitle="Search by CIF, name, national ID, mobile or email">
      <template #actions>
        <q-btn unelevated no-caps color="primary" icon="person_add" label="New customer" @click="dialog = true" />
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
                    :options="reference.customerStatuses.map(s => ({ label: label(s), value: s }))" />
        </div>
        <div class="col-6 col-md-3">
          <q-select v-model="filters.segment" dense outlined clearable label="Segment" emit-value map-options
                    :options="reference.segments.map(s => ({ label: s.name, value: s.code }))" />
        </div>
      </q-card-section>
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" v-model:pagination="pagination"
               :rows-per-page-options="[25, 50, 100]" class="clickable-rows" binary-state-sort
               @request="onRequest" @row-click="(e, r) => $router.push(`/customers/${r.id}`)">
        <template #body-cell-status="p">
          <q-td :props="p"><StatusBadge :status="p.value" /></q-td>
        </template>
      </q-table>
    </q-card>

    <CustomerFormDialog v-model="dialog" @saved="c => $router.push(`/customers/${c.id}`)" />
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import CustomerFormDialog from '../components/CustomerFormDialog.vue'
import { api, qs } from '../lib/api.js'
import { loadReference, reference } from '../lib/reference.js'
import { date, label } from '../lib/format.js'

const route = useRoute()
const router = useRouter()
const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const filters = reactive({ q: route.query.q || '', status: route.query.status || null, segment: route.query.segment || null })
const pagination = ref({ page: 1, rowsPerPage: 25, rowsNumber: 0 })

const columns = [
  { name: 'customerRef', label: 'CIF', field: 'customerRef', align: 'left', classes: 'mono' },
  { name: 'fullName', label: 'Name', field: 'fullName', align: 'left' },
  { name: 'segment', label: 'Segment', field: r => r.segmentName || r.segmentCode, align: 'left' },
  { name: 'customerType', label: 'Type', field: 'customerType', align: 'left', format: label },
  { name: 'nationalId', label: 'National ID', field: 'nationalId', align: 'left', classes: 'mono' },
  { name: 'mobile', label: 'Mobile', field: 'mobile', align: 'left' },
  { name: 'accounts', label: 'Accounts', field: 'accounts', align: 'right' },
  { name: 'liveCards', label: 'Cards', field: 'liveCards', align: 'right' },
  { name: 'createdAt', label: 'Since', field: 'createdAt', align: 'left', format: date },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function onRequest ({ pagination: p }) {
  loading.value = true
  try {
    const page = await api.get('/admin/customers' + qs({ ...filters, page: p.page - 1, size: p.rowsPerPage }))
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
