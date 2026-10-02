<template>
  <div>
    <q-tabs v-model="tab" align="left" no-caps dense active-color="primary" indicator-color="primary" class="q-px-sm">
      <q-tab name="statement" label="Statement" />
      <q-tab name="holds" :label="`Holds (${openHolds})`" />
      <q-tab name="transactions" label="Transactions" />
    </q-tabs>
    <q-separator />
    <q-tab-panels v-model="tab" animated keep-alive>
      <q-tab-panel name="statement" class="q-pa-none">
        <q-table flat dense :rows="lines" :columns="lineColumns" row-key="id" :loading="loading"
                 v-model:pagination="pagination" :rows-per-page-options="[25, 50, 100]" no-data-label="No entries yet"
                 @request="loadStatement">
          <template #body-cell-amount="p">
            <q-td :props="p" :class="p.row.amount < 0 ? 'text-negative' : 'text-positive'">{{ p.value }}</q-td>
          </template>
        </q-table>
      </q-tab-panel>
      <q-tab-panel name="holds" class="q-pa-none">
        <q-table flat dense :rows="holds" :columns="holdColumns" row-key="id" :pagination="{ rowsPerPage: 25 }"
                 no-data-label="No holds">
          <template #body-cell-status="p"><q-td :props="p"><q-badge :color="p.value === 'OPEN' ? 'warning' : 'grey-6'" :label="p.value" /></q-td></template>
          <template #body-cell-actions="p">
            <q-td :props="p" auto-width>
              <q-btn v-if="p.row.status === 'OPEN'" flat dense no-caps color="primary" label="Release" @click="release(p.row)" />
            </q-td>
          </template>
        </q-table>
      </q-tab-panel>
      <q-tab-panel name="transactions" class="q-pa-none">
        <TxnTable :filters="{ accountId: account.id }" />
      </q-tab-panel>
    </q-tab-panels>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify, useQuasar } from 'quasar'
import TxnTable from './TxnTable.vue'
import { api, pending, qs } from '../lib/api.js'
import { dateTime, label, money } from '../lib/format.js'

const $q = useQuasar()

const props = defineProps({ account: { type: Object, required: true } })
const emit = defineEmits(['changed'])

const tab = ref('statement')
const lines = ref([])
const holds = ref([])
const loading = ref(false)
const pagination = ref({ page: 1, rowsPerPage: 25, rowsNumber: 0 })
const exp = () => props.account.exponent
const openHolds = computed(() => holds.value.filter(h => h.status === 'OPEN').length)

const lineColumns = [
  { name: 'createdAt', label: 'When', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'entryType', label: 'Entry', field: 'entryType', format: label, align: 'left' },
  { name: 'narrative', label: 'Narrative', field: 'narrative', align: 'left' },
  { name: 'amount', label: 'Amount', field: r => money(r.amount, exp()), align: 'right' },
  { name: 'balanceAfter', label: 'Balance', field: r => money(r.balanceAfter, exp()), align: 'right' },
  { name: 'createdBy', label: 'By', field: 'createdBy', align: 'left' }
]
const holdColumns = [
  { name: 'createdAt', label: 'Placed', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'amount', label: 'Amount', field: r => money(r.amount, exp()), align: 'right' },
  { name: 'authId', label: 'Auth', field: 'authId', align: 'left', classes: 'mono' },
  { name: 'expiresAt', label: 'Expires', field: 'expiresAt', format: dateTime, align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'closed', label: 'Closed', field: r => r.closedAt ? `${dateTime(r.closedAt)} · ${r.closedBy} · ${r.closeReason}` : '', align: 'left' },
  { name: 'actions', label: '', field: 'id', align: 'right' }
]

async function loadStatement ({ pagination: p } = { pagination: pagination.value }) {
  loading.value = true
  try {
    const page = await api.get(`/admin/accounts/${props.account.id}/statement` + qs({ page: p.page - 1, size: p.rowsPerPage }))
    lines.value = page.items
    pagination.value = { ...p, rowsNumber: page.total }
  } finally {
    loading.value = false
  }
}

async function loadHolds () {
  holds.value = await api.get(`/admin/accounts/${props.account.id}/holds`)
}

function release (h) {
  $q.dialog({
    title: 'Release hold',
    message: `Give ${money(h.amount, exp())} back to the available balance?`,
    prompt: { model: '', type: 'text', label: 'Reason', isValid: v => !!(v && v.trim()), outlined: true },
    cancel: true
  }).onOk(async reason => {
    const res = await api.post(`/admin/holds/${h.id}/release`, { reason })
    if (!pending(res)) Notify.create({ type: 'positive', message: 'Hold released' })
    await loadHolds()
    emit('changed')
  })
}

async function reload () {
  await Promise.all([loadStatement({ pagination: { ...pagination.value, page: 1 } }), loadHolds()])
}

onMounted(reload)
defineExpose({ reload })
</script>
