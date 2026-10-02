<template>
  <q-page padding class="page">
    <PageHeader title="Fraud alerts" subtitle="Cases raised by the fraud rules. Take a case, call the cardholder, then confirm fraud or clear it.">
      <template #actions>
        <q-btn-toggle v-model="filter" no-caps unelevated toggle-color="primary" @update:model-value="reload"
                      :options="[{ label: 'Open', value: 'OPEN' }, { label: 'Mine', value: 'MINE' }, { label: 'Confirmed', value: 'CONFIRMED_FRAUD' }, { label: 'Cleared', value: 'FALSE_POSITIVE' }, { label: 'All', value: '' }]" />
        <q-btn v-if="can.supervise" flat no-caps color="primary" icon="tune" label="Rules" to="/setup/fraud-rules" />
      </template>
    </PageHeader>

    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" class="clickable-rows"
               v-model:pagination="pagination" :rows-number="total" @request="onRequest" @row-click="(e, r) => open(r)"
               no-data-label="No alerts">
        <template #body-cell-score="p">
          <q-td :props="p"><span class="qz-score" :class="scoreTone(p.value)">{{ p.value }}</span></q-td>
        </template>
        <template #body-cell-actionTaken="p">
          <q-td :props="p"><q-badge :color="actionColor(p.value)" :label="p.value === 'DECLINE_BLOCK' ? 'Declined + blocked' : p.value === 'DECLINE' ? 'Declined' : 'Alert'" /></q-td>
        </template>
        <template #body-cell-status="p">
          <q-td :props="p"><StatusBadge :status="p.value" /></q-td>
        </template>
      </q-table>
    </q-card>

    <q-dialog v-model="panel" position="right" full-height>
      <q-card v-if="a" style="width: 520px; max-width: 100vw" class="column no-wrap">
        <q-card-section class="row items-center q-pb-sm">
          <div class="col">
            <div class="text-caption muted">Alert #{{ a.id }} · {{ dateTime(a.createdAt) }}</div>
            <div class="text-h6 mono">{{ a.maskedPan }}</div>
            <div class="text-body2">
              <router-link :to="`/customers/${a.customerId}`">{{ a.customerName }}</router-link> ·
              card <router-link :to="`/cards/${a.cardId}`">{{ label(a.cardStatus) }}</router-link>
            </div>
          </div>
          <span class="qz-score lg" :class="scoreTone(a.score)">{{ a.score }}</span>
        </q-card-section>
        <q-separator />
        <q-card-section class="col scroll">
          <dl class="dl">
            <dt>Transaction</dt><dd>{{ label(a.txnType) }} · {{ a.channel }} <router-link v-if="a.isoTxnId" :to="`/transactions?id=${a.isoTxnId}`">#{{ a.isoTxnId }}</router-link></dd>
            <dt>Amount</dt><dd class="mono">{{ a.amount != null ? money(a.amount, 2, a.currencyCode) : '—' }}</dd>
            <dt>Merchant</dt><dd>{{ a.merchant || '—' }}</dd>
            <dt>Acquirer country</dt><dd class="mono">{{ a.country || 'domestic (not sent)' }}</dd>
            <dt>Answer</dt><dd><q-badge :color="a.actionCode === '000' ? 'positive' : 'negative'" :label="a.actionCode || '—'" /></dd>
            <dt>Status</dt><dd><StatusBadge :status="a.status" /> <span v-if="a.assignedTo" class="muted"> · with {{ a.assignedTo }}</span></dd>
          </dl>
          <div class="text-subtitle2 q-mt-md q-mb-xs">Matched rules</div>
          <div class="row q-gutter-xs">
            <q-chip v-for="r in a.rules.split(',')" :key="r" dense square color="indigo-1" text-color="indigo-10" class="mono">{{ r }}</q-chip>
          </div>
          <div class="text-subtitle2 q-mt-md q-mb-xs">Notes</div>
          <div v-if="!a.notes" class="muted text-body2">No notes yet</div>
          <div v-for="(n, i) in (a.notes || '').split('\n').filter(Boolean)" :key="i" class="qz-note text-body2">{{ n }}</div>
          <q-input v-if="a.status === 'OPEN' && can.write" v-model="note" outlined dense autogrow class="q-mt-sm" label="Add a note">
            <template #append><q-btn flat dense round icon="send" :disable="!note.trim()" @click="addNote" /></template>
          </q-input>
        </q-card-section>
        <q-separator />
        <q-card-section v-if="a.status === 'OPEN' && can.write" class="q-gutter-sm">
          <q-btn v-if="a.assignedTo !== session.user?.username" outline no-caps color="primary" icon="pan_tool" label="Take this case" @click="take" />
          <div class="row q-col-gutter-sm">
            <div class="col-12 col-sm-6">
              <q-select v-model="blockAs" outlined dense emit-value map-options label="Card becomes"
                        :options="[{ label: 'Blocked', value: 'BLOCKED' }, { label: 'Lost', value: 'LOST' }, { label: 'Stolen', value: 'STOLEN' }]" />
            </div>
            <div class="col-12 col-sm-6">
              <q-btn unelevated no-caps color="negative" icon="gpp_bad" label="Confirm fraud" class="full-width" @click="resolve('CONFIRMED_FRAUD')" />
            </div>
            <div class="col-12 col-sm-6">
              <q-select v-model="exempt" outlined dense emit-value map-options label="Then pause rules for"
                        :options="[{ label: 'No pause', value: 0 }, { label: '1 hour', value: 1 }, { label: '24 hours', value: 24 }, { label: '3 days', value: 72 }]" />
            </div>
            <div class="col-12 col-sm-6">
              <q-btn unelevated no-caps color="positive" icon="verified" label="Genuine (false positive)" class="full-width" @click="resolve('FALSE_POSITIVE')" />
            </div>
          </div>
          <q-btn flat no-caps label="Close without action" @click="resolve('CLOSED')" />
        </q-card-section>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { api, pending, qs } from '../lib/api.js'
import { can, session } from '../lib/session.js'
import { dateTime, label, money } from '../lib/format.js'

const route = useRoute()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const filter = ref('OPEN')
const pagination = ref({ page: 1, rowsPerPage: 25 })
const panel = ref(false)
const a = ref(null)
const note = ref('')
const blockAs = ref('BLOCKED')
const exempt = ref(0)

const columns = [
  { name: 'id', label: '#', field: 'id', align: 'left' },
  { name: 'createdAt', label: 'Raised', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'maskedPan', label: 'Card', field: 'maskedPan', align: 'left', classes: 'mono' },
  { name: 'customerName', label: 'Customer', field: 'customerName', align: 'left' },
  { name: 'txn', label: 'Transaction', field: r => `${label(r.txnType || '')} · ${r.channel || ''}`, align: 'left' },
  { name: 'amount', label: 'Amount', field: r => r.amount != null ? money(r.amount, 2, r.currencyCode) : '—', align: 'right' },
  { name: 'rules', label: 'Rules', field: 'rules', align: 'left', classes: 'mono text-caption' },
  { name: 'score', label: 'Score', field: 'score', align: 'right' },
  { name: 'actionTaken', label: 'Engine', field: 'actionTaken', align: 'left' },
  { name: 'assignedTo', label: 'With', field: r => r.assignedTo || '—', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

const scoreTone = s => s >= 100 ? 'hi' : s >= 50 ? 'mid' : 'lo'
const actionColor = x => ({ ALERT: 'warning', DECLINE: 'negative', DECLINE_BLOCK: 'deep-purple-7' }[x] || 'grey-6')

async function reload () {
  loading.value = true
  try {
    const status = filter.value === 'MINE' ? 'OPEN' : filter.value
    const page = await api.get('/admin/fraud/alerts' + qs({ status, page: pagination.value.page - 1, size: pagination.value.rowsPerPage }))
    rows.value = filter.value === 'MINE' ? page.items.filter(x => x.assignedTo === session.user?.username) : page.items
    total.value = filter.value === 'MINE' ? rows.value.length : page.total
  } finally {
    loading.value = false
  }
}

function onRequest (p) {
  pagination.value = p.pagination
  reload()
}

async function open (row) {
  a.value = await api.get(`/admin/fraud/alerts/${row.id}`)
  note.value = ''
  panel.value = true
}

async function take () {
  a.value = await api.post(`/admin/fraud/alerts/${a.value.id}/assign`, {})
  reload()
}

async function addNote () {
  a.value = await api.post(`/admin/fraud/alerts/${a.value.id}/note`, { note: note.value })
  note.value = ''
}

async function resolve (outcome) {
  try {
    const r = await api.post(`/admin/fraud/alerts/${a.value.id}/resolve`, {
      outcome, note: note.value || null, cardStatus: blockAs.value, exemptHours: exempt.value
    })
    if (!pending(r)) Notify.create({ type: 'positive', message: `Alert ${a.value.id}: ${label(outcome).toLowerCase()}` })
    panel.value = false
    reload()
  } catch { /* shown */ }
}

onMounted(async () => {
  await reload()
  if (route.query.id) open({ id: route.query.id })
})
</script>

<style>
.qz-score { display: inline-grid; place-items: center; min-width: 34px; height: 24px; padding: 0 6px; border-radius: 8px;
  font: 700 12.5px var(--qz-font-display); color: #fff; }
.qz-score.lo { background: #8EA2FF; }
.qz-score.mid { background: linear-gradient(135deg, #E8962D, #FF6B5B); }
.qz-score.hi { background: linear-gradient(135deg, #D946B0, #E5484D); }
.qz-score.lg { min-width: 56px; height: 40px; font-size: 20px; border-radius: 12px; }
.qz-note { padding: 6px 10px; border-left: 3px solid var(--q-primary); background: var(--qz-hover); border-radius: 0 8px 8px 0; margin-bottom: 6px; white-space: pre-wrap; }
</style>
