<template>
  <q-page padding class="page">
    <PageHeader title="Dashboard" subtitle="Issuance activity and portfolio status">
      <template #actions>
        <q-btn outline no-caps color="primary" icon="person_add" label="New customer" @click="newCustomer = true" />
        <q-btn unelevated no-caps color="primary" icon="add_card" label="Issue card" to="/issue" />
      </template>
    </PageHeader>

    <div class="row q-col-gutter-md">
      <div v-for="t in tiles" :key="t.label" class="col-6 col-md-3">
        <q-card flat bordered class="cursor-pointer full-height" @click="$router.push(t.to)">
          <q-card-section>
            <div class="row items-center no-wrap">
              <div class="col">
                <div class="stat-value">{{ t.value }}</div>
                <div class="stat-label">{{ t.label }}</div>
              </div>
              <q-icon :name="t.icon" size="32px" :color="t.color" />
            </div>
            <div class="text-caption muted q-mt-xs">{{ t.note }}</div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <q-banner v-if="d.lowRangeProducts?.length" rounded class="bg-orange-1 text-dark q-mt-md">
      <template #avatar><q-icon name="warning" color="warning" /></template>
      PAN range running low:
      <span v-for="p in d.lowRangeProducts" :key="p.code" class="q-mr-md">
        <router-link :to="`/setup/products/${p.code}`">{{ p.name }}</router-link> ({{ Number(p.remaining).toLocaleString() }} left)
      </span>
    </q-banner>

    <div class="row q-col-gutter-md q-mt-none">
      <div v-for="b in breakdowns" :key="b.title" class="col-12 col-md-4">
        <q-card flat bordered class="full-height">
          <q-card-section>
            <div class="text-subtitle1 text-weight-medium q-mb-sm">{{ b.title }}</div>
            <div v-if="!b.rows.length" class="muted text-body2">None yet</div>
            <div v-for="r in b.rows" :key="r.key" class="q-mb-sm cursor-pointer" @click="$router.push(b.link(r.key))">
              <div class="row justify-between text-body2">
                <span>{{ b.format(r.key) }}</span><span class="text-weight-medium">{{ r.value }}</span>
              </div>
              <q-linear-progress :value="r.value / b.total" rounded size="6px" :color="b.color(r.key)" />
            </div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <q-card flat bordered class="q-mt-md">
      <q-card-section class="row items-center">
        <div class="text-subtitle1 text-weight-medium">Recent activity</div>
        <q-space />
        <q-btn flat no-caps color="primary" label="Full audit log" to="/audit" />
      </q-card-section>
      <q-table flat dense :rows="recent" :columns="auditColumns" row-key="id" hide-pagination
               :pagination="{ rowsPerPage: 0 }" no-data-label="No activity yet" />
    </q-card>

    <CustomerFormDialog v-model="newCustomer" @saved="c => $router.push(`/customers/${c.id}`)" />
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import CustomerFormDialog from '../components/CustomerFormDialog.vue'
import { api } from '../lib/api.js'
import { dateTime, label, statusColor } from '../lib/format.js'

const d = ref({})
const recent = ref([])
const newCustomer = ref(false)

const sum = o => Object.values(o || {}).reduce((a, b) => a + b, 0)
const toRows = o => Object.entries(o || {}).map(([key, value]) => ({ key, value })).sort((a, b) => b.value - a.value)

const tiles = computed(() => [
  { label: 'Active customers', value: d.value.customers?.ACTIVE ?? 0, icon: 'people', color: 'primary',
    note: `${d.value.customersToday ?? 0} new today`, to: '/customers' },
  { label: 'Open accounts', value: sum(d.value.accounts) - (d.value.accounts?.CLOSED ?? 0), icon: 'account_balance', color: 'secondary',
    note: `${(d.value.accounts?.BLOCKED ?? 0) + (d.value.accounts?.DEBIT_BLOCKED ?? 0)} blocked`, to: '/accounts' },
  { label: 'Active cards', value: d.value.cards?.ACTIVE ?? 0, icon: 'credit_card', color: 'positive',
    note: `${d.value.activatedToday ?? 0} activated today`, to: '/cards?status=ACTIVE' },
  { label: 'Waiting for print', value: d.value.cards?.PENDING_PRINT ?? 0, icon: 'print', color: 'warning',
    note: `${d.value.issuedToday ?? 0} issued today`, to: '/cards?status=PENDING_PRINT' }
])

const breakdowns = computed(() => [
  { title: 'Cards by status', rows: toRows(d.value.cards), total: sum(d.value.cards) || 1, format: label,
    color: statusColor, link: k => `/cards?status=${k}` },
  { title: 'Cards by product', rows: toRows(d.value.cardsByProduct), total: sum(d.value.cardsByProduct) || 1, format: k => k,
    color: () => 'primary', link: k => `/cards?product=${k}` },
  { title: 'Accounts by status', rows: toRows(d.value.accounts), total: sum(d.value.accounts) || 1, format: label,
    color: statusColor, link: k => `/accounts?status=${k}` }
])

const auditColumns = [
  { name: 'createdAt', label: 'When', field: 'createdAt', format: dateTime, align: 'left' },
  { name: 'actor', label: 'By', field: 'actor', align: 'left' },
  { name: 'action', label: 'Action', field: 'action', align: 'left', format: label },
  { name: 'entity', label: 'Record', field: r => r.entityType ? `${r.entityType} ${r.entityId ?? ''}` : '', align: 'left' }
]

onMounted(async () => {
  const [dash, audit] = await Promise.all([api.get('/admin/dashboard'), api.get('/admin/audit?size=10')])
  d.value = dash
  recent.value = audit.items
})
</script>
