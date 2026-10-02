<template>
  <q-layout view="lHh LpR lFf">
    <!-- top bar: the command bar is the centre of gravity, system pulse on the right -->
    <q-header class="qz-topbar">
      <q-toolbar class="q-gutter-x-sm" style="min-height: 64px">
        <q-btn flat dense round icon="menu" aria-label="Menu" class="lt-md" @click="drawer = !drawer" />
        <div class="qz-crumb gt-sm">
          <span class="muted">{{ section }}</span><q-icon name="chevron_right" size="16px" class="muted" />
          <span class="text-weight-semibold">{{ $route.meta.title }}</span>
        </div>
        <button class="qz-search" type="button" @click="cmd = true">
          <q-icon name="search" size="20px" />
          <span class="ellipsis">Search or jump to…</span>
          <span class="gt-xs q-ml-auto"><kbd class="qz-kbd">Ctrl</kbd><kbd class="qz-kbd">K</kbd></span>
        </button>
        <q-space class="gt-sm" />

        <div class="qz-pulse gt-xs" role="status" aria-label="System status">
          <span class="qz-pill gt-sm"><span class="qz-dot" :class="iso.tone" />Switch {{ iso.label }}<q-tooltip>{{ iso.detail }}</q-tooltip></span>
          <router-link v-if="coreHost.shown" to="/core-banking" class="qz-pill clickable gt-sm" style="text-decoration: none">
            <span class="qz-dot" :class="coreHost.tone" />Core {{ coreHost.label }}<q-tooltip>{{ coreHost.detail }}</q-tooltip>
          </router-link>
          <span class="qz-pill clickable" role="button" tabindex="0" @click="checkHsm" @keyup.enter="checkHsm">
            <span class="qz-dot" :class="hsm.tone" />HSM {{ hsm.status }}<q-tooltip>{{ hsm.detail }} · click to recheck</q-tooltip>
          </span>
        </div>
        <q-btn flat round dense icon="how_to_reg" aria-label="Approvals" to="/approvals">
          <q-badge v-if="pendingApprovals" color="accent" floating rounded :label="pendingApprovals" />
          <q-tooltip>{{ pendingApprovals }} change(s) waiting for approval</q-tooltip>
        </q-btn>
        <q-btn flat round dense :icon="$q.dark.isActive ? 'light_mode' : 'dark_mode'" aria-label="Toggle theme" @click="toggleDark">
          <q-tooltip>{{ $q.dark.isActive ? 'Quasar Light' : 'Deep-space dark' }}</q-tooltip>
        </q-btn>
        <q-btn flat round dense aria-label="Account" class="q-ml-xs">
          <span class="qz-avatar">{{ initials }}</span>
          <q-menu anchor="bottom right" self="top right" :offset="[0, 8]">
            <q-list style="min-width: 240px">
              <q-item>
                <q-item-section avatar><span class="qz-avatar lg">{{ initials }}</span></q-item-section>
                <q-item-section>
                  <q-item-label class="text-weight-semibold">{{ session.user?.fullName }}</q-item-label>
                  <q-item-label caption>{{ session.user?.username }} · {{ session.user?.roles?.join(', ') }}</q-item-label>
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
          </q-menu>
        </q-btn>
      </q-toolbar>
    </q-header>

    <!-- the orbit rail: deep space, icons only until pinned open -->
    <q-drawer v-model="drawer" show-if-above :mini="railMini" :mini-width="76" :width="256" :breakpoint="1023"
              class="qz-rail-drawer">
      <div class="qz-rail column no-wrap full-height" :class="{ 'is-mini': railMini }">
        <router-link to="/" class="qz-rail-brand" aria-label="Quasar home">
          <QuasarMark :size="railMini ? 40 : 44" animated />
          <div v-if="!railMini" class="q-ml-sm">
            <div class="qz-wordmark qz-gradient-text" style="font-size: 23px">Quasar</div>
            <div class="qz-rail-sub">CARD MANAGEMENT</div>
          </div>
        </router-link>
        <div class="qz-rail-env" :class="{ mini: railMini }">TEST</div>

        <q-scroll-area class="col">
          <q-list>
            <template v-for="(group, gi) in groups" :key="group.title">
              <div v-if="!railMini" class="qz-rail-group">{{ group.title }}</div>
              <div v-else-if="gi" class="qz-rail-sep" />
              <q-item v-for="item in group.items" :key="item.to" :to="item.to" :exact="item.exact"
                      clickable v-ripple active-class="qz-rail-active" class="qz-rail-item">
                <q-item-section avatar>
                  <q-icon :name="item.icon" size="22px" />
                  <q-badge v-if="item.badge && railMini" color="accent" floating rounded :label="item.badge" />
                </q-item-section>
                <q-item-section>{{ item.label }}</q-item-section>
                <q-item-section v-if="item.badge" side><q-badge color="accent" rounded :label="item.badge" /></q-item-section>
                <q-tooltip v-if="railMini" anchor="center right" self="center left" :offset="[12, 0]" class="qz-rail-tip">{{ item.label }}</q-tooltip>
              </q-item>
            </template>
          </q-list>
        </q-scroll-area>

        <div class="qz-rail-foot">
          <q-btn flat dense round class="gt-sm" :icon="railMini ? 'keyboard_double_arrow_right' : 'keyboard_double_arrow_left'"
                 :aria-label="railMini ? 'Expand navigation' : 'Collapse navigation'" @click="toggleMini">
            <q-tooltip anchor="center right" self="center left">{{ railMini ? 'Pin navigation open' : 'Collapse to rail' }}</q-tooltip>
          </q-btn>
          <span v-if="!railMini" class="qz-rail-version">v{{ version.version || '…' }}</span>
        </div>
      </div>
    </q-drawer>

    <q-page-container>
      <router-view v-slot="{ Component, route }">
        <transition name="qz-page" mode="out-in">
          <component :is="Component" :key="route.path" />
        </transition>
      </router-view>
    </q-page-container>

    <CommandBar v-model="cmd" :dev-tools="devTools" />
  </q-layout>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useQuasar } from 'quasar'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api.js'
import QuasarMark from '../components/QuasarMark.vue'
import CommandBar from '../components/CommandBar.vue'
import { clearUser, session } from '../lib/session.js'
import { loadReference } from '../lib/reference.js'
import { navGroups } from '../lib/nav.js'

const $q = useQuasar()
const route = useRoute()
const router = useRouter()
const drawer = ref(false)
const mini = ref(true)
const cmd = ref(false)
const version = reactive({})
const hsm = reactive({ status: '…', tone: '', detail: 'Checking' })
const iso = reactive({ label: '…', tone: '', detail: 'Checking' })
const coreHost = reactive({ shown: false, label: '…', tone: '', detail: 'Checking' })
const devTools = ref(false)
const pendingApprovals = ref(0)
const openFraud = ref(0)

const railMini = computed(() => mini.value && $q.screen.gt.sm)
const groups = computed(() => navGroups({ devTools: devTools.value, pending: pendingApprovals.value, fraud: openFraud.value }))
const section = computed(() => {
  const path = route.path
  const hit = groups.value.find(g => g.items.some(i => i.to !== '/' && path.startsWith(i.to)))
  return hit ? hit.title : 'Operations'
})
const initials = computed(() => {
  const n = session.user?.fullName || session.user?.username || '?'
  return n.split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase()
})

function toggleMini () {
  mini.value = !mini.value
  try { localStorage.setItem('qz.rail', mini.value ? 'mini' : 'pinned') } catch { /* ignore */ }
}

async function checkApprovals () {
  try {
    pendingApprovals.value = (await api.get('/admin/approvals/pending-count', { quiet: true })).pending
    openFraud.value = (await api.get('/admin/fraud/alerts/count', { quiet: true })).open
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
      ? { label: 'OFF', tone: '', detail: 'ISO interface disabled' }
      : !s.running
        ? { label: 'DOWN', tone: 'bad', detail: `Not listening on ${s.port}` }
        : { label: `${s.connections} link${s.connections === 1 ? '' : 's'}`, tone: s.connections ? 'ok' : 'idle',
            detail: `Port ${s.port} · ${s.received} messages since start` })
  } catch {
    Object.assign(iso, { label: '?', tone: '', detail: 'Quasar not reachable' })
  }
}

async function checkCore () {
  try {
    const s = await api.get('/admin/core-banking/status', { quiet: true })
    const queued = (s.queue?.PENDING ?? 0) + (s.queue?.FAILED ?? 0)
    Object.assign(coreHost, {
      shown: s.configured || s.coreAccounts > 0,
      label: !s.configured ? 'OFF' : s.up ? (queued ? `UP · ${queued} queued` : 'UP') : 'DOWN',
      tone: s.up ? (s.queue?.FAILED ? 'bad' : 'ok') : 'bad',
      detail: s.up ? `Answered in ${s.latencyMs} ms · ${s.queue?.PENDING ?? 0} pending, ${s.queue?.FAILED ?? 0} failed`
        : `${s.reason || 'Not reachable'} · stand-in limits apply`
    })
  } catch { coreHost.shown = false }
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
      ? { status: 'UP', tone: 'ok', detail: `LMK check ${b.lmkCheckValue} · firmware ${b.firmware}` }
      : { status: 'DOWN', tone: 'bad', detail: b.reason || 'Unavailable' })
  } catch {
    Object.assign(hsm, { status: '?', tone: '', detail: 'Quasar not reachable' })
  }
}

let timer
onMounted(async () => {
  try {
    const d = localStorage.getItem('cms.dark')
    if (d !== null) $q.dark.set(d === 'true')
    mini.value = localStorage.getItem('qz.rail') !== 'pinned'
  } catch { /* ignore */ }
  loadReference().catch(() => {})
  api.get('/version', { quiet: true }).then(v => Object.assign(version, v)).catch(() => {})
  checkHsm()
  checkIso()
  checkApprovals()
  checkCore()
  api.get('/dev/iso/status', { quiet: true }).then(() => { devTools.value = true }).catch(() => {})
  timer = setInterval(() => { checkHsm(); checkIso(); checkApprovals(); checkCore() }, 30000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>
