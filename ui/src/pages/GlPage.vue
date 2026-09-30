<template>
  <q-page padding class="page">
    <PageHeader title="GL accounts" subtitle="Balancing side of every card posting. Each currency nets to zero with the card accounts." />
    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" row-key="code" :loading="loading" :pagination="{ rowsPerPage: 50 }">
        <template #body-cell-balance="p">
          <q-td :props="p" class="mono" :class="p.row.balance < 0 ? 'text-negative' : ''">{{ p.value }}</q-td>
        </template>
      </q-table>
    </q-card>
  </q-page>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { api } from '../lib/api.js'
import { label, money } from '../lib/format.js'

const rows = ref([])
const loading = ref(false)
const exponents = ref({})
const columns = [
  { name: 'code', label: 'Code', field: 'code', align: 'left', classes: 'mono', sortable: true },
  { name: 'name', label: 'Name', field: 'name', align: 'left' },
  { name: 'glType', label: 'Type', field: 'glType', format: label, align: 'left' },
  { name: 'currencyCode', label: 'Ccy', field: 'currencyCode', align: 'left', sortable: true },
  { name: 'balance', label: 'Balance', field: r => money(r.balance, exponents.value[r.currencyCode] ?? 2), align: 'right' }
]

onMounted(async () => {
  loading.value = true
  try {
    const [gl, ccy] = await Promise.all([api.get('/admin/gl-accounts'), api.get('/admin/setup/currencies')])
    exponents.value = Object.fromEntries(ccy.map(c => [c.code, c.exponent]))
    rows.value = gl
  } finally {
    loading.value = false
  }
})
</script>
