<template>
  <RefCrudPage title="Customer segments" noun="segment" endpoint="/admin/setup/segments"
               subtitle="Segments group customers and, with the account type, decide which card products they can get."
               :columns="columns" :fields="fields" :defaults="defaults" :usage-note="usage" />
</template>

<script setup>
import RefCrudPage from '../../components/RefCrudPage.vue'

const columns = [
  { name: 'code', label: 'Code', field: 'code', align: 'left', sortable: true, classes: 'mono' },
  { name: 'name', label: 'Name', field: 'name', align: 'left', sortable: true },
  { name: 'description', label: 'Description', field: 'description', align: 'left' },
  { name: 'customers', label: 'Customers', field: 'customers', align: 'right', sortable: true },
  { name: 'active', label: 'Status', field: 'active', align: 'left' }
]

const fields = [
  { name: 'code', label: 'Code', required: true, uppercase: true, maxlength: 16, mono: true, lockedOnEdit: true,
    hint: 'A-Z, 0-9, _ · cannot change later', rules: [v => /^[A-Z0-9_]{1,16}$/.test(v || '') || 'A-Z, 0-9, _ only'] },
  { name: 'name', label: 'Name', required: true, maxlength: 64 },
  { name: 'description', label: 'Description', type: 'textarea', maxlength: 256, col: 'col-12' },
  { name: 'active', label: 'Active (can be assigned to customers)', type: 'toggle', col: 'col-12' }
]

const defaults = () => ({ code: '', name: '', description: '', active: true })
const usage = s => s.customers > 0 ? `${s.customers} customer(s) are in this segment. Making it inactive keeps them in it but stops new assignments.` : ''
</script>
