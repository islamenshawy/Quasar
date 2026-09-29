<template>
  <q-page padding class="page">
    <PageHeader title="Audit log" subtitle="Every change made in the CMS, by whom and when. Card numbers are never recorded." />

    <q-card flat bordered>
      <q-card-section class="row q-col-gutter-sm">
        <div class="col-6 col-md-2">
          <q-input v-model="filters.actor" dense outlined debounce="300" label="Actor" clearable />
        </div>
        <div class="col-6 col-md-3">
          <q-select v-model="filters.action" dense outlined clearable label="Action" :options="actions" />
        </div>
        <div class="col-6 col-md-2">
          <q-select v-model="filters.entityType" dense outlined clearable label="Record type" :options="entityTypes" />
        </div>
        <div class="col-6 col-md-1">
          <q-input v-model="filters.entityId" dense outlined debounce="300" label="Id" clearable type="number" />
        </div>
        <div class="col-6 col-md-2">
          <q-input v-model="filters.from" dense outlined type="date" label="From" stack-label clearable />
        </div>
        <div class="col-6 col-md-2">
          <q-input v-model="filters.to" dense outlined type="date" label="To" stack-label clearable />
        </div>
      </q-card-section>
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" v-model:pagination="pagination"
               :rows-per-page-options="[50, 100, 200]" @request="onRequest">
        <template #body-cell-entity="p">
          <q-td :props="p">
            <router-link v-if="linkOf(p.row)" :to="linkOf(p.row)">{{ p.row.entityType }} {{ p.row.entityId }}</router-link>
            <span v-else>{{ p.row.entityType }} {{ p.row.entityId ?? '' }}</span>
          </q-td>
        </template>
        <template #body-cell-details="p">
          <q-td :props="p" class="mono text-caption" style="white-space: pre-wrap; max-width: 560px">{{ p.row.details ? JSON.stringify(p.row.details) : '' }}</q-td>
        </template>
      </q-table>
    </q-card>
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { api, qs } from '../lib/api.js'
import { dateTime } from '../lib/format.js'

const rows = ref([])
const loading = ref(false)
const actions = ref([])
const entityTypes = ['customer', 'account', 'card', 'card_product', 'account_type', 'segment', 'currency', 'number_sequence']
const filters = reactive({ actor: '', action: null, entityType: null, entityId: '', from: '', to: '' })
const pagination = ref({ page: 1, rowsPerPage: 50, rowsNumber: 0 })

const columns = [
  { name: 'id', label: '#', field: 'id', align: 'right' },
  { name: 'createdAt', label: 'When', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'actor', label: 'Actor', field: 'actor', align: 'left' },
  { name: 'action', label: 'Action', field: 'action', align: 'left' },
  { name: 'entity', label: 'Record', field: 'entityType', align: 'left' },
  { name: 'details', label: 'Details', field: 'details', align: 'left' }
]

const LINKS = { customer: 'customers', account: 'accounts', card: 'cards' }
const linkOf = r => LINKS[r.entityType] && r.entityId ? `/${LINKS[r.entityType]}/${r.entityId}` : null

async function onRequest ({ pagination: p }) {
  loading.value = true
  try {
    const page = await api.get('/admin/audit' + qs({ ...filters, page: p.page - 1, size: p.rowsPerPage }))
    rows.value = page.items
    pagination.value = { ...p, rowsNumber: page.total }
  } finally {
    loading.value = false
  }
}

const reload = () => onRequest({ pagination: { ...pagination.value, page: 1 } })
watch(filters, reload)

onMounted(async () => {
  reload()
  actions.value = await api.get('/admin/audit/actions')
})
</script>
