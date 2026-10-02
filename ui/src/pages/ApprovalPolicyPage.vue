<template>
  <q-page padding class="page">
    <PageHeader title="Approval policy"
                subtitle="Which actions need a second person (maker-checker). Only an administrator can change this; every change is audited." />
    <q-card flat bordered>
      <q-list separator>
        <q-item v-for="p in rows" :key="p.action">
          <q-item-section>
            <q-item-label>{{ p.description }}</q-item-label>
            <q-item-label caption class="mono">{{ p.action }}<span v-if="p.updatedBy"> · changed by {{ p.updatedBy }} {{ dateTime(p.updatedAt) }}</span></q-item-label>
          </q-item-section>
          <q-item-section side>
            <q-toggle :model-value="p.required" :disable="!can.admin" :label="p.required ? 'Needs approval' : 'Direct'"
                      left-label @update:model-value="v => set(p, v)" />
          </q-item-section>
        </q-item>
      </q-list>
    </q-card>
  </q-page>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import { api } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime } from '../lib/format.js'

const rows = ref([])

async function set (p, required) {
  try {
    rows.value = await api.put(`/admin/approval-policy/${p.action}`, { required })
    Notify.create({ type: 'positive', message: `${p.action}: ${required ? 'needs approval' : 'direct'}` })
  } catch { /* shown */ }
}

onMounted(async () => { rows.value = await api.get('/admin/approval-policy') })
</script>
