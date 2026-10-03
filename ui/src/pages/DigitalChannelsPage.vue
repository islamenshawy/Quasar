<template>
  <q-page padding class="page">
    <PageHeader title="Digital channels" subtitle="Wallet tokens and the token service, 3-D Secure decisions for the ACS">
      <template #actions>
        <q-btn outline no-caps color="primary" icon="refresh" label="Refresh" @click="loadAll" />
      </template>
    </PageHeader>

    <div class="row q-col-gutter-md q-mb-md">
      <div class="col-12 col-md-4">
        <q-card flat bordered class="full-height">
          <q-card-section class="row items-center no-wrap q-gutter-x-md">
            <div class="qz-tile-icon" :class="summary.tspConfigured ? 'tone-teal' : 'tone-amber'">
              <q-icon name="wallet" size="24px" />
            </div>
            <div class="col">
              <div class="text-subtitle1 text-weight-medium">{{ summary.tspConfigured ? 'Token service connected' : 'Token service not configured' }}</div>
              <div class="text-caption muted">
                {{ summary.tspConfigured ? 'Issuer suspend / resume / delete and card changes are sent from the outbox' : 'Issuer changes wait in the outbox' }}
              </div>
            </div>
          </q-card-section>
        </q-card>
      </div>
      <div v-for="t in tiles" :key="t.label" class="col-6 col-md-2">
        <q-card flat bordered class="full-height cursor-pointer" @click="t.go && t.go()">
          <q-card-section>
            <div class="stat-value" :class="t.cls">{{ t.value }}</div>
            <div class="stat-label">{{ t.label }}</div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <q-card flat bordered>
      <q-tabs v-model="tab" align="left" no-caps active-color="primary" indicator-color="primary" dense class="q-px-sm">
        <q-tab name="tokens" icon="smartphone" label="Tokens" />
        <q-tab name="outbox" icon="outbox" label="Token service outbox" />
        <q-tab name="tds" icon="verified_user" label="3-D Secure" />
      </q-tabs>
      <q-separator />
      <q-tab-panels v-model="tab" animated>
        <q-tab-panel name="tokens" class="q-pa-none">
          <div class="row q-pa-sm q-gutter-sm items-center">
            <q-btn-toggle v-model="tokenStatus" no-caps unelevated toggle-color="primary" size="sm" @update:model-value="loadTokens"
                          :options="['ACTIVE','SUSPENDED','DELETED','DECLINED',''].map(s => ({ label: s ? label(s) : 'All', value: s }))" />
            <q-input v-model="wallet" dense outlined clearable placeholder="Wallet" style="width: 180px" @update:model-value="loadTokens" debounce="300" />
          </div>
          <q-table flat :rows="tokens.items" :columns="tokenColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
                   no-data-label="No tokens">
            <template #body-cell-card="p">
              <q-td :props="p">
                <router-link :to="`/cards/${p.row.cardId}`" class="mono">{{ p.row.maskedPan }}</router-link>
                <div class="text-caption muted">{{ p.row.customerName }}</div>
              </q-td>
            </template>
            <template #body-cell-status="p">
              <q-td :props="p"><q-badge :color="tokenColor[p.value] || 'grey-6'" :label="label(p.value)" />
                <div v-if="p.row.statusReason" class="text-caption muted">{{ tokenReason(p.row.statusReason) }}</div></q-td>
            </template>
            <template #body-cell-decision="p">
              <q-td :props="p"><q-badge :color="{ GREEN: 'positive', YELLOW: 'warning', RED: 'negative' }[p.value]" :label="label(p.value)" />
                <div v-if="p.row.decisionReasons" class="text-caption muted">{{ p.row.decisionReasons.replace(/,/g, ', ') }}</div></q-td>
            </template>
          </q-table>
        </q-tab-panel>

        <q-tab-panel name="outbox" class="q-pa-none">
          <div class="row q-pa-sm q-gutter-sm items-center">
            <q-btn-toggle v-model="eventStatus" no-caps unelevated toggle-color="primary" size="sm" @update:model-value="loadEvents"
                          :options="[{ label: 'Pending', value: 'PENDING' }, { label: 'Failed', value: 'FAILED' }, { label: 'Sent', value: 'SENT' }, { label: 'All', value: '' }]" />
          </div>
          <q-table flat :rows="events.items" :columns="eventColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
                   no-data-label="Nothing here">
            <template #body-cell-status="p">
              <q-td :props="p"><q-badge :color="{ PENDING: 'warning', FAILED: 'negative', SENT: 'positive' }[p.value]" :label="label(p.value)" /></q-td>
            </template>
            <template #body-cell-lastError="p">
              <q-td :props="p" class="text-caption" style="white-space: normal; max-width: 260px">{{ p.value }}</q-td>
            </template>
            <template #body-cell-actions="p">
              <q-td :props="p" class="text-right">
                <q-btn v-if="p.row.status === 'FAILED' && can.write" flat dense no-caps size="sm" color="primary" label="Retry" @click="retry(p.row)" />
              </q-td>
            </template>
          </q-table>
        </q-tab-panel>

        <q-tab-panel name="tds" class="q-pa-none">
          <div class="row q-pa-sm q-gutter-sm items-center">
            <q-btn-toggle v-model="tdsOutcome" no-caps unelevated toggle-color="primary" size="sm" @update:model-value="loadTds"
                          :options="['', 'FRICTIONLESS', 'AUTHENTICATED', 'CHALLENGE', 'FAILED', 'REJECTED'].map(s => ({ label: s ? label(s) : 'All', value: s }))" />
          </div>
          <q-table flat :rows="tds.items" :columns="tdsColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
                   no-data-label="No authentications">
            <template #body-cell-card="p">
              <q-td :props="p"><router-link :to="`/cards/${p.row.cardId}`" class="mono">{{ p.row.maskedPan }}</router-link></q-td>
            </template>
            <template #body-cell-outcome="p">
              <q-td :props="p">
                <q-badge :color="{ FRICTIONLESS: 'positive', AUTHENTICATED: 'positive', CHALLENGE: 'info', FAILED: 'negative', REJECTED: 'negative' }[p.value]" :label="label(p.value)" />
                <span v-if="p.row.eci" class="mono muted q-ml-xs">ECI {{ p.row.eci }}</span>
              </q-td>
            </template>
            <template #body-cell-risk="p">
              <q-td :props="p"><span class="mono">{{ p.row.riskScore }}</span>
                <div v-if="p.row.riskReasons" class="text-caption muted">{{ p.row.riskReasons.replace(/,/g, ', ') }}</div></q-td>
            </template>
          </q-table>
        </q-tab-panel>
      </q-tab-panels>
    </q-card>

    <!-- dev only: the TSP / wallet and the ACS, played from the console -->
    <q-card v-if="dev" flat bordered class="q-mt-md">
      <q-card-section class="row items-center q-gutter-sm">
        <q-icon name="science" color="accent" size="22px" />
        <div class="text-subtitle1 text-weight-medium">Wallet and ACS simulator (dev only)</div>
        <q-space />
        <q-toggle v-model="tspDown" color="negative" label="Token service is down" @update:model-value="setTspMode" />
        <q-btn flat no-caps color="primary" icon="send" label="Send outbox now" @click="dispatchNow" />
      </q-card-section>
      <q-card-section class="row q-col-gutter-lg q-pt-none">
        <div class="col-12 col-md-6">
          <div class="text-weight-medium q-mb-sm">Add a card to a wallet</div>
          <div class="row q-col-gutter-sm">
            <q-input v-model.number="w.cardId" dense outlined type="number" label="Card id" class="col-4" />
            <q-input v-model="w.wallet" dense outlined label="Wallet" class="col-4" />
            <q-input v-model="w.deviceName" dense outlined label="Device" class="col-4" />
            <div class="col-12">
              <div class="text-caption muted">Wallet risk score {{ w.walletRiskScore }} (40+ asks for a code, 70+ declines)</div>
              <q-slider v-model="w.walletRiskScore" :min="0" :max="99" label />
            </div>
            <q-toggle v-model="w.withCvv2" label="Cardholder typed the CVV2" class="col-12" />
          </div>
          <q-btn unelevated no-caps color="primary" icon="add_to_home_screen" label="Request token" :disable="!w.cardId" @click="provision" />
          <div v-if="prov" class="q-mt-md">
            <q-banner rounded :class="{ GREEN: 'bg-green-1', YELLOW: 'bg-orange-1', RED: 'bg-red-1' }[prov.decision.decision]" class="text-dark">
              <b>{{ prov.decision.decision }}</b> · {{ prov.decision.reasons.join(', ') || 'no risk found' }}
              <div v-if="prov.token">Token •••• {{ prov.token.tokenLast4 }} {{ label(prov.token.status) }} · ref <span class="mono">{{ prov.decision.tokenRef }}</span></div>
              <div v-else-if="prov.decision.decision === 'YELLOW'" class="row items-center q-gutter-sm q-mt-xs">
                <span>Code sent to {{ prov.decision.destination }}</span>
                <q-input v-model="w.code" dense outlined placeholder="Code" style="width: 120px" />
                <q-btn flat dense no-caps color="primary" label="Use last SMS code" @click="w.code = lastCode()" />
                <q-btn unelevated dense no-caps color="primary" label="Verify" @click="verifyToken" />
              </div>
            </q-banner>
          </div>
        </div>
        <div class="col-12 col-md-6">
          <div class="text-weight-medium q-mb-sm">Authenticate an online payment (ACS)</div>
          <div class="row q-col-gutter-sm">
            <q-input v-model.number="a.cardId" dense outlined type="number" label="Card id" class="col-4" />
            <q-input v-model.number="a.amount" dense outlined type="number" label="Amount (EGP)" class="col-4" />
            <q-input v-model="a.merchant" dense outlined label="Merchant" class="col-4" />
            <q-input v-model="a.merchantCountry" dense outlined label="Merchant country" class="col-4" />
            <q-toggle v-model="a.newDevice" label="New device" class="col-8" />
          </div>
          <q-btn unelevated no-caps color="primary" icon="verified_user" label="Authenticate" :disable="!a.cardId" @click="authenticate" />
          <div v-if="auth" class="q-mt-md">
            <q-banner rounded :class="auth.transStatus === 'Y' ? 'bg-green-1' : auth.transStatus === 'C' ? 'bg-blue-1' : 'bg-red-1'" class="text-dark">
              <b>{{ label(auth.outcome) }}</b> (transStatus {{ auth.transStatus }}) · risk {{ auth.riskScore }}
              <span v-if="auth.reasons?.length">: {{ auth.reasons.join(', ') }}</span>
              <div v-if="auth.cavv" class="mono text-caption q-mt-xs" style="word-break: break-all">ECI {{ auth.eci }} · CAVV {{ auth.cavv }}
                <q-btn flat dense size="sm" icon="content_copy" @click="copyToClipboard(auth.cavv)" />
              </div>
              <div v-if="auth.transStatus === 'C'" class="row items-center q-gutter-sm q-mt-xs">
                <span>Code sent to {{ auth.destination || 'the cardholder' }}<template v-if="auth.attemptsLeft != null"> · {{ auth.attemptsLeft }} tries left</template></span>
                <q-input v-model="a.code" dense outlined placeholder="Code" style="width: 120px" />
                <q-btn flat dense no-caps color="primary" label="Use last SMS code" @click="a.code = lastCode()" />
                <q-btn unelevated dense no-caps color="primary" label="Submit" @click="challenge" />
              </div>
            </q-banner>
            <div v-if="auth.cavv" class="text-caption muted q-mt-xs">Pay with it from the switch simulator: e-commerce, CAVV and ECI fields.</div>
          </div>
        </div>
      </q-card-section>
      <q-card-section class="q-pt-none">
        <div class="text-weight-medium q-mb-sm">Received by the token service</div>
        <q-table flat dense :rows="received" :columns="receivedColumns" row-key="at" hide-pagination :pagination="{ rowsPerPage: 8 }"
                 no-data-label="Nothing sent yet" />
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Notify, copyToClipboard } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import { api, qs } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, label, money, tokenReason } from '../lib/format.js'

const tab = ref('tokens')
const summary = ref({ tokens: {}, threeDs30Days: {} })
const tokens = ref({ items: [] })
const events = ref({ items: [] })
const tds = ref({ items: [] })
const tokenStatus = ref('ACTIVE')
const wallet = ref('')
const eventStatus = ref('')
const tdsOutcome = ref('')
const tokenColor = { ACTIVE: 'positive', SUSPENDED: 'warning', INACTIVE: 'info', REQUESTED: 'info', DELETED: 'grey-6', DECLINED: 'negative' }

const n = (o, k) => o?.[k] ?? 0
const tiles = computed(() => {
  const t = summary.value.tokens, d = summary.value.threeDs30Days
  const tdsAll = Object.values(d || {}).reduce((a, b) => a + b, 0)
  const friction = n(d, 'FRICTIONLESS')
  return [
    { label: 'Active tokens', value: n(t, 'ACTIVE'), go: () => { tab.value = 'tokens'; tokenStatus.value = 'ACTIVE'; loadTokens() } },
    { label: 'Suspended', value: n(t, 'SUSPENDED'), cls: n(t, 'SUSPENDED') ? 'text-warning' : '', go: () => { tab.value = 'tokens'; tokenStatus.value = 'SUSPENDED'; loadTokens() } },
    { label: 'Outbox pending / failed', value: `${n(t, 'PENDING_EVENTS')} / ${n(t, 'FAILED_EVENTS')}`, cls: n(t, 'FAILED_EVENTS') ? 'text-negative' : '',
      go: () => { tab.value = 'outbox'; eventStatus.value = n(t, 'FAILED_EVENTS') ? 'FAILED' : 'PENDING'; loadEvents() } },
    { label: '3-D Secure, 30 days (frictionless)', value: tdsAll ? `${tdsAll} (${Math.round(100 * friction / tdsAll)}%)` : 0, go: () => { tab.value = 'tds' } }
  ]
})

const tokenColumns = [
  { name: 'card', label: 'Card', field: 'maskedPan', align: 'left' },
  { name: 'wallet', label: 'Wallet · device', field: r => `${r.wallet} · ${r.deviceName || r.deviceType || '—'}`, align: 'left' },
  { name: 'token', label: 'Token', field: r => r.tokenLast4 ? '•••• ' + r.tokenLast4 : '—', align: 'left', classes: 'mono' },
  { name: 'requestor', label: 'Requestor', field: 'tokenRequestorId', align: 'left', classes: 'mono' },
  { name: 'decision', label: 'Request', field: 'decision', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'createdAt', label: 'Added', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'lastUsedAt', label: 'Last payment', field: 'lastUsedAt', format: v => v ? dateTime(v) : '—', align: 'left' }
]
const eventColumns = [
  { name: 'createdAt', label: 'Queued', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'action', label: 'Message', field: r => label(r.action), align: 'left' },
  { name: 'tokenRef', label: 'Token', field: r => `${r.wallet} · ${r.tokenRef}`, align: 'left', classes: 'mono' },
  { name: 'reason', label: 'Reason', field: 'reason', align: 'left' },
  { name: 'attempts', label: 'Tries', field: 'attempts', align: 'right' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'lastError', label: 'Last error', field: 'lastError', align: 'left' },
  { name: 'sentAt', label: 'Sent', field: 'sentAt', format: v => v ? dateTime(v) : '—', align: 'left' },
  { name: 'actions', label: '', field: 'id', align: 'right' }
]
const tdsColumns = [
  { name: 'createdAt', label: 'When', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'card', label: 'Card', field: 'maskedPan', align: 'left' },
  { name: 'merchant', label: 'Merchant', field: r => r.merchant || '—', align: 'left' },
  { name: 'amount', label: 'Amount', field: r => money(r.amount, 2) + ' ' + r.currencyCode, align: 'right', classes: 'mono' },
  { name: 'channel', label: 'Device', field: r => label(r.deviceChannel || '') || '—', align: 'left' },
  { name: 'outcome', label: 'Outcome', field: 'outcome', align: 'left' },
  { name: 'risk', label: 'Risk', field: 'riskScore', align: 'left' },
  { name: 'used', label: 'Paid', field: r => r.usedTxnId ? '#' + r.usedTxnId : '—', align: 'left', classes: 'mono' }
]

async function loadSummary () { summary.value = await api.get('/admin/digital/summary') }
async function loadTokens () { tokens.value = await api.get('/admin/digital/tokens' + qs({ status: tokenStatus.value, wallet: wallet.value || '', size: 100 })) }
async function loadEvents () { events.value = await api.get('/admin/digital/token-events' + qs({ status: eventStatus.value, size: 100 })) }
async function loadTds () { tds.value = await api.get('/admin/digital/3ds' + qs({ outcome: tdsOutcome.value, size: 100 })) }
async function loadAll () {
  await Promise.all([loadSummary(), loadTokens(), loadEvents(), loadTds()])
  if (dev.value) loadReceived()
}

async function retry (e) {
  await api.post(`/admin/digital/token-events/${e.id}/retry`, {})
  Notify.create({ type: 'positive', message: 'Queued again' })
  loadAll()
}

// ---------------- dev simulator ----------------
const dev = ref(false)
const tspDown = ref(false)
const received = ref([])
const sink = ref([])
const w = reactive({ cardId: null, wallet: 'Quasar Pay', deviceName: 'Pixel 9', walletRiskScore: 10, withCvv2: true, code: '' })
const a = reactive({ cardId: null, amount: 300, merchant: 'QUASAR STORE', merchantCountry: '818', newDevice: false, code: '' })
const prov = ref(null)
const auth = ref(null)
const receivedColumns = [
  { name: 'at', label: 'Received', field: 'at', format: dateTime, align: 'left' },
  { name: 'action', label: 'Message', field: r => label(r.action), align: 'left' },
  { name: 'tokenRef', label: 'Token', field: 'tokenRef', align: 'left', classes: 'mono' },
  { name: 'reason', label: 'Reason', field: 'reason', align: 'left' },
  { name: 'card', label: 'New card', field: r => r.panLast4 ? `•••• ${r.panLast4} · ${r.expiry}` : '', align: 'left', classes: 'mono' }
]
async function loadReceived () {
  received.value = await api.get('/dev/tsp-sim/received', { quiet: true })
  sink.value = await api.get('/dev/notifications/sink', { quiet: true })
}
function lastCode () {
  const m = sink.value.find(s => /one-time password/.test(s.text))
  return m ? (m.text.match(/[0-9]{6}/) || [''])[0] : ''
}
async function refreshSink () {
  await api.post('/dev/notifications/dispatch', {}, { quiet: true }).catch(() => {})
  await loadReceived()
}
async function setTspMode () { await api.put('/dev/tsp-sim/mode', { fail: tspDown.value }) }
async function dispatchNow () { await api.post('/dev/tsp-sim/dispatch', {}); loadAll() }
async function provision () {
  try {
    prov.value = await api.post('/dev/tsp-sim/provision', { ...w, code: undefined })
    await refreshSink()
    loadAll()
  } catch { /* shown */ }
}
async function verifyToken () {
  try {
    const r = await api.post(`/dev/tsp-sim/provision/${prov.value.decision.tokenRef}/verify`, { code: w.code })
    if (r.token) prov.value = { ...prov.value, token: r.token }
    else Notify.create({ type: 'warning', message: `Not verified (${label(r.verification.status)}, ${r.verification.attemptsLeft} tries left)` })
    loadAll()
  } catch { /* shown */ }
}
async function authenticate () {
  try {
    auth.value = await api.post('/dev/acs-sim/authenticate', { ...a, amount: Math.round(Number(a.amount) * 100), currency: 'EGP', code: undefined })
    await refreshSink()
    loadTds(); loadSummary()
  } catch { /* shown */ }
}
async function challenge () {
  try {
    auth.value = await api.post(`/dev/acs-sim/${auth.value.authId}/challenge`, { code: a.code })
    loadTds(); loadSummary()
  } catch { /* shown */ }
}

onMounted(async () => {
  dev.value = await api.get('/dev/tsp-sim/received', { quiet: true }).then(() => true).catch(() => false)
  loadAll()
})
</script>
