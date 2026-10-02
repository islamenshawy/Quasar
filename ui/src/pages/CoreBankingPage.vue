<template>
  <q-page padding class="page">
    <PageHeader title="Core banking" subtitle="Funds interface for accounts held in core banking: connection, stand-in and the store-and-forward queue">
      <template #actions>
        <q-btn outline no-caps color="primary" icon="refresh" label="Refresh" @click="load" />
        <q-btn v-if="can.supervise" unelevated no-caps color="primary" icon="send" label="Replay queue now" :loading="replaying" @click="replayNow" />
      </template>
    </PageHeader>

    <div class="row q-col-gutter-md q-mb-md">
      <div class="col-12 col-md-4">
        <q-card flat bordered class="full-height">
          <q-card-section class="row items-center no-wrap q-gutter-x-md">
            <div class="qz-tile-icon" :class="status.up ? 'tone-teal' : status.configured ? 'tone-amber' : ''">
              <q-icon :name="status.up ? 'hub' : 'portable_wifi_off'" size="24px" />
            </div>
            <div class="col">
              <div class="text-subtitle1 text-weight-medium">
                {{ !status.configured ? 'Not configured' : status.up ? 'Connected' : 'Unavailable' }}
              </div>
              <div class="text-caption muted">
                <template v-if="status.up">answered in {{ status.latencyMs }} ms</template>
                <template v-else>{{ status.reason || '—' }}</template>
              </div>
            </div>
          </q-card-section>
          <q-card-section class="q-pt-none text-caption muted">
            While core is unavailable, cards approve up to their product's stand-in limit and the postings wait below.
          </q-card-section>
        </q-card>
      </div>
      <div v-for="t in tiles" :key="t.label" class="col-6 col-md-2">
        <q-card flat bordered class="full-height cursor-pointer" @click="filter = t.filter; loadQueue()">
          <q-card-section>
            <div class="stat-value" :class="t.cls">{{ t.value }}</div>
            <div class="stat-label">{{ t.label }}</div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <q-card flat bordered>
      <q-card-section class="row items-center q-gutter-sm">
        <div class="text-subtitle1 text-weight-medium">Store-and-forward queue</div>
        <q-space />
        <q-btn-toggle v-model="filter" no-caps unelevated toggle-color="primary" size="sm" @update:model-value="loadQueue"
                      :options="[{ label: 'Pending', value: 'PENDING' }, { label: 'Failed', value: 'FAILED' }, { label: 'Sent', value: 'SENT' }, { label: 'Cancelled', value: 'CANCELLED' }, { label: 'All', value: '' }]" />
      </q-card-section>
      <q-table flat :rows="queue.items" :columns="columns" row-key="id" :loading="loading" hide-pagination
               :pagination="{ rowsPerPage: 0 }" no-data-label="Nothing in the queue">
        <template #body-cell-accountNumber="p">
          <q-td :props="p"><router-link :to="`/accounts/${p.row.accountId}`" class="mono">{{ p.value }}</router-link></q-td>
        </template>
        <template #body-cell-status="p">
          <q-td :props="p"><q-badge :color="tone(p.value)" :label="p.value" /></q-td>
        </template>
        <template #body-cell-lastError="p">
          <q-td :props="p" class="text-caption" style="white-space: normal; max-width: 280px">{{ p.value }}</q-td>
        </template>
        <template #body-cell-actions="p">
          <q-td :props="p" class="text-right">
            <template v-if="['PENDING', 'FAILED'].includes(p.row.status) && can.write">
              <q-btn flat dense no-caps size="sm" color="primary" label="Retry" @click="retry(p.row)" />
              <q-btn flat dense no-caps size="sm" color="negative" label="Cancel" @click="cancel(p.row)" />
            </template>
          </q-td>
        </template>
      </q-table>
    </q-card>

    <q-card v-if="sim" flat bordered class="q-mt-md">
      <q-card-section class="row items-center q-gutter-sm">
        <q-icon name="science" color="accent" size="22px" />
        <div class="text-subtitle1 text-weight-medium">Simulator (dev only)</div>
        <q-space />
        <q-toggle v-model="sim.down" color="negative" label="Core is down" @update:model-value="saveSim" />
        <q-input v-model.number="sim.latencyMs" dense outlined type="number" suffix="ms" label="Latency" style="width: 130px" @change="saveSim" />
      </q-card-section>
      <q-table flat dense :rows="simAccounts" :columns="simColumns" row-key="ref" hide-pagination :pagination="{ rowsPerPage: 0 }"
               no-data-label="No core accounts used yet">
        <template #body-cell-actions="p">
          <q-td :props="p" class="text-right">
            <q-btn flat dense no-caps size="sm" color="primary" label="Set balance" @click="setBalance(p.row)" />
            <q-btn-dropdown flat dense no-caps size="sm" :label="p.row.status">
              <q-list dense>
                <q-item v-for="s in ['ACTIVE', 'DEBIT_BLOCKED', 'BLOCKED', 'CLOSED']" :key="s" clickable v-close-popup @click="setSimAccount(p.row.ref, { status: s })">
                  <q-item-section>{{ s }}</q-item-section>
                </q-item>
              </q-list>
            </q-btn-dropdown>
          </q-td>
        </template>
      </q-table>
    </q-card>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify, useQuasar } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import { api, pending, qs } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, money } from '../lib/format.js'

const $q = useQuasar()
const status = ref({})
const queue = ref({ items: [] })
const filter = ref('PENDING')
const loading = ref(false)
const replaying = ref(false)
const sim = ref(null)
const simAccounts = ref([])

const tiles = computed(() => [
  { label: 'Pending', value: status.value.queue?.PENDING ?? 0, filter: 'PENDING', cls: (status.value.queue?.PENDING ?? 0) ? 'text-warning' : '' },
  { label: 'Failed', value: status.value.queue?.FAILED ?? 0, filter: 'FAILED', cls: (status.value.queue?.FAILED ?? 0) ? 'text-negative' : '' },
  { label: 'Core accounts', value: status.value.coreAccounts ?? 0, filter: '' },
  { label: 'Answer time', value: status.value.up ? `${status.value.latencyMs} ms` : '—', filter: filter.value }
])

const columns = [
  { name: 'id', label: '#', field: 'id', align: 'left' },
  { name: 'createdAt', label: 'Queued', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'accountNumber', label: 'Account', field: 'accountNumber', align: 'left' },
  { name: 'operation', label: 'Operation', field: 'operation', align: 'left' },
  { name: 'type', label: 'Type', field: 'type', align: 'left' },
  { name: 'amount', label: 'Amount', field: r => money(r.amount + r.fee, 2, r.currency), align: 'right' },
  { name: 'reference', label: 'Reference', field: 'reference', align: 'left', classes: 'mono' },
  { name: 'attempts', label: 'Tries', field: 'attempts', align: 'right' },
  { name: 'nextAttemptAt', label: 'Next try', field: r => r.status === 'PENDING' ? dateTime(r.nextAttemptAt) : (r.sentAt ? dateTime(r.sentAt) : ''), align: 'left' },
  { name: 'lastError', label: 'Last answer', field: 'lastError', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'actions', label: '', field: 'id' }
]
const simColumns = [
  { name: 'ref', label: 'Core account', field: 'ref', align: 'left', classes: 'mono' },
  { name: 'currency', label: 'Ccy', field: 'currency', align: 'left' },
  { name: 'ledgerBalance', label: 'Ledger', field: r => money(r.ledgerBalance, 2), align: 'right', classes: 'mono' },
  { name: 'heldAmount', label: 'Held', field: r => money(r.heldAmount, 2), align: 'right', classes: 'mono' },
  { name: 'availableBalance', label: 'Available', field: r => money(r.availableBalance, 2), align: 'right', classes: 'mono' },
  { name: 'actions', label: '', field: 'ref' }
]

const tone = s => ({ PENDING: 'warning', FAILED: 'negative', SENT: 'positive', CANCELLED: 'grey-6' }[s] || 'grey-6')

async function loadQueue () {
  loading.value = true
  try {
    queue.value = await api.get('/admin/core-banking/saf' + qs({ status: filter.value, size: 100 }))
  } finally {
    loading.value = false
  }
}

async function loadSim () {
  try {
    sim.value = await api.get('/dev/core-sim/state', { quiet: true })
    simAccounts.value = await api.get('/dev/core-sim/accounts', { quiet: true })
  } catch {
    sim.value = null
  }
}

async function load () {
  status.value = await api.get('/admin/core-banking/status')
  await Promise.all([loadQueue(), loadSim()])
}

async function replayNow () {
  replaying.value = true
  try {
    const r = await api.post('/admin/batch/jobs/CORE_SAF_REPLAY/run', {})
    Notify.create({ type: 'positive', message: r.message || 'Queue replayed' })
    await load()
  } catch { /* shown */ } finally {
    replaying.value = false
  }
}

async function retry (row) {
  await api.post(`/admin/core-banking/saf/${row.id}/retry`, {})
  Notify.create({ type: 'positive', message: `Posting ${row.reference} will be sent at the next replay` })
  load()
}

function cancel (row) {
  $q.dialog({
    title: `Cancel ${row.operation} ${row.reference}`,
    message: 'It will never reach core banking. Settle it manually in core first. Reason:',
    prompt: { model: '', type: 'text', outlined: true, isValid: v => v.trim().length > 2 }, cancel: true
  }).onOk(async reason => {
    const r = await api.post(`/admin/core-banking/saf/${row.id}/cancel`, { reason })
    if (!pending(r)) Notify.create({ type: 'positive', message: 'Cancelled' })
    load()
  })
}

async function saveSim () {
  sim.value = await api.put('/dev/core-sim/state', { down: sim.value.down, latencyMs: sim.value.latencyMs || 0 })
  status.value = await api.get('/admin/core-banking/status')
}

async function setSimAccount (ref, body) {
  await api.put(`/dev/core-sim/accounts/${encodeURIComponent(ref)}`, body)
  loadSim()
}

function setBalance (row) {
  $q.dialog({
    title: `Core balance of ${row.ref}`, message: 'Ledger balance (major units)',
    prompt: { model: String(row.ledgerBalance / 100), type: 'number', outlined: true }, cancel: true
  }).onOk(v => setSimAccount(row.ref, { balance: Math.round(Number(v) * 100) }))
}

onMounted(load)
</script>
