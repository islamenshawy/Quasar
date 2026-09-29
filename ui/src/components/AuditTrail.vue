<template>
  <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" dense
           :pagination="{ rowsPerPage: 10 }" no-data-label="No activity recorded">
    <template #body-cell-details="p">
      <q-td :props="p" class="text-caption mono" style="white-space: normal; max-width: 520px">
        {{ summarise(p.row.details) }}
      </q-td>
    </template>
  </q-table>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api, qs } from '../lib/api.js'
import { dateTime } from '../lib/format.js'

const props = defineProps({
  entityType: { type: String, required: true },
  entityId: { type: [Number, String], required: true }
})

const rows = ref([])
const loading = ref(false)
const columns = [
  { name: 'createdAt', label: 'When', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'actor', label: 'By', field: 'actor', align: 'left' },
  { name: 'action', label: 'Action', field: 'action', align: 'left' },
  { name: 'details', label: 'Details', field: 'details', align: 'left' }
]

/** Compact one-line rendering of audit details, e.g. "status ACTIVE → SUSPENDED · reason kyc". */
function summarise (d) {
  if (!d || typeof d !== 'object') return d || ''
  if (d.from !== undefined && d.to !== undefined) {
    return `${d.from} → ${d.to}` + (d.reason ? ` · ${d.reason}` : '')
  }
  if (d.changed) {
    return Object.entries(d.changed).map(([k, v]) => `${k}: ${v.from ?? '∅'} → ${v.to ?? '∅'}`).join(' · ')
  }
  return Object.entries(d).map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`).join(' · ')
}

async function load () {
  loading.value = true
  try {
    const page = await api.get('/admin/audit' + qs({ entityType: props.entityType, entityId: props.entityId, size: 100 }))
    rows.value = page.items
  } finally {
    loading.value = false
  }
}

onMounted(load)
defineExpose({ reload: load })
</script>
