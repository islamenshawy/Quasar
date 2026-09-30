<template>
  <q-page padding class="page">
    <PageHeader title="Issue card" subtitle="Customer → account → card. The card is created pending print for Dexxis." />

    <q-stepper v-model="step" flat bordered animated header-nav color="primary" active-icon="edit" done-color="positive"
               :vertical="$q.screen.lt.md">
      <!-- 1. customer -->
      <q-step :name="1" title="Customer" icon="person" :done="!!customer"
              :caption="customer ? `${customer.fullName} · ${customer.customerRef}` : ''">
        <div class="row q-col-gutter-sm items-center q-mb-sm">
          <div class="col">
            <q-input v-model="q" dense outlined debounce="300" autofocus clearable
                     placeholder="Search by CIF, name, national ID or mobile">
              <template #prepend><q-icon name="search" /></template>
            </q-input>
          </div>
          <div class="col-auto">
            <q-btn outline no-caps color="primary" icon="person_add" label="New customer" @click="newCustomer = true" />
          </div>
        </div>
        <q-table flat dense :rows="customers" :columns="customerColumns" row-key="id" :loading="searching"
                 :pagination="{ rowsPerPage: 8 }" class="clickable-rows"
                 :no-data-label="q ? 'No customer found. Create a new one.' : 'Type to search'"
                 :row-class="r => r.id === customer?.id ? 'bg-blue-grey-1 text-dark' : ''"
                 @row-click="(e, r) => pickCustomer(r)">
          <template #body-cell-status="p"><q-td :props="p"><StatusBadge :status="p.value" /></q-td></template>
        </q-table>
      </q-step>

      <!-- 2. account -->
      <q-step :name="2" title="Account" icon="account_balance" :done="!!account" :disable="!customer"
              :caption="account ? `${account.accountNumber} · ${account.accountTypeCode} · ${account.currencyCode}` : ''">
        <q-banner v-if="customer && customer.status !== 'ACTIVE'" dense rounded class="bg-orange-1 text-dark q-mb-md">
          <template #avatar><q-icon name="warning" color="warning" /></template>
          Customer is {{ label(customer.status) }}. New accounts and cards need an active customer.
        </q-banner>
        <q-table flat dense :rows="accounts" :columns="accountColumns" row-key="id" class="clickable-rows"
                 :pagination="{ rowsPerPage: 0 }" hide-pagination no-data-label="No accounts yet. Open one."
                 :row-class="r => r.id === account?.id ? 'bg-blue-grey-1 text-dark' : ''"
                 @row-click="(e, r) => pickAccount(r)">
          <template #body-cell-status="p"><q-td :props="p"><StatusBadge :status="p.value" /></q-td></template>
        </q-table>
        <q-stepper-navigation class="row q-gutter-sm">
          <q-btn outline no-caps color="primary" icon="add" label="Open new account"
                 :disable="customer?.status !== 'ACTIVE'" @click="newAccount = true" />
          <q-btn flat no-caps label="Back" @click="step = 1" />
        </q-stepper-navigation>
      </q-step>

      <!-- 3. card -->
      <q-step :name="3" title="Card" icon="credit_card" :disable="!account">
        <IssueCardPanel v-if="account && customer" :account="account" :customer="customer" @issued="onIssued" />
        <q-stepper-navigation>
          <q-btn flat no-caps label="Back" @click="step = 2" />
        </q-stepper-navigation>
      </q-step>
    </q-stepper>

    <CustomerFormDialog v-model="newCustomer" @saved="pickCustomer" />
    <OpenAccountDialog v-model="newAccount" :customer="customer" @saved="onAccountOpened" />
  </q-page>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import CustomerFormDialog from '../components/CustomerFormDialog.vue'
import OpenAccountDialog from '../components/OpenAccountDialog.vue'
import IssueCardPanel from '../components/IssueCardPanel.vue'
import { api, qs } from '../lib/api.js'
import { label, money } from '../lib/format.js'

const route = useRoute()
const step = ref(1)
const q = ref('')
const customers = ref([])
const searching = ref(false)
const customer = ref(null)
const accounts = ref([])
const account = ref(null)
const newCustomer = ref(false)
const newAccount = ref(false)

const customerColumns = [
  { name: 'customerRef', label: 'CIF', field: 'customerRef', align: 'left', classes: 'mono' },
  { name: 'fullName', label: 'Name', field: 'fullName', align: 'left' },
  { name: 'segment', label: 'Segment', field: r => r.segmentName || r.segmentCode, align: 'left' },
  { name: 'nationalId', label: 'National ID', field: 'nationalId', align: 'left', classes: 'mono' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]
const accountColumns = [
  { name: 'accountNumber', label: 'Account', field: 'accountNumber', align: 'left', classes: 'mono' },
  { name: 'type', label: 'Type', field: r => r.accountTypeName || r.accountTypeCode, align: 'left' },
  { name: 'currencyCode', label: 'Currency', field: 'currencyCode', align: 'left' },
  { name: 'available', label: 'Available', field: r => money(r.availableBalance, r.exponent), align: 'right' },
  { name: 'liveCards', label: 'Cards', field: 'liveCards', align: 'right' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' }
]

watch(q, async v => {
  if (!v) { customers.value = []; return }
  searching.value = true
  try {
    customers.value = (await api.get('/admin/customers' + qs({ q: v, size: 25 }))).items
  } finally {
    searching.value = false
  }
})

async function pickCustomer (c) {
  customer.value = c
  account.value = null
  accounts.value = await api.get(`/admin/customers/${c.id}/accounts`)
  step.value = 2
}

function pickAccount (a) {
  account.value = a
  step.value = 3
}

function onAccountOpened (a) {
  accounts.value = [...accounts.value, a]
  pickAccount(a)
}

async function onIssued () {
  accounts.value = await api.get(`/admin/customers/${customer.value.id}/accounts`)
}

// deep link from a customer or account page: /issue?customerId=..&accountId=..
onMounted(async () => {
  const { customerId, accountId } = route.query
  if (!customerId) return
  await pickCustomer(await api.get(`/admin/customers/${customerId}`))
  const a = accounts.value.find(x => String(x.id) === String(accountId))
  if (a) pickAccount(a)
})
</script>
