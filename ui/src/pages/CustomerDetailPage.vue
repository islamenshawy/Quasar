<template>
  <q-page padding class="page">
    <template v-if="c">
      <PageHeader :title="c.fullName" back="/customers">
        <template #badge><StatusBadge :status="c.status" /></template>
        <template #subtitle>
          CIF <span class="mono">{{ c.customerRef }}</span> · {{ c.segmentName || c.segmentCode }} · {{ label(c.customerType) }}
          <span v-if="c.statusReason"> · {{ label(c.status) }}: {{ c.statusReason }}</span>
        </template>
        <template #actions>
          <q-btn outline no-caps color="primary" icon="edit" label="Edit" :disable="c.status === 'CLOSED'" @click="editDialog = true" />
          <StatusAction :current="c.status" :targets="targets" entity="customer" :reason-optional="['ACTIVE']" :on-change="changeStatus" />
          <q-btn unelevated no-caps color="primary" icon="add" label="Open account" :disable="c.status !== 'ACTIVE'"
                 @click="accountDialog = true" />
        </template>
      </PageHeader>

      <q-card flat bordered>
        <q-tabs v-model="tab" align="left" no-caps active-color="primary" indicator-color="primary" dense class="q-px-sm">
          <q-tab name="overview" label="Overview" />
          <q-tab name="accounts" :label="`Accounts (${accounts.length})`" />
          <q-tab name="cards" :label="`Cards (${cards.length})`" />
          <q-tab name="activity" label="Activity" />
        </q-tabs>
        <q-separator />
        <q-tab-panels v-model="tab" animated>
          <q-tab-panel name="overview">
            <div class="row q-col-gutter-lg">
              <div class="col-12 col-md-6">
                <dl class="dl">
                  <dt>Full name</dt><dd>{{ c.fullName }}</dd>
                  <dt>Name on card</dt><dd class="mono">{{ c.embossingName }}</dd>
                  <dt>National ID</dt><dd class="mono">{{ c.nationalId || '—' }}</dd>
                  <dt>Date of birth</dt><dd>{{ c.dateOfBirth ? date(c.dateOfBirth) : '—' }}</dd>
                  <dt>Mobile</dt><dd>{{ c.mobile || '—' }}</dd>
                  <dt>Email</dt><dd>{{ c.email || '—' }}</dd>
                  <dt>Address</dt><dd>{{ c.address || '—' }}</dd>
                </dl>
              </div>
              <div class="col-12 col-md-6">
                <dl class="dl">
                  <dt>CIF</dt><dd class="mono">{{ c.customerRef }}</dd>
                  <dt>Segment</dt><dd>{{ c.segmentName }} <span class="mono muted">{{ c.segmentCode }}</span></dd>
                  <dt>Open accounts</dt><dd>{{ c.accounts }}</dd>
                  <dt>Live cards</dt><dd>{{ c.liveCards }}</dd>
                  <dt>Created</dt><dd>{{ dateTime(c.createdAt) }} by {{ c.createdBy || '—' }}</dd>
                  <dt>Last change</dt><dd>{{ c.updatedAt ? `${dateTime(c.updatedAt)} by ${c.updatedBy}` : '—' }}</dd>
                </dl>
              </div>
            </div>
          </q-tab-panel>

          <q-tab-panel name="accounts" class="q-pa-none">
            <q-table flat :rows="accounts" :columns="accountColumns" row-key="id" class="clickable-rows"
                     :pagination="{ rowsPerPage: 0 }" hide-pagination no-data-label="No accounts yet"
                     @row-click="(e, r) => $router.push(`/accounts/${r.id}`)">
              <template #body-cell-status="p"><q-td :props="p"><StatusBadge :status="p.value" /></q-td></template>
              <template #body-cell-actions="p">
                <q-td :props="p" auto-width>
                  <q-btn v-if="p.row.status === 'ACTIVE' && c.status === 'ACTIVE'" flat dense no-caps color="primary"
                         icon="add_card" label="Issue card" @click.stop="$router.push({ path: '/issue', query: { customerId: c.id, accountId: p.row.id } })" />
                </q-td>
              </template>
            </q-table>
          </q-tab-panel>

          <q-tab-panel name="cards" class="q-pa-none">
            <q-table flat :rows="cards" :columns="cardColumns" row-key="id" class="clickable-rows"
                     :pagination="{ rowsPerPage: 0 }" hide-pagination no-data-label="No cards yet"
                     @row-click="(e, r) => $router.push(`/cards/${r.id}`)">
              <template #body-cell-status="p"><q-td :props="p"><StatusBadge :status="p.value" /></q-td></template>
            </q-table>
          </q-tab-panel>

          <q-tab-panel name="activity" class="q-pa-none">
            <AuditTrail ref="trail" entity-type="customer" :entity-id="c.id" />
          </q-tab-panel>
        </q-tab-panels>
      </q-card>

      <CustomerFormDialog v-model="editDialog" :customer="c" @saved="load" />
      <OpenAccountDialog v-model="accountDialog" :customer="c" @saved="a => { tab = 'accounts'; load() }" />
    </template>
    <div v-else class="flex flex-center q-pa-xl"><q-spinner size="40px" color="primary" /></div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import StatusAction from '../components/StatusAction.vue'
import AuditTrail from '../components/AuditTrail.vue'
import CustomerFormDialog from '../components/CustomerFormDialog.vue'
import OpenAccountDialog from '../components/OpenAccountDialog.vue'
import { api } from '../lib/api.js'
import { date, dateTime, expiry, label, money } from '../lib/format.js'

const props = defineProps({ id: { type: String, required: true } })

const c = ref(null)
const accounts = ref([])
const cards = ref([])
const tab = ref('overview')
const editDialog = ref(false)
const accountDialog = ref(false)
const trail = ref(null)

const targets = computed(() => ({
  ACTIVE: ['SUSPENDED', 'CLOSED'],
  SUSPENDED: ['ACTIVE', 'CLOSED']
})[c.value?.status] || [])

const accountColumns = [
  { name: 'accountNumber', label: 'Account', field: 'accountNumber', align: 'left', classes: 'mono' },
  { name: 'type', label: 'Type', field: r => r.accountTypeName || r.accountTypeCode, align: 'left' },
  { name: 'currencyCode', label: 'Currency', field: 'currencyCode', align: 'left' },
  { name: 'available', label: 'Available', field: r => money(r.availableBalance, r.exponent), align: 'right' },
  { name: 'liveCards', label: 'Cards', field: 'liveCards', align: 'right' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'actions', label: '', field: 'id', align: 'right' }
]

const cardColumns = [
  { name: 'maskedPan', label: 'Card', field: 'maskedPan', align: 'left', classes: 'mono' },
  { name: 'product', label: 'Product', field: r => r.productName, align: 'left' },
  { name: 'accountNumber', label: 'Account', field: 'accountNumber', align: 'left', classes: 'mono' },
  { name: 'expiry', label: 'Expiry', field: 'expiryYYMM', format: expiry, align: 'left' },
  { name: 'createdAt', label: 'Issued', field: 'createdAt', format: date, align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

async function load () {
  const [cust, accts, crds] = await Promise.all([
    api.get(`/admin/customers/${props.id}`),
    api.get(`/admin/customers/${props.id}/accounts`),
    api.get(`/admin/customers/${props.id}/cards`)])
  c.value = cust
  accounts.value = accts
  cards.value = crds
  trail.value?.reload()
}

async function changeStatus (status, reason) {
  c.value = await api.post(`/admin/customers/${props.id}/status`, { status, reason })
  Notify.create({ type: 'positive', message: `Customer is now ${label(status).toLowerCase()}` })
  load()
}

onMounted(load)
</script>
