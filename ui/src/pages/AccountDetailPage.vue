<template>
  <q-page padding class="page">
    <template v-if="a">
      <PageHeader :title="a.accountNumber" :back="`/customers/${a.customerId}`">
        <template #badge><StatusBadge :status="a.status" /></template>
        <template #subtitle>
          {{ a.accountTypeName || a.accountTypeCode }} · {{ a.currencyCode }} ·
          <router-link :to="`/customers/${a.customerId}`">{{ a.customerName }}</router-link>
          (CIF <span class="mono">{{ a.customerRef }}</span>)
          <span v-if="a.statusReason"> · {{ label(a.status) }}: {{ a.statusReason }}</span>
        </template>
        <template #actions>
          <StatusAction v-if="can.write" :current="a.status" :targets="targets" entity="account" :reason-optional="['ACTIVE']" :on-change="changeStatus" />
          <q-btn v-if="can.write && !isCore" outline no-caps color="primary" icon="post_add" label="Post entry" :disable="a.status === 'CLOSED'"
                 @click="entryDialog = true" />
          <q-btn v-if="can.write" unelevated no-caps color="primary" icon="add_card" label="Issue card" :disable="a.status !== 'ACTIVE'"
                 :to="{ path: '/issue', query: { customerId: a.customerId, accountId: a.id } }" />
        </template>
      </PageHeader>

      <div class="row q-col-gutter-md q-mb-md">
        <div v-for="t in balances" :key="t.label" class="col-12 col-sm-4">
          <q-card flat bordered>
            <q-card-section>
              <div class="stat-label">{{ t.label }}<q-badge v-if="isCore" color="secondary" class="q-ml-sm" label="live from core" /></div>
              <div class="stat-value mono">{{ money(t.value, a.exponent) }} <span class="text-body2">{{ a.currencyCode }}</span></div>
            </q-card-section>
          </q-card>
        </div>
      </div>

      <q-card flat bordered>
        <q-tabs v-model="tab" align="left" no-caps active-color="primary" indicator-color="primary" dense class="q-px-sm">
          <q-tab name="cards" :label="`Cards (${cards.length})`" />
          <q-tab v-if="!isCore" name="ledger" label="Ledger" />
          <q-tab v-else name="core" label="Core banking" />
          <q-tab name="details" label="Details" />
          <q-tab name="activity" label="Activity" />
        </q-tabs>
        <q-separator />
        <q-tab-panels v-model="tab" animated>
          <q-tab-panel name="cards" class="q-pa-none">
            <q-table flat :rows="cards" :columns="cardColumns" row-key="id" class="clickable-rows"
                     :pagination="{ rowsPerPage: 0 }" hide-pagination no-data-label="No cards on this account"
                     @row-click="(e, r) => $router.push(`/cards/${r.id}`)">
              <template #body-cell-status="p"><q-td :props="p"><StatusBadge :status="p.value" /></q-td></template>
            </q-table>
          </q-tab-panel>
          <q-tab-panel name="ledger" class="q-pa-none">
            <AccountLedger ref="ledgerRef" :account="a" @changed="load" />
          </q-tab-panel>
          <q-tab-panel v-if="isCore" name="core">
            <q-banner v-if="coreBal && coreBal.status !== 'APPROVED'" dense rounded class="bg-orange-1 text-dark q-mb-md">
              <template #avatar><q-icon name="portable_wifi_off" color="warning" /></template>
              Core banking did not answer ({{ coreBal.reason || coreBal.status }}). Cards approve up to their stand-in limit; postings wait in the queue.
            </q-banner>
            <div class="row items-center q-mb-sm">
              <div class="text-subtitle1 text-weight-medium">Postings waiting for core</div>
              <q-space />
              <q-btn flat no-caps color="primary" icon="refresh" label="Refresh" @click="loadCore" />
              <q-btn flat no-caps color="primary" icon="hub" label="Core banking queue" to="/core-banking" />
            </div>
            <q-table flat dense :rows="coreQueue" :columns="queueColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
                     no-data-label="Nothing waiting: every posting reached core banking">
              <template #body-cell-status="p"><q-td :props="p"><q-badge :color="p.value === 'SENT' ? 'positive' : p.value === 'FAILED' ? 'negative' : p.value === 'PENDING' ? 'warning' : 'grey-6'" :label="p.value" /></q-td></template>
            </q-table>
          </q-tab-panel>
          <q-tab-panel name="details">
            <dl class="dl" style="max-width: 640px">
              <dt>Account number</dt><dd class="mono">{{ a.accountNumber }}</dd>
              <dt>Type</dt><dd>{{ a.accountTypeName }} <span class="mono muted">{{ a.accountTypeCode }}</span></dd>
              <dt>Balance held in</dt><dd>{{ label(a.ledgerMode) }}</dd>
              <dt>Currency</dt><dd>{{ a.currencyCode }}</dd>
              <dt>Opened</dt><dd>{{ dateTime(a.createdAt) }} by {{ a.createdBy || '—' }}</dd>
              <dt>Closed</dt><dd>{{ a.closedAt ? dateTime(a.closedAt) : '—' }}</dd>
            </dl>
          </q-tab-panel>
          <q-tab-panel name="activity" class="q-pa-none">
            <AuditTrail ref="trail" entity-type="account" :entity-id="a.id" />
          </q-tab-panel>
        </q-tab-panels>
      </q-card>
      <LedgerEntryDialog v-model="entryDialog" :account="a" @saved="() => { load(); ledgerRef?.reload() }" />
    </template>
    <div v-else class="flex flex-center q-pa-xl"><q-spinner size="40px" color="primary" /></div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import StatusAction from '../components/StatusAction.vue'
import AuditTrail from '../components/AuditTrail.vue'
import AccountLedger from '../components/AccountLedger.vue'
import LedgerEntryDialog from '../components/LedgerEntryDialog.vue'
import { api, pending, qs } from '../lib/api.js'
import { can } from '../lib/session.js'
import { date, dateTime, expiry, label, money } from '../lib/format.js'

const props = defineProps({ id: { type: String, required: true } })

const a = ref(null)
const cards = ref([])
const tab = ref('cards')
const trail = ref(null)
const ledgerRef = ref(null)
const entryDialog = ref(false)

const OPEN = ['ACTIVE', 'DEBIT_BLOCKED', 'BLOCKED']
const targets = computed(() => OPEN.includes(a.value?.status)
  ? [...OPEN.filter(s => s !== a.value.status), 'CLOSED'] : [])

const isCore = computed(() => a.value?.ledgerMode === 'CORE_BANKING')
const coreBal = ref(null)
const coreQueue = ref([])
const balances = computed(() => isCore.value
  ? [
      { label: 'Ledger balance', value: coreBal.value?.ledgerBalance ?? null },
      { label: 'On hold', value: coreBal.value?.ledgerBalance != null ? coreBal.value.ledgerBalance - coreBal.value.availableBalance : null },
      { label: 'Available', value: coreBal.value?.availableBalance ?? null }
    ]
  : [
      { label: 'Ledger balance', value: a.value.ledgerBalance },
      { label: 'On hold', value: a.value.heldAmount },
      { label: 'Available', value: a.value.availableBalance }
    ])
const queueColumns = [
  { name: 'createdAt', label: 'Queued', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'operation', label: 'Operation', field: 'operation', align: 'left' },
  { name: 'type', label: 'Type', field: 'type', align: 'left' },
  { name: 'amount', label: 'Amount', field: r => money(r.amount + r.fee, 2, r.currency), align: 'right' },
  { name: 'attempts', label: 'Tries', field: 'attempts', align: 'right' },
  { name: 'lastError', label: 'Last answer', field: 'lastError', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function loadCore () {
  const [b, q] = await Promise.all([
    api.get(`/admin/accounts/${props.id}/core-balance`, { quiet: true }).catch(() => ({ status: 'UNAVAILABLE' })),
    api.get('/admin/core-banking/saf' + qs({ accountId: props.id, size: 50 }), { quiet: true }).catch(() => ({ items: [] }))])
  coreBal.value = b
  coreQueue.value = q.items
}

const cardColumns = [
  { name: 'maskedPan', label: 'Card', field: 'maskedPan', align: 'left', classes: 'mono' },
  { name: 'product', label: 'Product', field: 'productName', align: 'left' },
  { name: 'embossingName', label: 'Name on card', field: 'embossingName', align: 'left', classes: 'mono' },
  { name: 'expiry', label: 'Expiry', field: 'expiryYYMM', format: expiry, align: 'left' },
  { name: 'createdAt', label: 'Issued', field: 'createdAt', format: date, align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function load () {
  const [acct, page] = await Promise.all([
    api.get(`/admin/accounts/${props.id}`),
    api.get('/admin/cards' + qs({ accountId: props.id, size: 200 }))])
  a.value = acct
  cards.value = page.items
  if (acct.ledgerMode === 'CORE_BANKING') loadCore()
  trail.value?.reload()
}

async function changeStatus (status, reason) {
  const res = await api.post(`/admin/accounts/${props.id}/status`, { status, reason })
  if (!pending(res)) Notify.create({ type: 'positive', message: `Account is now ${label(status).toLowerCase()}` })
  load()
}

onMounted(load)
</script>
