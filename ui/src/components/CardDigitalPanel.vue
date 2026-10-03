<template>
  <div>
    <div class="row items-center q-mb-sm">
      <div class="col">
        <div class="text-subtitle1 text-weight-medium">Wallet tokens</div>
        <div class="text-caption muted">Phones and merchants holding a token of this card. Suspending one stops its payments; the token service is told.</div>
      </div>
    </div>
    <q-table flat :rows="tokens" :columns="tokenColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
             no-data-label="This card is in no wallet">
      <template #body-cell-device="p">
        <q-td :props="p">
          <q-icon :name="p.row.deviceType === 'WATCH' ? 'watch' : p.row.deviceType === 'MERCHANT' ? 'storefront' : 'smartphone'" class="q-mr-xs" />
          {{ p.row.wallet }} <span class="muted">· {{ p.row.deviceName || p.row.deviceType || '—' }}</span>
        </q-td>
      </template>
      <template #body-cell-decision="p">
        <q-td :props="p">
          <q-badge :color="{ GREEN: 'positive', YELLOW: 'warning', RED: 'negative' }[p.value]" :label="label(p.value)" />
          <q-tooltip v-if="p.row.decisionReasons">{{ p.row.decisionReasons.replace(/,/g, ', ').replace(/_/g, ' ').toLowerCase() }}</q-tooltip>
        </q-td>
      </template>
      <template #body-cell-status="p">
        <q-td :props="p">
          <q-badge :color="tokenColor[p.value] || 'grey-6'" :label="label(p.value)" />
          <div v-if="p.row.statusReason" class="text-caption muted">{{ tokenReason(p.row.statusReason) }}</div>
        </q-td>
      </template>
      <template #body-cell-actions="p">
        <q-td :props="p" class="text-right">
          <template v-if="can.write">
            <q-btn v-if="p.row.status === 'ACTIVE'" flat dense no-caps size="sm" color="warning" label="Suspend" @click="act(p.row, 'suspend')" />
            <q-btn v-if="p.row.status === 'SUSPENDED'" flat dense no-caps size="sm" color="primary" label="Resume" @click="act(p.row, 'resume')" />
            <q-btn v-if="['ACTIVE','SUSPENDED','INACTIVE','REQUESTED'].includes(p.row.status)" flat dense no-caps size="sm" color="negative"
                   label="Delete" @click="act(p.row, 'delete')" />
          </template>
        </q-td>
      </template>
    </q-table>

    <div class="text-subtitle1 text-weight-medium q-mt-lg">3-D Secure</div>
    <div class="text-caption muted q-mb-sm">Online payment authentications decided for the bank's ACS: frictionless, challenged by SMS code, or refused.</div>
    <q-table flat :rows="tds" :columns="tdsColumns" row-key="id" hide-pagination :pagination="{ rowsPerPage: 0 }"
             no-data-label="No 3-D Secure authentications">
      <template #body-cell-outcome="p">
        <q-td :props="p">
          <q-badge :color="{ FRICTIONLESS: 'positive', AUTHENTICATED: 'positive', CHALLENGE: 'info', FAILED: 'negative', REJECTED: 'negative' }[p.value]"
                   :label="label(p.value)" />
          <span v-if="p.row.eci" class="mono muted q-ml-xs">ECI {{ p.row.eci }}</span>
        </q-td>
      </template>
      <template #body-cell-risk="p">
        <q-td :props="p">
          <span class="mono">{{ p.row.riskScore }}</span>
          <span v-if="p.row.riskReasons" class="text-caption muted q-ml-xs">{{ p.row.riskReasons.replace(/,/g, ', ').replace(/_/g, ' ').toLowerCase() }}</span>
        </q-td>
      </template>
      <template #body-cell-used="p">
        <q-td :props="p">
          <span v-if="p.row.usedTxnId" class="mono">transaction #{{ p.row.usedTxnId }}</span>
          <span v-else class="muted">{{ ['FRICTIONLESS','AUTHENTICATED'].includes(p.row.outcome) ? 'not used yet' : '—' }}</span>
        </q-td>
      </template>
    </q-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Notify, useQuasar } from 'quasar'
import { api, pending } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, label, money, tokenReason } from '../lib/format.js'

const props = defineProps({ cardId: { type: [Number, String], required: true } })
const $q = useQuasar()
const tokens = ref([])
const tds = ref([])

const tokenColor = { ACTIVE: 'positive', SUSPENDED: 'warning', INACTIVE: 'info', REQUESTED: 'info', DELETED: 'grey-6', DECLINED: 'negative' }


const tokenColumns = [
  { name: 'device', label: 'Wallet · device', field: 'wallet', align: 'left' },
  { name: 'token', label: 'Token', field: r => r.tokenLast4 ? `•••• ${r.tokenLast4}` + (r.tokenExpiry ? ` · ${r.tokenExpiry.slice(2)}/${r.tokenExpiry.slice(0, 2)}` : '') : '—', align: 'left', classes: 'mono' },
  { name: 'decision', label: 'Request', field: 'decision', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'createdAt', label: 'Added', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'lastUsedAt', label: 'Last payment', field: 'lastUsedAt', format: v => v ? dateTime(v) : '—', align: 'left' },
  { name: 'actions', label: '', field: 'id', align: 'right' }
]
const tdsColumns = [
  { name: 'createdAt', label: 'When', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'merchant', label: 'Merchant', field: r => r.merchant || '—', align: 'left' },
  { name: 'amount', label: 'Amount', field: r => money(r.amount, 2) + ' ' + r.currencyCode, align: 'right', classes: 'mono' },
  { name: 'outcome', label: 'Outcome', field: 'outcome', align: 'left' },
  { name: 'risk', label: 'Risk', field: 'riskScore', align: 'left' },
  { name: 'used', label: 'Paid with', field: 'usedTxnId', align: 'left' }
]

async function load () {
  const [t, d] = await Promise.all([api.get(`/admin/cards/${props.cardId}/tokens`), api.get(`/admin/digital/3ds?cardId=${props.cardId}&size=50`)])
  tokens.value = t
  tds.value = d.items
}

function act (t, action) {
  $q.dialog({
    title: `${action.charAt(0).toUpperCase() + action.slice(1)} token`,
    message: `${t.wallet} on ${t.deviceName || 'device'} (•••• ${t.tokenLast4 || '—'})`,
    prompt: { model: '', type: 'text', label: 'Reason', isValid: v => !!(v && v.trim()), outlined: true },
    cancel: true
  }).onOk(async reason => {
    try {
      const r = await api.post(`/admin/tokens/${t.id}/${action}`, { reason })
      if (!pending(r)) Notify.create({ type: 'positive', message: `Token ${label(r.status).toLowerCase()}` })
      load()
    } catch { /* shown */ }
  })
}

onMounted(load)
defineExpose({ reload: load })
</script>
