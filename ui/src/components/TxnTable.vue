<template>
  <div>
    <q-table flat :rows="rows" :columns="visibleColumns" row-key="id" :loading="loading" v-model:pagination="pagination"
             :rows-per-page-options="[25, 50, 100]" class="clickable-rows" dense no-data-label="No transactions"
             @request="onRequest" @row-click="(e, r) => open(r)">
      <template #body-cell-result="p">
        <q-td :props="p">
          <q-badge :color="p.row.approved ? 'positive' : 'negative'" :label="p.row.actionCode || '—'" class="q-mr-xs" />
          <span class="text-caption">{{ p.row.actionText }}</span>
          <q-badge v-if="p.row.reversed" outline color="grey-7" label="reversed" class="q-ml-xs" />
          <q-badge v-if="p.row.advice" outline color="info" label="advice" class="q-ml-xs" />
        </q-td>
      </template>
    </q-table>

    <q-dialog v-model="dialog">
      <q-card v-if="current" style="width: 720px; max-width: 95vw">
        <q-card-section class="row items-center">
          <div>
            <div class="text-h6">{{ label(current.txnType) }} · {{ current.channel }}</div>
            <div class="text-body2 muted">#{{ current.id }} · MTI {{ current.mti }} · {{ dateTime(current.receivedAt) }}</div>
          </div>
          <q-space />
          <q-badge :color="current.approved ? 'positive' : 'negative'" class="text-body2 q-pa-sm"
                   :label="`${current.actionCode} ${current.actionText}`" />
        </q-card-section>
        <q-card-section class="q-pt-none">
          <dl class="dl">
            <dt>Card</dt><dd class="mono">
              <router-link v-if="current.cardId" :to="`/cards/${current.cardId}`" @click="dialog = false">{{ current.maskedPan }}</router-link>
              <span v-else>{{ current.maskedPan }}</span></dd>
            <dt>Account</dt><dd class="mono">
              <router-link v-if="current.accountId" :to="`/accounts/${current.accountId}`" @click="dialog = false">{{ current.accountNumber }}</router-link>
              <span v-else>—</span> <span v-if="current.customerName" class="muted"> · {{ current.customerName }}</span></dd>
            <dt>Amount</dt><dd>{{ amt(current) }}<span v-if="current.feeAmount"> + fees {{ money(current.feeAmount, 2, current.billingCurrency || '') }}</span></dd>
            <template v-if="current.billingAmount != null">
              <dt>Billed</dt><dd class="mono">{{ money(current.billingAmount, 2, current.billingCurrency) }} at {{ current.fxRate }}
                <span v-if="current.fxFee" class="muted"> · FX markup {{ money(current.fxFee, 2) }}</span></dd>
            </template>
            <template v-if="current.acquirerCountry"><dt>Acquirer country</dt><dd class="mono">{{ current.acquirerCountry }}</dd></template>
            <template v-if="current.fraudScore != null">
              <dt>Fraud score</dt><dd><q-badge :color="current.fraudScore >= 100 ? 'negative' : 'warning'" :label="current.fraudScore" />
                <span class="mono text-caption q-ml-sm">{{ current.fraudRules }}</span></dd>
            </template>
            <template v-if="current.standIn || current.coreRef">
              <dt>Core banking</dt><dd><q-badge v-if="current.standIn" color="warning" label="stand-in" class="q-mr-sm" /><span class="mono">{{ current.coreRef || 'queued' }}</span></dd>
            </template>
            <dt v-if="current.amountCompleted != null">Completed</dt>
            <dd v-if="current.amountCompleted != null">{{ money(current.amountCompleted, current.exponent, current.currencyCode) }}</dd>
            <dt>Auth id</dt><dd class="mono">{{ current.authId || '—' }}</dd>
            <dt>Reason</dt><dd>{{ current.declineReason || '—' }}</dd>
            <dt>Balance after</dt><dd>{{ current.ledgerAfter != null ? `${money(current.ledgerAfter, current.exponent)} ledger · ${money(current.availableAfter, current.exponent)} available` : '—' }}</dd>
            <dt>STAN / RRN</dt><dd class="mono">{{ current.stan }} / {{ current.rrn || '—' }}</dd>
            <dt>Terminal / acquirer</dt><dd class="mono">{{ current.terminalId }} / {{ current.acquirerId }}</dd>
            <dt>Merchant</dt><dd>{{ [current.merchantType, current.cardAcceptor].filter(Boolean).join(' · ') || '—' }}</dd>
            <dt>Original</dt><dd class="mono">{{ current.originalKey || '—' }}</dd>
            <dt>Responded</dt><dd>{{ dateTime(current.respondedAt) }}</dd>
          </dl>
        </q-card-section>
        <q-card-actions align="right"><q-btn flat no-caps label="Close" v-close-popup /></q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, qs } from '../lib/api.js'
import { dateTime, label, money } from '../lib/format.js'

const props = defineProps({
  filters: { type: Object, default: () => ({}) },
  hide: { type: Array, default: () => [] }
})

const rows = ref([])
const loading = ref(false)
const pagination = ref({ page: 1, rowsPerPage: 25, rowsNumber: 0 })
const dialog = ref(false)
const current = ref(null)

const amt = r => r.amount ? money(r.amount, r.exponent, r.currencyCode) : '—'

const columns = [
  { name: 'receivedAt', label: 'When', field: 'receivedAt', format: dateTime, align: 'left' },
  { name: 'type', label: 'Type', field: r => label(r.txnType), align: 'left' },
  { name: 'channel', label: 'Channel', field: 'channel', align: 'left' },
  { name: 'card', label: 'Card', field: 'maskedPan', align: 'left', classes: 'mono' },
  { name: 'amount', label: 'Amount', field: r => amt(r), align: 'right' },
  { name: 'result', label: 'Result', field: 'actionCode', align: 'left' },
  { name: 'terminal', label: 'Terminal', field: 'terminalId', align: 'left', classes: 'mono' },
  { name: 'authId', label: 'Auth', field: 'authId', align: 'left', classes: 'mono' }
]
const visibleColumns = computed(() => columns.filter(c => !props.hide.includes(c.name)))

async function onRequest ({ pagination: p }) {
  loading.value = true
  try {
    const page = await api.get('/admin/transactions' + qs({ ...props.filters, page: p.page - 1, size: p.rowsPerPage }))
    rows.value = page.items
    pagination.value = { ...p, rowsNumber: page.total }
  } finally {
    loading.value = false
  }
}

function open (r) {
  current.value = r
  dialog.value = true
}

const reload = () => onRequest({ pagination: { ...pagination.value, page: 1 } })
watch(() => props.filters, reload, { deep: true })
onMounted(reload)
defineExpose({ reload })
</script>
