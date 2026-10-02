<template>
  <q-page padding class="page">
    <PageHeader title="Notifications" subtitle="Messages to cardholders: queued with the event that caused them, sent by the dispatcher, retried with backoff.">
      <template #actions>
        <q-btn flat no-caps color="primary" icon="edit_note" label="Message templates" to="/setup/message-templates" />
        <q-btn outline no-caps color="primary" icon="refresh" label="Refresh" @click="load" />
      </template>
    </PageHeader>

    <div class="row q-col-gutter-md q-mb-md">
      <div v-for="t in tiles" :key="t.label" class="col-6 col-md-3">
        <q-card flat bordered class="full-height cursor-pointer" @click="filter = t.filter; loadList()">
          <q-card-section class="row items-center no-wrap q-gutter-x-md">
            <div class="qz-tile-icon" :class="t.tone"><q-icon :name="t.icon" size="22px" /></div>
            <div><div class="stat-value" style="font-size: 26px">{{ t.value }}</div><div class="stat-label">{{ t.label }}</div></div>
          </q-card-section>
        </q-card>
      </div>
      <div class="col-6 col-md-3">
        <q-card flat bordered class="full-height">
          <q-card-section>
            <div class="stat-label q-mb-xs">Gateways</div>
            <div v-for="(v, k) in status.providers" :key="k" class="text-body2"><b>{{ k }}</b> <span class="mono">{{ v }}</span></div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <div class="row q-col-gutter-md">
      <div :class="sink ? 'col-12 col-lg-8' : 'col-12'">
        <q-card flat bordered>
          <q-card-section class="row items-center q-gutter-sm">
            <q-btn-toggle v-model="filter" no-caps unelevated toggle-color="primary" size="sm" @update:model-value="loadList"
                          :options="[{ label: 'All', value: '' }, { label: 'Pending', value: 'PENDING' }, { label: 'Failed', value: 'FAILED' }, { label: 'Sent', value: 'SENT' }]" />
            <q-select v-model="event" dense outlined clearable emit-value map-options label="Event" style="min-width: 180px"
                      :options="events.map(e => ({ label: label(e), value: e }))" @update:model-value="loadList" />
          </q-card-section>
          <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" hide-pagination :pagination="{ rowsPerPage: 0 }"
                   no-data-label="No messages">
            <template #body-cell-body="p">
              <q-td :props="p" style="white-space: normal; min-width: 300px; max-width: 460px" :dir="/[؀-ۿ]/.test(p.value) ? 'rtl' : 'ltr'">
                <div v-if="p.row.subject" class="text-weight-medium">{{ p.row.subject }}</div>{{ p.value }}
                <div v-if="p.row.lastError" class="text-caption text-negative">{{ p.row.lastError }}</div>
              </q-td>
            </template>
            <template #body-cell-customerName="p">
              <q-td :props="p"><router-link v-if="p.row.customerId" :to="`/customers/${p.row.customerId}`">{{ p.value }}</router-link></q-td>
            </template>
            <template #body-cell-status="p">
              <q-td :props="p">
                <StatusBadge :status="p.value" />
                <q-btn v-if="p.value !== 'SENT' && p.row.event !== 'OTP' && can.write" flat dense no-caps size="sm" color="primary" label="Resend" @click="resend(p.row)" />
              </q-td>
            </template>
          </q-table>
        </q-card>
      </div>

      <div v-if="sink" class="col-12 col-lg-4">
        <div class="qz-phone">
          <div class="qz-phone-top"><span>Dev SMS / e-mail sink</span>
            <q-btn flat dense round size="sm" icon="send" color="white" @click="dispatch"><q-tooltip>Send queued messages now</q-tooltip></q-btn>
            <q-btn flat dense round size="sm" icon="delete_sweep" color="white" @click="clearSink"><q-tooltip>Clear</q-tooltip></q-btn>
          </div>
          <div class="qz-phone-body">
            <div v-if="!sink.length" class="text-center text-caption q-pa-lg" style="opacity: .7">Nothing sent yet</div>
            <div v-for="s in sink" :key="s.notificationId" class="qz-bubble" :class="s.channel === 'EMAIL' ? 'mail' : ''"
                 :dir="/[؀-ۿ]/.test(s.text) ? 'rtl' : 'ltr'">
              <div class="meta">{{ s.channel }} → {{ s.to }} · {{ new Date(s.at).toLocaleTimeString() }}</div>
              <div v-if="s.subject" class="text-weight-bold">{{ s.subject }}</div>
              <div style="white-space: pre-wrap">{{ s.text }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { api, qs } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, label } from '../lib/format.js'

const events = ['TXN_APPROVED', 'TXN_DECLINED', 'CARD_ISSUED', 'CARD_ACTIVATED', 'CARD_STATUS', 'FRAUD_ALERT', 'OTP']
const status = ref({ counts: {}, providers: {} })
const rows = ref([])
const loading = ref(false)
const filter = ref('')
const event = ref(null)
const sink = ref(null)

const tiles = computed(() => [
  { label: 'Sent today', value: status.value.counts.SENT_TODAY ?? 0, icon: 'mark_chat_read', tone: 'tone-teal', filter: 'SENT' },
  { label: 'Waiting', value: status.value.counts.PENDING ?? 0, icon: 'schedule_send', tone: 'tone-blue', filter: 'PENDING' },
  { label: 'Failed', value: status.value.counts.FAILED ?? 0, icon: 'sms_failed', tone: 'tone-amber', filter: 'FAILED' }
])

const columns = [
  { name: 'createdAt', label: 'Queued', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'event', label: 'Event', field: 'event', format: label, align: 'left' },
  { name: 'channel', label: 'Channel', field: 'channel', align: 'left' },
  { name: 'customerName', label: 'Customer', field: 'customerName', align: 'left' },
  { name: 'destination', label: 'To', field: 'destination', align: 'left', classes: 'mono' },
  { name: 'body', label: 'Message', field: 'body', align: 'left' },
  { name: 'attempts', label: 'Tries', field: 'attempts', align: 'right' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function loadList () {
  loading.value = true
  try {
    rows.value = (await api.get('/admin/notifications' + qs({ status: filter.value, event: event.value, size: 100 }))).items
  } finally {
    loading.value = false
  }
}

async function loadSink () {
  try { sink.value = await api.get('/dev/notifications/sink', { quiet: true }) } catch { sink.value = null }
}

async function load () {
  status.value = await api.get('/admin/notifications/status')
  await Promise.all([loadList(), loadSink()])
}

async function resend (row) {
  await api.post(`/admin/notifications/${row.id}/resend`, {})
  Notify.create({ type: 'positive', message: 'Queued again' })
  load()
}

async function dispatch () {
  const r = await api.post('/dev/notifications/dispatch', {})
  Notify.create({ type: 'positive', message: `${r.sent} sent` })
  load()
}

async function clearSink () {
  await api.del('/dev/notifications/sink')
  loadSink()
}

onMounted(load)
</script>
