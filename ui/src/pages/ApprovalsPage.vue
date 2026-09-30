<template>
  <q-page padding class="page">
    <PageHeader title="Approvals"
                subtitle="Maker-checker: changes wait here until a second supervisor approves them. You never approve your own." />

    <q-card flat bordered>
      <q-tabs v-model="tab" align="left" no-caps dense active-color="primary" indicator-color="primary" class="q-px-sm">
        <q-tab name="PENDING" :label="`Waiting (${counts.PENDING ?? 0})`" />
        <q-tab name="MINE" label="Submitted by me" />
        <q-tab name="HISTORY" label="History" />
      </q-tabs>
      <q-separator />
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" v-model:pagination="pagination"
               :rows-per-page-options="[25, 50, 100]" no-data-label="Nothing here" @request="load"
               class="clickable-rows" @row-click="(e, r) => open(r)">
        <template #body-cell-status="p">
          <q-td :props="p"><q-badge :color="color(p.value)" :label="label(p.value)" /></q-td>
        </template>
        <template #body-cell-actions="p">
          <q-td :props="p" auto-width @click.stop>
            <template v-if="p.row.status === 'PENDING'">
              <template v-if="can.supervise && p.row.maker !== me">
                <q-btn flat dense no-caps color="positive" icon="check" label="Approve" @click="decide(p.row, 'approve')" />
                <q-btn flat dense no-caps color="negative" icon="close" label="Reject" @click="decide(p.row, 'reject')" />
              </template>
              <q-btn v-if="p.row.maker === me" flat dense no-caps label="Withdraw" @click="cancel(p.row)" />
            </template>
          </q-td>
        </template>
      </q-table>
    </q-card>

    <q-dialog v-model="dialog">
      <q-card v-if="current" style="width: 720px; max-width: 95vw">
        <q-card-section class="row items-center">
          <div>
            <div class="text-h6">#{{ current.id }} · {{ current.description }}</div>
            <div class="text-body2 muted">{{ current.summary }}</div>
          </div>
          <q-space />
          <q-badge :color="color(current.status)" :label="label(current.status)" class="text-body2 q-pa-sm" />
        </q-card-section>
        <q-card-section class="q-pt-none">
          <dl class="dl">
            <dt>Maker</dt><dd>{{ current.maker }} · {{ dateTime(current.madeAt) }}</dd>
            <dt>Checker</dt><dd>{{ current.checker ? `${current.checker} · ${dateTime(current.checkedAt)}` : '—' }}</dd>
            <dt>Comment</dt><dd>{{ current.checkerComment || '—' }}</dd>
            <dt v-if="current.error">Error</dt><dd v-if="current.error" class="text-negative">{{ current.error }}</dd>
            <dt>Record</dt><dd>{{ current.entityType }} {{ current.entityId }}</dd>
          </dl>
          <div class="text-caption text-uppercase muted q-mt-md q-mb-xs">Requested change</div>
          <pre class="mono text-caption q-pa-sm rounded-borders payload">{{ JSON.stringify(current.payload, null, 2) }}</pre>
        </q-card-section>
        <q-card-actions align="right">
          <template v-if="current.status === 'PENDING' && can.supervise && current.maker !== me">
            <q-btn flat no-caps color="negative" label="Reject" @click="decide(current, 'reject')" />
            <q-btn unelevated no-caps color="positive" label="Approve" @click="decide(current, 'approve')" />
          </template>
          <q-btn flat no-caps label="Close" v-close-popup />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Notify, useQuasar } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import { api, qs } from '../lib/api.js'
import { can, session } from '../lib/session.js'
import { dateTime, label } from '../lib/format.js'

const $q = useQuasar()
const me = computed(() => session.user?.username)
const tab = ref('PENDING')
const rows = ref([])
const loading = ref(false)
const pagination = ref({ page: 1, rowsPerPage: 25, rowsNumber: 0 })
const counts = reactive({})
const dialog = ref(false)
const current = ref(null)

const color = s => ({ PENDING: 'warning', APPROVED: 'positive', REJECTED: 'negative', FAILED: 'negative', CANCELLED: 'grey-6' }[s] || 'grey-6')
const columns = [
  { name: 'id', label: '#', field: 'id', align: 'right' },
  { name: 'madeAt', label: 'Submitted', field: 'madeAt', format: dateTime, align: 'left' },
  { name: 'maker', label: 'Maker', field: 'maker', align: 'left' },
  { name: 'description', label: 'Action', field: 'description', align: 'left' },
  { name: 'summary', label: 'Change', field: 'summary', align: 'left', style: 'white-space: normal; max-width: 420px' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'checker', label: 'Checker', field: r => r.checker || '', align: 'left' },
  { name: 'actions', label: '', field: 'id', align: 'right' }
]

async function load ({ pagination: p } = { pagination: pagination.value }) {
  loading.value = true
  const filter = tab.value === 'PENDING' ? { status: 'PENDING' } : tab.value === 'MINE' ? { maker: me.value } : {}
  try {
    const page = await api.get('/admin/approvals' + qs({ ...filter, page: p.page - 1, size: p.rowsPerPage }))
    rows.value = page.items
    pagination.value = { ...p, rowsNumber: page.total }
    counts.PENDING = (await api.get('/admin/approvals/pending-count')).pending
  } finally {
    loading.value = false
  }
}

function open (r) {
  current.value = r
  dialog.value = true
}

function decide (r, what) {
  $q.dialog({
    title: what === 'approve' ? `Approve #${r.id}` : `Reject #${r.id}`,
    message: r.summary,
    prompt: { model: '', type: 'text', label: what === 'approve' ? 'Comment (optional)' : 'Reason *', outlined: true,
      isValid: v => what === 'approve' || !!(v && v.trim()) },
    cancel: true,
    ok: { label: what === 'approve' ? 'Approve' : 'Reject', color: what === 'approve' ? 'positive' : 'negative', unelevated: true, noCaps: true }
  }).onOk(async comment => {
    const res = await api.post(`/admin/approvals/${r.id}/${what}`, { comment })
    Notify.create(res.status === 'FAILED'
      ? { type: 'negative', message: `#${r.id} could not be applied`, caption: res.error }
      : { type: 'positive', message: `#${r.id} ${label(res.status).toLowerCase()}` })
    dialog.value = false
    load()
  })
}

async function cancel (r) {
  await api.post(`/admin/approvals/${r.id}/cancel`, {})
  Notify.create({ type: 'info', message: `#${r.id} withdrawn` })
  load()
}

watch(tab, () => load({ pagination: { ...pagination.value, page: 1 } }))
onMounted(() => load())
</script>

<style scoped>
.payload { background: rgba(0, 0, 0, .04); max-height: 320px; overflow: auto; white-space: pre-wrap; }
.body--dark .payload { background: rgba(255, 255, 255, .06); }
</style>
