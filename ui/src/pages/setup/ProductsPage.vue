<template>
  <q-page padding class="page">
    <PageHeader title="Card products" subtitle="BIN ranges, keys, limits and who may receive each product.">
      <template #actions>
        <q-input v-model="filter" dense outlined debounce="200" placeholder="Filter" clearable style="min-width: 200px">
          <template #prepend><q-icon name="search" /></template>
        </q-input>
        <q-btn unelevated no-caps color="primary" icon="add" label="New product" to="/setup/products/new" />
      </template>
    </PageHeader>

    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" row-key="code" :loading="loading" :filter="filter"
               class="clickable-rows" :row-class="r => r.active ? '' : 'inactive-row'"
               :pagination="{ rowsPerPage: 25 }" @row-click="(e, r) => $router.push(`/setup/products/${r.code}`)">
        <template #body-cell-range="p">
          <q-td :props="p">
            <div class="row items-center no-wrap q-gutter-x-sm" style="min-width: 160px">
              <q-linear-progress :value="used(p.row)" rounded size="8px" style="width: 90px"
                                 :color="used(p.row) > .9 ? 'negative' : 'primary'" />
              <span class="text-caption">{{ p.row.rangeRemaining.toLocaleString() }} left</span>
            </div>
          </q-td>
        </template>
        <template #body-cell-active="p">
          <q-td :props="p"><q-badge :color="p.value ? 'positive' : 'grey-6'" :label="p.value ? 'Active' : 'Inactive'" /></q-td>
        </template>
      </q-table>
    </q-card>
  </q-page>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '../../components/PageHeader.vue'
import { api } from '../../lib/api.js'
import { label } from '../../lib/format.js'

const rows = ref([])
const loading = ref(false)
const filter = ref('')

const used = p => {
  const size = p.rangeEnd - p.rangeStart + 1
  return size > 0 ? (size - p.rangeRemaining) / size : 1
}

const columns = [
  { name: 'code', label: 'Code', field: 'code', align: 'left', sortable: true, classes: 'mono' },
  { name: 'name', label: 'Name', field: 'name', align: 'left', sortable: true },
  { name: 'cardType', label: 'Type', field: 'cardType', align: 'left', format: label },
  { name: 'cardTier', label: 'Tier', field: 'cardTier', align: 'left', format: label },
  { name: 'scheme', label: 'Scheme', field: 'scheme', align: 'left' },
  { name: 'currencyCode', label: 'Ccy', field: 'currencyCode', align: 'left' },
  { name: 'bin', label: 'BIN', field: 'bin', align: 'left', classes: 'mono' },
  { name: 'cardsIssued', label: 'Cards', field: 'cardsIssued', align: 'right', sortable: true },
  { name: 'range', label: 'Range', field: 'rangeRemaining', align: 'left' },
  { name: 'active', label: 'Status', field: 'active', align: 'left' }
]

onMounted(async () => {
  loading.value = true
  try { rows.value = await api.get('/admin/setup/products') } finally { loading.value = false }
})
</script>
