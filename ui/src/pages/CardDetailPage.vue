<template>
  <q-page padding class="page">
    <template v-if="k">
      <PageHeader :title="k.maskedPan" :back="`/accounts/${k.accountId}`">
        <template #badge><StatusBadge :status="k.status" /></template>
        <template #subtitle>
          {{ k.productName }} ·
          <router-link :to="`/customers/${k.customerId}`">{{ k.customerName }}</router-link> ·
          account <router-link :to="`/accounts/${k.accountId}`" class="mono">{{ k.accountNumber }}</router-link>
        </template>
        <template #actions>
          <q-btn v-if="can.write && k.status === 'ACTIVE' && k.pinTries > 0" outline no-caps color="primary" icon="password"
                 :label="`Reset PIN tries (${k.pinTries})`" @click="resetTries" />
          <q-btn v-if="can.write && k.status === 'PENDING_PRINT'" outline no-caps color="primary" icon="visibility"
                 label="Show number for printing" @click="reveal" />
          <q-btn v-if="can.write && !k.frozen && ['ACTIVE','BLOCKED','PIN_BLOCKED'].includes(k.status)" outline no-caps color="primary"
                 icon="ac_unit" label="Freeze" @click="freeze(true)" />
          <q-btn v-if="can.write && replaceable" outline no-caps color="primary" icon="autorenew" label="Replace card"
                 @click="replaceDialog = true" />
          <StatusAction v-if="can.write" :current="k.status" :targets="k.allowedTransitions" entity="card" :on-change="changeStatus" />
        </template>
      </PageHeader>

      <div class="row q-col-gutter-md">
        <div class="col-12 col-md-5">
          <CardPreview :pan="k.maskedPan" :name="k.embossingName" :expiry-yymm="k.expiryYYMM" :product-name="k.productName"
                       :scheme="k.scheme" :tier="k.cardTier" :status="k.status" />
          <q-banner v-if="k.status === 'PENDING_PRINT'" rounded class="bg-orange-1 text-dark q-mt-md">
            <template #avatar><q-icon name="print" color="warning" /></template>
            Waiting for Dexxis to print. Perso data fetched {{ k.persoFetchCount }} time(s)
            <span v-if="k.lastPersoFetchAt">, last {{ dateTime(k.lastPersoFetchAt) }}</span>.
          </q-banner>
          <q-banner v-if="k.frozen" rounded class="bg-blue-1 text-dark q-mt-md">
            <template #avatar><q-icon name="ac_unit" color="info" /></template>
            Frozen by the cardholder: every transaction declines (104) until it is unfrozen. The card's status is unchanged.
            <template #action>
              <q-btn v-if="can.write" flat no-caps color="primary" label="Unfreeze" @click="freeze(false)" />
            </template>
          </q-banner>
          <q-banner v-if="k.status === 'PIN_BLOCKED'" rounded class="bg-orange-1 text-dark q-mt-md">
            <template #avatar><q-icon name="password" color="warning" /></template>
            PIN tries exhausted ({{ k.pinTries }}/{{ k.pinTryLimit }}). Reactivating resets the counter.
          </q-banner>
          <CardLimitsCard class="q-mt-md" :card-id="k.id" :editable="can.write && !['LOST','STOLEN','EXPIRED','CANCELLED'].includes(k.status)" />
        </div>
        <div class="col-12 col-md-7">
          <q-card flat bordered>
            <q-card-section>
              <dl class="dl">
                <dt>Card number</dt><dd class="mono">{{ k.maskedPan }} · PSN {{ k.psn }}</dd>
                <dt v-if="k.replacesCardId">Replaces</dt>
                <dd v-if="k.replacesCardId"><router-link :to="`/cards/${k.replacesCardId}`">card #{{ k.replacesCardId }}</router-link> · {{ label(k.replacementReason) }}</dd>
                <dt v-if="k.replacedByCardId">Replaced by</dt>
                <dd v-if="k.replacedByCardId"><router-link :to="`/cards/${k.replacedByCardId}`">card #{{ k.replacedByCardId }}</router-link></dd>
                <dt>Product</dt><dd>{{ k.productName }} <span class="mono muted">{{ k.productCode }}</span></dd>
                <dt>Type / tier / scheme</dt><dd>{{ label(k.cardType) }} · {{ label(k.cardTier) }} · {{ k.scheme }}</dd>
                <dt>Name on card</dt><dd class="mono">{{ k.embossingName }}</dd>
                <dt>Expiry</dt><dd>{{ expiry(k.expiryYYMM) }}</dd>
                <dt>PIN</dt><dd>{{ k.pinSet ? `Set · ${k.pinTries}/${k.pinTryLimit} wrong tries` : 'Not set' }}</dd>
                <dt>Issued</dt><dd>{{ dateTime(k.createdAt) }} by {{ k.createdBy || '—' }} · {{ k.issueChannel }} {{ k.issueLocation || '' }}</dd>
                <dt>Printed</dt><dd>{{ dateTime(k.printedAt) }}</dd>
                <dt>Activated</dt><dd>{{ dateTime(k.activatedAt) }}</dd>
              </dl>
            </q-card-section>
          </q-card>
        </div>
      </div>

      <q-card flat bordered class="q-mt-md">
        <q-tabs v-model="tab" align="left" no-caps active-color="primary" indicator-color="primary" dense class="q-px-sm">
          <q-tab name="transactions" label="Transactions" />
          <q-tab name="fees" :label="`Fees${fees.items?.length ? ' (' + fees.items.length + ')' : ''}`" @click="loadFees" />
          <q-tab name="chip" label="Chip" @click="loadScripts" />
          <q-tab name="digital" label="Wallets & 3-D Secure" />
          <q-tab name="history" label="Status history" />
          <q-tab name="activity" label="Activity" />
        </q-tabs>
        <q-separator />
        <q-tab-panels v-model="tab" animated>
          <q-tab-panel name="transactions" class="q-pa-none">
            <TxnTable :filters="{ cardId: k.id }" :hide="['card']" />
          </q-tab-panel>
          <q-tab-panel name="fees" class="q-pa-none">
            <q-table flat :rows="fees.items || []" :columns="feeColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
                     no-data-label="No card fees charged (transaction fees show on each transaction)">
              <template #bottom-row>
                <q-tr v-if="fees.items?.length"><q-td colspan="3" class="text-weight-medium">Total</q-td>
                  <q-td class="text-right mono text-weight-medium">{{ money(fees.total, 2) }}</q-td><q-td colspan="2" /></q-tr>
              </template>
            </q-table>
          </q-tab-panel>
          <q-tab-panel name="chip">
            <div class="row items-center q-mb-sm">
              <div class="col">
                <div class="text-subtitle1 text-weight-medium">Commands for the chip</div>
                <div class="text-caption muted">Sent with the card's next online chip transaction, signed by the HSM; the card reports the result on a later one.</div>
              </div>
              <q-btn v-if="can.write" unelevated no-caps color="primary" icon="memory" label="Send a command" @click="scriptDialog = true" />
            </div>
            <q-table flat :rows="chipScripts" :columns="scriptColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
                     no-data-label="No chip commands for this card">
              <template #body-cell-status="p">
                <q-td :props="p">
                  <q-badge :color="{ QUEUED: 'info', SENT: 'warning', APPLIED: 'positive', FAILED: 'negative', CANCELLED: 'grey-6' }[p.value]" :label="label(p.value)" />
                  <q-btn v-if="p.value === 'QUEUED' && can.write" flat dense no-caps size="sm" color="negative" label="Cancel" @click="cancelScript(p.row)" />
                </q-td>
              </template>
            </q-table>
            <q-dialog v-model="scriptDialog">
              <q-card style="width: 460px; max-width: 95vw">
                <q-card-section class="text-h6">Send a command to the chip</q-card-section>
                <q-card-section class="q-pt-none q-gutter-md">
                  <q-select v-model="script.command" outlined dense emit-value map-options label="Command" :options="scriptOptions" />
                  <q-input v-if="script.command === 'UPDATE_OFFLINE_LIMIT'" v-model="script.value" outlined dense type="number" label="Offline transactions allowed (0-255)" />
                  <q-input v-model="script.reason" outlined dense label="Reason *" />
                </q-card-section>
                <q-card-actions align="right">
                  <q-btn flat no-caps label="Cancel" v-close-popup />
                  <q-btn unelevated no-caps color="primary" label="Queue" :disable="!script.reason" @click="queueScript" />
                </q-card-actions>
              </q-card>
            </q-dialog>
          </q-tab-panel>
          <q-tab-panel name="digital">
            <CardDigitalPanel :card-id="k.id" />
          </q-tab-panel>
          <q-tab-panel name="history">
            <q-timeline color="primary" layout="dense">
              <q-timeline-entry v-for="(h, i) in history" :key="i" :subtitle="`${dateTime(h.changedAt)} · ${h.changedBy}`"
                                :color="statusColor(h.newStatus)" :icon="i === 0 ? 'radio_button_checked' : undefined">
                <template #title>
                  <span v-if="h.oldStatus">{{ label(h.oldStatus) }} → </span>{{ label(h.newStatus) }}
                </template>
                <div v-if="h.reason" class="text-body2">{{ h.reason }}</div>
              </q-timeline-entry>
            </q-timeline>
          </q-tab-panel>
          <q-tab-panel name="activity" class="q-pa-none">
            <AuditTrail ref="trail" entity-type="card" :entity-id="k.id" />
          </q-tab-panel>
        </q-tab-panels>
      </q-card>
      <ReplaceCardDialog v-model="replaceDialog" :card="k" @done="load" />
      <q-dialog v-model="panDialog" @hide="revealed = ''">
        <q-card style="width: 440px; max-width: 95vw">
          <q-card-section>
            <div class="text-h6">Card number for printing</div>
            <div class="text-body2 muted">Recorded in the audit log. Enter it in Dexxis, then close.</div>
            <div class="mono text-h5 q-my-md" style="letter-spacing: .06em">{{ revealed.replace(/(.{4})(?=.)/g, '$1 ') }}</div>
          </q-card-section>
          <q-card-actions align="right">
            <q-btn flat no-caps icon="content_copy" label="Copy" @click="copyToClipboard(revealed)" />
            <q-btn unelevated no-caps color="primary" label="Close" v-close-popup />
          </q-card-actions>
        </q-card>
      </q-dialog>
    </template>
    <div v-else class="flex flex-center q-pa-xl"><q-spinner size="40px" color="primary" /></div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify, copyToClipboard, useQuasar } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import StatusAction from '../components/StatusAction.vue'
import CardPreview from '../components/CardPreview.vue'
import AuditTrail from '../components/AuditTrail.vue'
import CardLimitsCard from '../components/CardLimitsCard.vue'
import ReplaceCardDialog from '../components/ReplaceCardDialog.vue'
import TxnTable from '../components/TxnTable.vue'
import CardDigitalPanel from '../components/CardDigitalPanel.vue'
import { api, pending } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, expiry, label, money, statusColor } from '../lib/format.js'

const chipScripts = ref([])
const scriptDialog = ref(false)
const script = ref({ command: 'PIN_UNBLOCK', value: '', reason: '' })
const scriptOptions = [
  { label: 'Unblock the offline PIN', value: 'PIN_UNBLOCK' }, { label: 'Block the chip application', value: 'APPLICATION_BLOCK' },
  { label: 'Unblock the chip application', value: 'APPLICATION_UNBLOCK' }, { label: 'Set the offline transaction limit', value: 'UPDATE_OFFLINE_LIMIT' }]
const scriptColumns = [
  { name: 'createdAt', label: 'Queued', field: 'createdAt', format: v => new Date(v).toLocaleString(), align: 'left' },
  { name: 'command', label: 'Command', field: r => (scriptOptions.find(o => o.value === r.command)?.label || r.command) + (r.value ? ' = ' + r.value : ''), align: 'left' },
  { name: 'reason', label: 'Reason', field: 'reason', align: 'left' },
  { name: 'sentAt', label: 'Sent', field: r => r.sentAt ? new Date(r.sentAt).toLocaleString() : '—', align: 'left' },
  { name: 'createdBy', label: 'By', field: 'createdBy', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]
async function loadScripts () {
  chipScripts.value = await api.get(`/admin/cards/${props.id}/chip-scripts`)
}
async function queueScript () {
  try {
    const r = await api.post(`/admin/cards/${props.id}/chip-scripts`, script.value)
    if (!pending(r)) Notify.create({ type: 'positive', message: 'Command queued for the next chip transaction' })
    scriptDialog.value = false
    script.value = { command: 'PIN_UNBLOCK', value: '', reason: '' }
    loadScripts()
  } catch { /* shown */ }
}
async function cancelScript (s) {
  await api.post(`/admin/cards/chip-scripts/${s.id}/cancel`, {})
  loadScripts()
}
const fees = ref({})
const feeColumns = [
  { name: 'createdAt', label: 'Charged', field: 'createdAt', format: v => new Date(v).toLocaleString(), align: 'left' },
  { name: 'event', label: 'Fee', field: r => (r.event.charAt(0) + r.event.slice(1).toLowerCase()).replace(/_/g, ' '), align: 'left' },
  { name: 'period', label: 'Period', field: r => r.period === 'ONCE' ? '—' : r.period, align: 'left' },
  { name: 'amount', label: 'Amount', field: r => (r.amount / 100).toFixed(2) + ' ' + r.currencyCode, align: 'right', classes: 'mono' },
  { name: 'queued', label: '', field: r => r.queued ? 'queued for core banking' : '', align: 'left' },
  { name: 'createdBy', label: 'By', field: 'createdBy', align: 'left' }
]
async function loadFees () {
  fees.value = await api.get(`/admin/cards/${props.id}/fees`)
}
const $q = useQuasar()

const props = defineProps({ id: { type: String, required: true } })

const k = ref(null)
const history = ref([])
const tab = ref('transactions')
const replaceDialog = ref(false)
const panDialog = ref(false)
const revealed = ref('')
const replaceable = computed(() => ['ACTIVE', 'BLOCKED', 'PIN_BLOCKED', 'LOST', 'STOLEN', 'EXPIRED'].includes(k.value?.status) && !k.value?.replacedByCardId)

async function reveal () {
  try {
    revealed.value = (await api.post(`/admin/cards/${props.id}/reveal-pan`, {})).pan
    panDialog.value = true
  } catch { /* shown */ }
}
const trail = ref(null)

async function load () {
  const [card, hist] = await Promise.all([api.get(`/admin/cards/${props.id}`), api.get(`/admin/cards/${props.id}/history`)])
  k.value = card
  history.value = hist
  trail.value?.reload()
}

async function changeStatus (status, reason) {
  const res = await api.post(`/admin/cards/${props.id}/status`, { status, reason })
  if (!pending(res)) Notify.create({ type: 'positive', message: `Card is now ${label(status).toLowerCase()}` })
  load()
}

function freeze (frozen) {
  $q.dialog({
    title: frozen ? 'Freeze card' : 'Unfreeze card',
    message: frozen ? 'At the cardholder\'s request: every transaction is declined until the card is unfrozen.' : 'Transactions work again.',
    prompt: { model: '', type: 'text', label: 'Reason', isValid: v => !!(v && v.trim()), outlined: true },
    cancel: true
  }).onOk(async reason => {
    try {
      k.value = await api.post(`/admin/cards/${props.id}/freeze`, { frozen, reason })
      Notify.create({ type: 'positive', message: frozen ? 'Card frozen' : 'Card unfrozen' })
      trail.value?.reload()
    } catch { /* shown */ }
  })
}

function resetTries () {
  $q.dialog({
    title: 'Reset PIN tries',
    message: `Clear ${k.value.pinTries} wrong PIN attempt(s) on this card?`,
    prompt: { model: '', type: 'text', label: 'Reason', isValid: v => !!(v && v.trim()), outlined: true },
    cancel: true
  }).onOk(async reason => {
    const res = await api.post(`/admin/cards/${props.id}/reset-pin-tries`, { reason })
    if (!pending(res)) Notify.create({ type: 'positive', message: 'PIN tries reset' })
    load()
  })
}

onMounted(load)
</script>
