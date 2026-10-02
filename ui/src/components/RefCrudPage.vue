<template>
  <q-page padding class="page">
    <PageHeader :title="title" :subtitle="subtitle">
      <template #actions>
        <q-input v-model="filter" dense outlined debounce="200" placeholder="Filter" clearable style="min-width: 200px">
          <template #prepend><q-icon name="search" /></template>
        </q-input>
        <q-btn v-if="can.supervise" unelevated no-caps color="primary" icon="add" :label="`Add ${noun}`" @click="edit(null)" />
      </template>
    </PageHeader>

    <slot name="top" />

    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" :row-key="rowKey" :loading="loading" :filter="filter"
               :pagination="{ rowsPerPage: 25 }" class="clickable-rows" :row-class="r => r.active === false ? 'inactive-row' : ''"
               @row-click="(e, row) => edit(row)">
        <template #body-cell-active="p">
          <q-td :props="p">
            <q-badge :color="p.value ? 'positive' : 'grey-6'" :label="p.value ? 'Active' : 'Inactive'" />
          </q-td>
        </template>
        <template v-for="slot in cellSlots" :key="slot" #[`body-cell-${slot}`]="p">
          <q-td :props="p"><slot :name="`cell-${slot}`" v-bind="p" /></q-td>
        </template>
      </q-table>
    </q-card>

    <q-dialog v-model="dialog" persistent>
      <q-card style="width: 680px; max-width: 95vw">
        <q-form @submit="save">
          <q-card-section class="row items-center">
            <div class="text-h6">{{ editing ? `Edit ${noun}` : `New ${noun}` }}</div>
            <q-space />
            <q-btn flat round dense icon="close" v-close-popup aria-label="Close" />
          </q-card-section>
          <q-card-section class="q-pt-none">
            <DynamicForm :fields="fields" :model-value="form" :editing="!!editing" />
            <div v-if="editing && usageNote" class="text-caption muted q-mt-md">{{ usageNote(editing) }}</div>
          </q-card-section>
          <q-card-actions align="right" class="q-pa-md">
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn v-if="can.supervise" type="submit" unelevated no-caps color="primary" label="Save" :loading="busy" />
            <span v-else class="text-caption muted">Changes need a supervisor</span>
          </q-card-actions>
        </q-form>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from './PageHeader.vue'
import DynamicForm from './DynamicForm.vue'
import { api, pending } from '../lib/api.js'
import { can } from '../lib/session.js'
import { reloadReference } from '../lib/reference.js'

/**
 * Generic maintenance screen for a reference table: list, filter, create, edit.
 * Rows are never deleted; the "active" toggle retires them.
 */
const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  noun: { type: String, required: true },
  endpoint: { type: String, required: true }, // e.g. /admin/setup/currencies
  rowKey: { type: String, default: 'code' },
  columns: { type: Array, required: true },
  fields: { type: Array, required: true },
  defaults: { type: Function, default: () => ({ active: true }) },
  cellSlots: { type: Array, default: () => [] },
  usageNote: { type: Function, default: null }
})
const emit = defineEmits(['saved'])

const rows = ref([])
const loading = ref(false)
const filter = ref('')
const dialog = ref(false)
const editing = ref(null)
const busy = ref(false)
const form = reactive({})

async function load () {
  loading.value = true
  try {
    rows.value = await api.get(props.endpoint)
  } finally {
    loading.value = false
  }
}

function edit (row) {
  editing.value = row
  for (const k of Object.keys(form)) delete form[k]
  Object.assign(form, row ? JSON.parse(JSON.stringify(row)) : props.defaults())
  dialog.value = true
}

async function save () {
  busy.value = true
  try {
    const key = editing.value?.[props.rowKey]
    const saved = editing.value
      ? await api.put(`${props.endpoint}/${encodeURIComponent(key)}`, form)
      : await api.post(props.endpoint, form)
    if (!pending(saved)) Notify.create({ type: 'positive', message: `${props.noun.charAt(0).toUpperCase() + props.noun.slice(1)} ${saved[props.rowKey]} saved` })
    dialog.value = false
    await load()
    reloadReference().catch(() => {})
    emit('saved', saved)
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

onMounted(load)
defineExpose({ reload: load })
</script>
