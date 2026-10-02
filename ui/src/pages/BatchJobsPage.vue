<template>
  <q-page padding class="page">
    <PageHeader title="Batch jobs" subtitle="Scheduled housekeeping: expiry, renewal, holds, uncollected prints. Supervisors can run a job now; schedule changes need approval." />

    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" row-key="code" :loading="loading" hide-pagination :pagination="{ rowsPerPage: 0 }">
        <template #body-cell-name="p">
          <q-td :props="p">
            <div class="text-weight-medium">{{ p.row.name }}</div>
            <div class="text-caption muted" style="white-space: normal">{{ p.row.description }}</div>
          </q-td>
        </template>
        <template #body-cell-schedule="p">
          <q-td :props="p">
            <span class="mono">{{ p.row.cron }}</span>
            <q-badge v-if="!p.row.enabled" color="grey-6" label="off" class="q-ml-xs" />
            <div class="text-caption muted">{{ p.row.enabled ? 'next ' + dateTime(p.row.nextRun) : '' }}</div>
          </q-td>
        </template>
        <template #body-cell-last="p">
          <q-td :props="p">
            <template v-if="p.row.lastRun">
              <q-badge :color="runColor(p.row.lastRun.status)" :label="p.row.lastRun.status" />
              <span class="text-caption q-ml-xs">{{ dateTime(p.row.lastRun.startedAt) }} · {{ p.row.lastRun.triggeredBy }}</span>
              <div class="text-caption muted" style="white-space: normal">{{ p.row.lastRun.message }}</div>
            </template>
            <span v-else class="muted">never</span>
            <q-badge v-if="p.row.running" color="info" label="running" class="q-ml-xs" />
          </q-td>
        </template>
        <template #body-cell-actions="p">
          <q-td :props="p" auto-width>
            <q-btn flat round dense icon="history" @click="history(p.row)"><q-tooltip>Run history</q-tooltip></q-btn>
            <template v-if="can.supervise">
              <q-btn flat dense no-caps color="primary" icon="play_arrow" round :loading="runningCode === p.row.code"
                     :disable="p.row.running" @click="run(p.row)"><q-tooltip>Run now</q-tooltip></q-btn>
              <q-btn flat round dense icon="edit_calendar" @click="edit(p.row)"><q-tooltip>Change schedule</q-tooltip></q-btn>
            </template>
          </q-td>
        </template>
      </q-table>
    </q-card>

    <q-dialog v-model="histDialog">
      <q-card style="width: 760px; max-width: 95vw">
        <q-card-section class="text-h6">{{ histJob?.name }} · runs</q-card-section>
        <q-table flat dense :rows="runs" :columns="runColumns" row-key="id" :pagination="{ rowsPerPage: 15 }">
          <template #body-cell-status="p"><q-td :props="p"><q-badge :color="runColor(p.value)" :label="p.value" /></q-td></template>
        </q-table>
        <q-card-actions align="right"><q-btn flat no-caps label="Close" v-close-popup /></q-card-actions>
      </q-card>
    </q-dialog>

    <q-dialog v-model="editDialog" persistent>
      <q-card style="width: 460px; max-width: 95vw">
        <q-form @submit="saveSchedule">
          <q-card-section class="text-h6">{{ editing?.name }} schedule</q-card-section>
          <q-card-section class="q-pt-none q-gutter-md">
            <q-input v-model="sched.cron" outlined dense label="Cron *" input-class="mono"
                     hint="second minute hour day month weekday, e.g. 0 30 0 * * * = every day 00:30"
                     :rules="[v => (v || '').trim().split(/\s+/).length === 6 || 'Six fields']" />
            <q-toggle v-model="sched.enabled" label="Enabled" />
          </q-card-section>
          <q-card-actions align="right" class="q-pa-md">
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn type="submit" unelevated no-caps color="primary" label="Save" />
          </q-card-actions>
        </q-form>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import { api, pending } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime } from '../lib/format.js'

const rows = ref([])
const loading = ref(false)
const runningCode = ref(null)
const histDialog = ref(false)
const histJob = ref(null)
const runs = ref([])
const editDialog = ref(false)
const editing = ref(null)
const sched = reactive({ cron: '', enabled: true })

const runColor = s => ({ SUCCESS: 'positive', FAILED: 'negative', RUNNING: 'info' }[s] || 'grey-6')
const columns = [
  { name: 'name', label: 'Job', field: 'name', align: 'left' },
  { name: 'schedule', label: 'Schedule', field: 'cron', align: 'left' },
  { name: 'last', label: 'Last run', field: r => r.lastRun?.startedAt, align: 'left', style: 'max-width: 380px' },
  { name: 'actions', label: '', field: 'code', align: 'right' }
]
const runColumns = [
  { name: 'id', label: '#', field: 'id', align: 'right' },
  { name: 'startedAt', label: 'Started', field: 'startedAt', format: dateTime, align: 'left' },
  { name: 'finishedAt', label: 'Finished', field: 'finishedAt', format: dateTime, align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'items', label: 'Items', field: 'items', align: 'right' },
  { name: 'triggeredBy', label: 'By', field: 'triggeredBy', align: 'left' },
  { name: 'message', label: 'Message', field: 'message', align: 'left', style: 'white-space: normal' }
]

async function load () {
  loading.value = true
  try { rows.value = await api.get('/admin/batch/jobs') } finally { loading.value = false }
}

async function run (j) {
  runningCode.value = j.code
  try {
    const r = await api.post(`/admin/batch/jobs/${j.code}/run`, {})
    Notify.create({ type: r.status === 'SUCCESS' ? 'positive' : 'negative', message: `${j.name}: ${r.status}`, caption: r.message })
    load()
  } catch { /* shown */ } finally {
    runningCode.value = null
  }
}

async function history (j) {
  histJob.value = j
  runs.value = (await api.get(`/admin/batch/jobs/${j.code}/runs?size=100`)).items
  histDialog.value = true
}

function edit (j) {
  editing.value = j
  Object.assign(sched, { cron: j.cron, enabled: j.enabled })
  editDialog.value = true
}

async function saveSchedule () {
  try {
    const r = await api.put(`/admin/batch/jobs/${editing.value.code}`, { ...sched })
    if (!pending(r)) Notify.create({ type: 'positive', message: 'Schedule saved' })
    editDialog.value = false
    load()
  } catch { /* shown */ }
}

onMounted(load)
</script>
