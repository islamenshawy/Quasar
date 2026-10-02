<template>
  <RefCrudPage title="FX rates" noun="FX rate" endpoint="/admin/setup/fx-rates" row-key="pair"
               subtitle="1 unit of the transaction currency = rate units of the account currency. Used when a product accepts foreign currency; the fee plan's FX markup is added on top."
               :columns="columns" :fields="fields" :defaults="defaults" />
</template>

<script setup>
import { computed } from 'vue'
import RefCrudPage from '../../components/RefCrudPage.vue'
import { reference } from '../../lib/reference.js'
import { dateTime } from '../../lib/format.js'

const ccy = () => reference.currencies.map(c => ({ label: `${c.code} – ${c.name}`, value: c.code }))

const columns = [
  { name: 'pair', label: 'Pair', field: 'pair', align: 'left', classes: 'mono', sortable: true },
  { name: 'reads', label: 'Reads as', field: r => `1 ${r.baseCcy} = ${r.rate} ${r.quoteCcy}`, align: 'left' },
  { name: 'rate', label: 'Rate', field: 'rate', align: 'right', classes: 'mono' },
  { name: 'updatedAt', label: 'Changed', field: 'updatedAt', format: dateTime, align: 'left' },
  { name: 'updatedBy', label: 'By', field: 'updatedBy', align: 'left' }
]

const fields = computed(() => [
  { name: 'baseCcy', label: 'Transaction currency', type: 'select', required: true, lockedOnEdit: true, options: ccy() },
  { name: 'quoteCcy', label: 'Account currency', type: 'select', required: true, lockedOnEdit: true, options: ccy() },
  { name: 'rate', label: 'Rate', type: 'number', required: true, step: 'any', mono: true, col: 'col-12',
    hint: m => m.baseCcy && m.quoteCcy && m.rate ? `1 ${m.baseCcy} = ${m.rate} ${m.quoteCcy}` : '',
    rules: [v => Number(v) > 0 || 'Must be positive'] }
])

const defaults = () => ({ baseCcy: 'USD', quoteCcy: 'EGP', rate: null })
</script>
