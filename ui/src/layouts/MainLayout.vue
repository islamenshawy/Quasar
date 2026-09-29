<template>
  <q-layout view="hHh LpR fFf">
    <q-header elevated class="bg-secondary text-white">
      <q-toolbar>
        <q-btn flat dense round icon="menu" aria-label="Menu" @click="drawer = !drawer" />
        <q-toolbar-title class="row items-center no-wrap q-gutter-x-sm">
          <q-icon name="credit_card" />
          <span class="text-weight-medium">CMS Console</span>
          <q-badge color="warning" text-color="dark" label="TEST" class="gt-xs" />
        </q-toolbar-title>

        <q-chip dense square :color="hsm.color" text-color="white" :icon="hsm.icon" class="gt-xs"
                clickable @click="checkHsm">
          HSM {{ hsm.status }}
          <q-tooltip>{{ hsm.detail }}</q-tooltip>
        </q-chip>
        <q-btn flat round dense :icon="$q.dark.isActive ? 'light_mode' : 'dark_mode'" aria-label="Toggle dark mode"
               @click="toggleDark" />
        <q-btn flat no-caps dense icon="person" :label="session.operator || 'Set operator'" class="q-ml-sm"
               @click="askOperator(false)">
          <q-tooltip>Operator id sent with every change (test mode)</q-tooltip>
        </q-btn>
      </q-toolbar>
    </q-header>

    <q-drawer v-model="drawer" show-if-above bordered :width="250">
      <q-list padding>
        <template v-for="group in nav" :key="group.title">
          <q-item-label header class="text-uppercase text-caption">{{ group.title }}</q-item-label>
          <q-item v-for="item in group.items" :key="item.to" :to="item.to" :exact="item.exact"
                  clickable v-ripple active-class="nav-active">
            <q-item-section avatar><q-icon :name="item.icon" /></q-item-section>
            <q-item-section>{{ item.label }}</q-item-section>
          </q-item>
        </template>
      </q-list>
      <div class="q-pa-md text-caption muted absolute-bottom">
        cms-core {{ version.version || '…' }}
      </div>
    </q-drawer>

    <q-page-container>
      <router-view v-slot="{ Component, route }">
        <component :is="Component" :key="route.path" />
      </router-view>
    </q-page-container>
  </q-layout>
</template>

<script setup>
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useQuasar } from 'quasar'
import { api } from '../lib/api.js'
import { session, setOperator } from '../lib/session.js'
import { loadReference } from '../lib/reference.js'

const $q = useQuasar()
const drawer = ref(false)
const version = reactive({})
const hsm = reactive({ status: '…', color: 'grey-7', icon: 'memory', detail: 'Checking' })

const nav = [
  {
    title: 'Operations',
    items: [
      { to: '/', label: 'Dashboard', icon: 'dashboard', exact: true },
      { to: '/issue', label: 'Issue card', icon: 'add_card' },
      { to: '/customers', label: 'Customers', icon: 'people' },
      { to: '/accounts', label: 'Accounts', icon: 'account_balance' },
      { to: '/cards', label: 'Cards', icon: 'credit_card' }
    ]
  },
  {
    title: 'Setup',
    items: [
      { to: '/setup/products', label: 'Card products', icon: 'style' },
      { to: '/setup/account-types', label: 'Account types', icon: 'category' },
      { to: '/setup/segments', label: 'Customer segments', icon: 'groups' },
      { to: '/setup/currencies', label: 'Currencies', icon: 'payments' },
      { to: '/setup/numbering', label: 'Numbering & settings', icon: 'pin' }
    ]
  },
  {
    title: 'Control',
    items: [{ to: '/audit', label: 'Audit log', icon: 'history' }]
  }
]

function toggleDark () {
  $q.dark.toggle()
  try { localStorage.setItem('cms.dark', String($q.dark.isActive)) } catch { /* ignore */ }
}

function askOperator (required) {
  $q.dialog({
    title: 'Operator',
    message: 'Your operator id is recorded on every change. Test mode only: real sign-in replaces this (CMS-060).',
    prompt: { model: session.operator, type: 'text', isValid: v => /^[A-Za-z0-9._-]{2,64}$/.test(v || ''), outlined: true },
    persistent: required,
    cancel: !required
  }).onOk(v => setOperator(v))
}

async function checkHsm () {
  try {
    const r = await fetch('/api/admin/hsm/health')
    const b = await r.json()
    Object.assign(hsm, r.ok
      ? { status: 'UP', color: 'positive', icon: 'memory', detail: `LMK check ${b.lmkCheckValue} · firmware ${b.firmware}` }
      : { status: 'DOWN', color: 'negative', icon: 'warning', detail: b.reason || 'Unavailable' })
  } catch {
    Object.assign(hsm, { status: '?', color: 'grey-7', icon: 'help', detail: 'CMS not reachable' })
  }
}

let timer
onMounted(async () => {
  try {
    const d = localStorage.getItem('cms.dark')
    if (d !== null) $q.dark.set(d === 'true')
  } catch { /* ignore */ }
  if (!session.operator) askOperator(true)
  loadReference().catch(() => {})
  api.get('/version', { quiet: true }).then(v => Object.assign(version, v)).catch(() => {})
  checkHsm()
  timer = setInterval(checkHsm, 30000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>
