<template>
  <q-page padding class="page">
    <PageHeader title="Transactions" subtitle="Every authorization from the ATM / POS switch, approved or declined" />

    <q-card flat bordered>
      <q-card-section class="row q-col-gutter-sm">
        <div class="col-12 col-md-3">
          <q-input v-model="filters.q" dense outlined debounce="300" clearable placeholder="STAN, RRN, terminal, auth id, last 4">
            <template #prepend><q-icon name="search" /></template>
          </q-input>
        </div>
        <div class="col-6 col-md-2">
          <q-select v-model="filters.type" dense outlined clearable label="Type" emit-value map-options
                    :options="types.map(t => ({ label: label(t), value: t }))" />
        </div>
        <div class="col-6 col-md-2">
          <q-select v-model="filters.channel" dense outlined clearable label="Channel" :options="['ATM', 'POS', 'ECOM', 'OTHER']" />
        </div>
        <div class="col-6 col-md-1">
          <q-select v-model="filters.result" dense outlined clearable label="Result" :options="['APPROVED', 'DECLINED']" />
        </div>
        <div class="col-6 col-md-2">
          <q-input v-model="filters.from" dense outlined type="date" label="From" stack-label clearable />
        </div>
        <div class="col-6 col-md-2">
          <q-input v-model="filters.to" dense outlined type="date" label="To" stack-label clearable />
        </div>
      </q-card-section>
      <TxnTable :filters="query" />
    </q-card>
  </q-page>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import TxnTable from '../components/TxnTable.vue'
import { label } from '../lib/format.js'

const route = useRoute()
const types = ['BALANCE_INQUIRY', 'WITHDRAWAL', 'PURCHASE', 'PREAUTH', 'COMPLETION', 'REFUND', 'PIN_CHANGE', 'REVERSAL']
const filters = reactive({ q: '', type: null, channel: null, result: route.query.result || null, from: '', to: '' })
const query = computed(() => ({ ...filters }))
</script>
