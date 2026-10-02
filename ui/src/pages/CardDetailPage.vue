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
          <q-tab name="history" label="Status history" />
          <q-tab name="activity" label="Activity" />
        </q-tabs>
        <q-separator />
        <q-tab-panels v-model="tab" animated>
          <q-tab-panel name="transactions" class="q-pa-none">
            <TxnTable :filters="{ cardId: k.id }" :hide="['card']" />
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
import { api, pending } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, expiry, label, statusColor } from '../lib/format.js'

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
