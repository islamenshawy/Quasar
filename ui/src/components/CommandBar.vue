<template>
  <q-dialog v-model="open" position="top" transition-show="jump-down" transition-hide="fade" :transition-duration="160" @before-show="reset" @show="focus">
    <div class="qz-cmd" role="dialog" aria-label="Command bar">
      <div class="qz-cmd-input">
        <q-icon name="search" size="22px" />
        <input ref="input" v-model="query" placeholder="Jump to a page, run an action, find a card, customer or account…"
               aria-label="Search" autocomplete="off" spellcheck="false" @keydown="onKey" />
        <kbd>Esc</kbd>
      </div>

      <div class="qz-cmd-results" ref="list">
        <template v-for="g in groups" :key="g.title">
          <div class="qz-cmd-group">{{ g.title }}<q-spinner-dots v-if="g.loading" size="14px" class="q-ml-sm" /></div>
          <div v-for="r in g.rows" :key="r.key" class="qz-cmd-row" :class="{ active: r.index === cursor }"
               :data-index="r.index" @mouseenter="cursor = r.index" @click="run(r)">
            <span class="qz-cmd-icon" :class="r.tone"><q-icon :name="r.icon" size="18px" /></span>
            <div class="col" style="min-width: 0">
              <div class="ellipsis" v-html="mark(r.label)" />
              <div v-if="r.caption" class="qz-cmd-caption ellipsis">{{ r.caption }}</div>
            </div>
            <span v-if="r.hint" class="qz-cmd-hint">{{ r.hint }}</span>
            <q-icon v-if="r.index === cursor" name="keyboard_return" size="16px" class="qz-cmd-enter" />
          </div>
        </template>
        <div v-if="!flat.length && !searching" class="qz-cmd-empty">
          Nothing matches “{{ query }}”. Try the last 4 digits of a card, a CIF, a name or an account number.
        </div>
      </div>

      <div class="qz-cmd-foot">
        <span><kbd>↑</kbd><kbd>↓</kbd> move</span>
        <span><kbd>Enter</kbd> open</span>
        <span><kbd>Ctrl</kbd><kbd>K</kbd> or <kbd>/</kbd> anywhere</span>
      </div>
    </div>
  </q-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useQuasar } from 'quasar'
import { useRouter } from 'vue-router'
import { api, qs } from '../lib/api.js'
import { can } from '../lib/session.js'
import { navGroups } from '../lib/nav.js'

const props = defineProps({ devTools: Boolean })
const open = defineModel({ type: Boolean, default: false })
const router = useRouter()
const $q = useQuasar()
const input = ref(null)
const list = ref(null)
const query = ref('')
const cursor = ref(0)
const found = ref({ cards: [], customers: [], accounts: [] })
const searching = ref(false)
let seq = 0
let timer

const actions = computed(() => [
  ...(can.write ? [
    { label: 'Issue a card', icon: 'add_card', to: '/issue', keys: 'new card issue' },
    { label: 'New customer', icon: 'person_add', to: '/customers?new=1', keys: 'create customer cif onboard' }
  ] : []),
  { label: $q.dark.isActive ? 'Switch to Quasar Light' : 'Switch to deep-space dark', icon: $q.dark.isActive ? 'light_mode' : 'dark_mode',
    run: () => { $q.dark.toggle(); try { localStorage.setItem('cms.dark', String($q.dark.isActive)) } catch { /* ignore */ } },
    keys: 'theme dark light mode' },
  { label: 'Pending approvals', icon: 'how_to_reg', to: '/approvals', keys: 'maker checker approve' },
  { label: 'Declined transactions', icon: 'block', to: '/transactions?result=DECLINED', keys: 'declines failed' },
  { label: 'Cards waiting for print', icon: 'print', to: '/cards?status=PENDING_PRINT', keys: 'print pending' }
])

const score = (text, q) => {
  if (!q) return 1
  const t = text.toLowerCase()
  if (t.startsWith(q)) return 3
  if (t.split(/\s+/).some(w => w.startsWith(q))) return 2
  return t.includes(q) ? 1 : 0
}

const groups = computed(() => {
  const q = query.value.trim().toLowerCase()
  const out = []
  let i = 0
  const add = (title, rows, loading = false) => {
    if (!rows.length && !loading) return
    out.push({ title, loading, rows: rows.map(r => ({ ...r, index: i++ })) })
  }
  const pages = navGroups({ devTools: props.devTools }).flatMap(g => g.items.map(it => ({ ...it, group: g.title })))
  const pick = (items, n) => items
    .map(it => ({ it, s: Math.max(score(it.label, q), it.keys ? score(it.keys, q) * 0.8 : 0) }))
    .filter(x => x.s > 0).sort((a, b) => b.s - a.s).slice(0, n).map(x => x.it)

  if (q) {
    add('Cards', found.value.cards.map(c => ({ key: 'card' + c.id, label: c.maskedPan, caption: `${c.customerName} · ${c.productName}`,
      hint: c.status?.replace(/_/g, ' ').toLowerCase(), icon: 'credit_card', tone: 'flare', to: `/cards/${c.id}` })), searching.value)
    add('Customers', found.value.customers.map(c => ({ key: 'cust' + c.id, label: c.fullName, caption: `CIF ${c.customerRef} · ${c.segmentName || c.segmentCode || ''}`,
      hint: c.status?.toLowerCase(), icon: 'person', tone: 'indigo', to: `/customers/${c.id}` })))
    add('Accounts', found.value.accounts.map(a => ({ key: 'acct' + a.id, label: a.accountNumber, caption: `${a.customerName} · ${a.currencyCode}`,
      hint: a.status?.toLowerCase(), icon: 'account_balance', tone: 'blue', to: `/accounts/${a.id}` })))
  }
  add('Actions', pick(actions.value, q ? 4 : 6).map(a => ({ key: 'act' + a.label, label: a.label, icon: a.icon, tone: 'amber', to: a.to, run: a.run })))
  add('Go to', pick(pages, q ? 6 : 8).map(p => ({ key: 'go' + p.to, label: p.label, caption: p.group, icon: p.icon, tone: 'nav', to: p.to })))
  return out
})
const flat = computed(() => groups.value.flatMap(g => g.rows))

watch(query, q => {
  cursor.value = 0
  clearTimeout(timer)
  const term = q.trim()
  if (term.length < 2) { found.value = { cards: [], customers: [], accounts: [] }; searching.value = false; return }
  searching.value = true
  timer = setTimeout(() => search(term), 180)
})

async function search (term) {
  const mine = ++seq
  const get = path => api.get(path, { quiet: true }).then(p => p.items || []).catch(() => [])
  const [cards, customers, accounts] = await Promise.all([
    get('/admin/cards' + qs({ q: term, size: 5 })),
    get('/admin/customers' + qs({ q: term, size: 4 })),
    get('/admin/accounts' + qs({ q: term, size: 4 }))
  ])
  if (mine !== seq) return
  found.value = { cards, customers, accounts }
  searching.value = false
}

function run (r) {
  open.value = false
  if (r.run) r.run()
  else if (r.to) router.push(r.to)
}

function onKey (e) {
  const n = flat.value.length
  if (e.key === 'ArrowDown') { e.preventDefault(); cursor.value = n ? (cursor.value + 1) % n : 0; reveal() }
  else if (e.key === 'ArrowUp') { e.preventDefault(); cursor.value = n ? (cursor.value - 1 + n) % n : 0; reveal() }
  else if (e.key === 'Enter') { e.preventDefault(); const r = flat.value[cursor.value]; if (r) run(r) }
}
function reveal () {
  nextTick(() => list.value?.querySelector(`[data-index="${cursor.value}"]`)?.scrollIntoView({ block: 'nearest' }))
}
function reset () {
  query.value = ''
  cursor.value = 0
}
function focus () {
  nextTick(() => input.value?.focus())
}

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
function mark (label) {
  const q = query.value.trim()
  const safe = esc(label)
  if (!q) return safe
  const at = label.toLowerCase().indexOf(q.toLowerCase())
  return at < 0 ? safe : esc(label.slice(0, at)) + '<b>' + esc(label.slice(at, at + q.length)) + '</b>' + esc(label.slice(at + q.length))
}

function globalKey (e) {
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target?.tagName) || e.target?.isContentEditable
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); open.value = !open.value }
  else if (e.key === '/' && !typing && !open.value) { e.preventDefault(); open.value = true }
  else if (open.value && e.target !== input.value) {
    // keys typed while the bar is still animating in go to the search box, not to the page
    if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); query.value += e.key; focus() }
    else if (['ArrowDown', 'ArrowUp', 'Enter'].includes(e.key)) onKey(e)
  }
}
onMounted(() => window.addEventListener('keydown', globalKey))
onBeforeUnmount(() => window.removeEventListener('keydown', globalKey))
</script>

<style>
.qz-cmd {
  width: 680px; max-width: calc(100vw - 32px); margin-top: 10vh; border-radius: 18px; overflow: hidden;
  background: rgba(255, 255, 255, .94); backdrop-filter: blur(20px) saturate(1.4); color: var(--qz-text);
  border: 1px solid rgba(255, 255, 255, .7);
  box-shadow: 0 40px 120px -30px rgba(20, 10, 80, .65), 0 0 0 1px rgba(91, 75, 232, .12);
}
.body--dark .qz-cmd { background: rgba(21, 18, 43, .92); border-color: rgba(200, 190, 255, .14); }
.qz-cmd-input { display: flex; align-items: center; gap: 12px; padding: 16px 18px; border-bottom: 1px solid var(--qz-line); color: var(--q-primary); }
.qz-cmd-input input { flex: 1; border: 0; outline: 0; background: transparent; font: 500 17px var(--qz-font); color: var(--qz-text); }
.qz-cmd-input input::placeholder { color: var(--qz-text-2); font-weight: 400; }
.qz-cmd-results { max-height: min(56vh, 480px); overflow-y: auto; padding: 6px 8px 10px; }
.qz-cmd-group { display: flex; align-items: center; font-size: 11px; font-weight: 700; letter-spacing: .12em; text-transform: uppercase;
  color: var(--qz-text-2); padding: 12px 10px 6px; }
.qz-cmd-row { display: flex; align-items: center; gap: 12px; padding: 8px 10px; border-radius: 10px; cursor: pointer; }
.qz-cmd-row.active { background: linear-gradient(90deg, rgba(91, 75, 232, .14), rgba(62, 107, 255, .05)); }
.qz-cmd-row b { color: var(--q-primary); font-weight: 700; }
.qz-cmd-icon { width: 32px; height: 32px; border-radius: 9px; display: grid; place-items: center; color: #fff; flex: none; background: var(--qz-grad); }
.qz-cmd-icon.flare { background: var(--qz-grad-flare); }
.qz-cmd-icon.blue { background: linear-gradient(135deg, #3E6BFF, #5FA8FF); }
.qz-cmd-icon.amber { background: linear-gradient(135deg, #E8962D, #FF6B5B); }
.qz-cmd-icon.nav { background: rgba(91, 75, 232, .1); color: var(--q-primary); }
.qz-cmd-caption { font-size: 12px; color: var(--qz-text-2); }
.qz-cmd-hint { font-size: 11.5px; color: var(--qz-text-2); text-transform: capitalize; }
.qz-cmd-enter { color: var(--q-primary); }
.qz-cmd-empty { padding: 26px 16px; text-align: center; color: var(--qz-text-2); }
.qz-cmd-foot { display: flex; gap: 18px; padding: 10px 18px; border-top: 1px solid var(--qz-line); font-size: 12px; color: var(--qz-text-2); }
.qz-cmd kbd, .qz-kbd { font: 600 11px var(--qz-font); padding: 2px 6px; border-radius: 6px; margin-right: 3px;
  border: 1px solid var(--qz-line-strong); background: var(--qz-hover); color: var(--qz-text-2); }
@media (max-width: 599px) { .qz-cmd-foot { display: none; } .qz-cmd { margin-top: 2vh; } }
</style>
