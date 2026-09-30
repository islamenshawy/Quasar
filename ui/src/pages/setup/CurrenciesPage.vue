<template>
  <RefCrudPage title="Currencies" noun="currency" endpoint="/admin/setup/currencies"
               subtitle="Currencies accounts and card products can use. Retire a currency by making it inactive."
               :columns="columns" :fields="fields" :defaults="defaults" :usage-note="usage" />
</template>

<script setup>
import RefCrudPage from '../../components/RefCrudPage.vue'

const columns = [
  { name: 'code', label: 'Code', field: 'code', align: 'left', sortable: true, classes: 'mono' },
  { name: 'name', label: 'Name', field: 'name', align: 'left', sortable: true },
  { name: 'numericCode', label: 'ISO numeric', field: 'numericCode', align: 'left', classes: 'mono' },
  { name: 'exponent', label: 'Decimals', field: 'exponent', align: 'right' },
  { name: 'accounts', label: 'Accounts', field: 'accounts', align: 'right', sortable: true },
  { name: 'active', label: 'Status', field: 'active', align: 'left' }
]

const fields = [
  { name: 'code', label: 'ISO code', required: true, uppercase: true, maxlength: 3, mono: true, lockedOnEdit: true,
    rules: [v => /^[A-Z]{3}$/.test(v || '') || '3 letters, e.g. EGP'] },
  { name: 'numericCode', label: 'ISO numeric code', required: true, maxlength: 3, mono: true,
    rules: [v => /^\d{3}$/.test(v || '') || '3 digits, e.g. 818'] },
  { name: 'name', label: 'Name', required: true, maxlength: 64 },
  { name: 'exponent', label: 'Decimal places', type: 'number', required: true, hint: 'Minor units, e.g. 2 for piastres',
    readonly: m => m.accounts > 0, rules: [v => (v >= 0 && v <= 3) || '0 to 3'] },
  { name: 'active', label: 'Active', type: 'toggle', col: 'col-12' }
]

const defaults = () => ({ code: '', numericCode: '', name: '', exponent: 2, active: true })
const usage = c => c.accounts > 0 ? `${c.accounts} account(s) use ${c.code}; decimal places are locked.` : ''
</script>
