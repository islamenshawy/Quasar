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

        <q-chip dense square :color="iso.color" text-color="white" icon="lan" class="gt-sm">
          Switch {{ iso.label }}
          <q-tooltip>{{ iso.detail }}</q-tooltip>
        </q-chip>
        <q-chip dense square :color="hsm.color" text-color="white" :icon="hsm.icon" class="gt-xs"
                clickable @click="checkHsm">
          HSM {{ hsm.status }}
          <q-tooltip>{{ hsm.detail }}</q-tooltip>
        </q-chip>
        <q-btn flat round dense :icon="$q.dark.isActive ? 'light_mode' : 'dark_mode'" aria-label="Toggle dark mode"
               @click="toggleDark" />
        <q-btn flat round dense icon="how_to_reg" aria-label="Approvals" to="/approvals" class="q-ml-xs">
          <q-badge v-if="pendingApprovals" color="warning" text-color="dark" floating :label="pendingApprovals" />
          <q-tooltip>{{ pendingApprovals }} change(s) waiting for approval</q-tooltip>
        </q-btn>
        <q-btn-dropdown flat no-caps dense icon="person" :label="session.user?.username" class="q-ml-sm">
          <q-list style="min-width: 220px">
            <q-item>
              <q-item-section>
                <q-item-label>{{ session.user?.fullName }}</q-item-label>
                <q-item-label caption>{{ session.user?.roles?.join(', ') }}</q-item-label>
              </q-item-section>
            </q-item>
            <q-separator />
            <q-item clickable v-close-popup :to="{ name: 'login', query: { mode: 'password', next: $route.fullPath } }">
              <q-item-section avatar><q-icon name="password" /></q-item-section>
              <q-item-section>Change password</q-item-section>
            </q-item>
            <q-item clickable v-close-popup @click="signOut">
              <q-item-section avatar><q-icon name="logout" /></q-item-section>
              <q-item-section>Sign out</q-item-section>
            </q-item>
          </q-list>
        </q-btn-dropdown>
      </q-toolbar>
    </q-header>

    <q-drawer v-model="drawer" show-if-above bordered :width="250">
      <q-list padding>
        <template v-for="group in navGroups" :key="group.title">
          <q-item-label header class="text-uppercase text-caption">{{ group.title }}</q-item-label>
          <q-item v-for="item in group.items" :key="item.to" :to="item.to" :exact="item.exact"
                  clickable v-ripple active-class="nav-active">
            <q-item-section avatar><q-icon :name="item.icon" /></q-item-section>
            <q-item-section>{{ item.label }}</q-item-section>
            <q-item-section v-if="item.badge" side>
              <q-badge color="warning" text-color="dark" :label="item.badge" />
            </q-item-section>
          </q-item>
        </template>
      </q-list>
      <div class="q-px-md q-pb-md text-caption muted">
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
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useQuasar } from 'quasar'
import { useRouter } from 'vue-router'
import { api } from '../lib/api.js'
import { can, clearUser, session } from '../lib/session.js'
import { loadReference } from '../lib/reference.js'

const $q = useQuasar()
const drawer = ref(false)
const version = reactive({})
const hsm = reactive({ status: '…', color: 'grey-7', icon: 'memory', detail: 'Checking' })
const iso = reactive({ label: '…', color: 'grey-7', detail: 'Checking' })
const devTools = ref(false)
const pendingApprovals = ref(0)
const router = useRouter()

const nav = computed(() => [
  {
    title: 'Operations',
    items: [
      { to: '/', label: 'Dashboard', icon: 'dashboard', exact: true },
      ...(can.write ? [{ to: '/issue', label: 'Issue card', icon: 'add_card' }] : []),
      { to: '/customers', label: 'Customers', icon: 'people' },
      { to: '/accounts', label: 'Accounts', icon: 'account_balance' },
      { to: '/cards', label: 'Cards', icon: 'credit_card' },
      { to: '/transactions', label: 'Transactions', icon: 'receipt_long' },
      { to: '/approvals', label: 'Approvals', icon: 'how_to_reg', badge: pendingApprovals.value || null }
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
    items: [
      { to: '/gl', label: 'GL accounts', icon: 'account_tree' },
      { to: '/batch', label: 'Batch jobs', icon: 'schedule' },
      { to: '/audit', label: 'Audit log', icon: 'history' },
      { to: '/admin/approval-policy', label: 'Approval policy', icon: 'rule' },
      ...(can.admin ? [{ to: '/admin/users', label: 'Users', icon: 'manage_accounts' }] : [])
    ]
  }
])

const navGroups = computed(() => devTools.value && can.write
  ? [...nav.value.slice(0, 2), { title: 'Dev tools', items: [{ to: '/switch-simulator', label: 'Switch simulator', icon: 'lan' }] }, ...nav.value.slice(2)]
  : nav.value)

async function checkApprovals () {
  try {
    pendingApprovals.value = (await api.get('/admin/approvals/pending-count', { quiet: true })).pending
  } catch { /* signed out */ }
}

async function signOut () {
  await api.post('/auth/logout', {}, { quiet: true }).catch(() => {})
  clearUser()
  router.replace({ name: 'login' })
}

async function checkIso () {
  try {
    const s = await api.get('/admin/iso/status', { quiet: true })
    Object.assign(iso, !s.enabled
      ? { label: 'OFF', color: 'grey-7', detail: 'ISO interface disabled' }
      : !s.running
        ? { label: 'DOWN', color: 'negative', detail: `Not listening on ${s.port}` }
        : { label: `${s.connections} link${s.connections === 1 ? '' : 's'}`, color: s.connections ? 'positive' : 'blue-grey-6',
            detail: `Port ${s.port} · ${s.received} messages since start` })
  } catch {
    Object.assign(iso, { label: '?', color: 'grey-7', detail: 'CMS not reachable' })
  }
}

function toggleDark () {
  $q.dark.toggle()
  try { localStorage.setItem('cms.dark', String($q.dark.isActive)) } catch { /* ignore */ }
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
  loadReference().catch(() => {})
  api.get('/version', { quiet: true }).then(v => Object.assign(version, v)).catch(() => {})
  checkHsm()
  checkIso()
  checkApprovals()
  api.get('/dev/iso/status', { quiet: true }).then(() => { devTools.value = true }).catch(() => {})
  timer = setInterval(() => { checkHsm(); checkIso(); checkApprovals() }, 30000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>
