<template>
  <div class="emv">
    <div class="row items-center q-mb-sm q-gutter-x-sm">
      <span class="text-caption muted">{{ byteCount }} bytes · {{ rows.length }} tags</span>
      <q-space />
      <q-toggle v-model="onlySet" dense size="sm" label="Only set flags" class="text-caption" />
      <q-btn flat dense round size="sm" icon="unfold_more" @click="toggleAll(true)"><q-tooltip>Expand all</q-tooltip></q-btn>
      <q-btn flat dense round size="sm" icon="unfold_less" @click="toggleAll(false)"><q-tooltip>Collapse all</q-tooltip></q-btn>
      <q-btn flat dense round size="sm" icon="content_copy" @click="copyRaw"><q-tooltip>Copy raw hex</q-tooltip></q-btn>
    </div>

    <q-banner v-if="error" dense rounded class="bg-red-1 text-negative q-mb-sm">
      <template #avatar><q-icon name="error_outline" /></template>
      Cannot parse: {{ error }}
    </q-banner>

    <q-list bordered separator class="rounded-borders">
      <TagRow v-for="(r, i) in rows" :key="r.tag + i" :row="r" :only-set="onlySet" :open="open" />
    </q-list>

    <div class="mono text-caption muted q-mt-sm raw">{{ cleanHex }}</div>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, ref } from 'vue'
import { copyToClipboard, Notify, QExpansionItem, QList } from 'quasar'
import { decodeIcc } from '../lib/emv.js'

const props = defineProps({ hex: { type: String, default: '' } })
const onlySet = ref(false)
const open = ref({ all: null, version: 0 })

const cleanHex = computed(() => (props.hex || '').replace(/[\s:]/g, '').toUpperCase())
const byteCount = computed(() => Math.floor(cleanHex.value.length / 2))
const parsed = computed(() => {
  try {
    return { rows: decodeIcc(cleanHex.value), error: null }
  } catch (e) {
    return { rows: [], error: e.message }
  }
})
const rows = computed(() => parsed.value.rows)
const error = computed(() => parsed.value.error)

function toggleAll (v) {
  open.value = { all: v, version: open.value.version + 1 }
}
function copyRaw () {
  copyToClipboard(cleanHex.value).then(() => Notify.create({ type: 'positive', message: 'DE55 copied', timeout: 1200 }))
}

// one tag: summary line, expandable to description, details, bit grid and nested tags
const TagRow = defineComponent({
  name: 'TagRow',
  props: { row: Object, onlySet: Boolean, open: Object },
  setup (p) {
    const expanded = ref(false)
    let seen = 0
    return () => {
      if (p.open.version !== seen) { seen = p.open.version; if (p.open.all !== null) expanded.value = p.open.all }
      const r = p.row
      const header = h('div', { class: 'row items-center no-wrap full-width q-gutter-x-sm' }, [
        h('span', { class: ['tag mono', r.known ? '' : 'unknown'] }, r.tag),
        h('div', { class: 'col', style: 'min-width: 0' }, [
          h('div', { class: 'text-body2 text-weight-medium ellipsis' }, r.name),
          h('div', { class: ['text-caption ellipsis', r.warn ? 'text-negative text-weight-medium' : 'muted'] }, r.summary)
        ]),
        h('span', { class: 'text-caption muted mono col-auto' }, `${r.length} B`)
      ])
      const body = h('div', { class: 'q-px-md q-pb-md body' }, [
        r.desc && h('div', { class: 'text-body2 q-mb-sm' }, r.desc),
        h('div', { class: 'mono text-caption q-mb-sm' }, [h('span', { class: 'muted' }, 'Value '), r.raw]),
        r.details && h('table', { class: 'details text-caption q-mb-sm' }, r.details.map(d =>
          h('tr', [h('td', { class: 'muted' }, d.label), h('td', { class: 'mono' }, d.value)]))),
        r.bits && h('div', { class: 'bytes' }, r.bits.map(b => {
          const flags = p.onlySet ? b.flags.filter(f => f.set) : b.flags
          return h('div', { class: 'byte' }, [
            h('div', { class: 'byte-head mono text-caption' }, [
              h('b', `Byte ${b.index}`), ` · ${b.hex} · `, h('span', { class: 'muted' }, b.bin)]),
            flags.length
              ? flags.map(f => h('div', { class: ['flag text-caption', f.value !== undefined ? 'field' : f.set ? (r.tone === 'alert' ? 'on alert' : 'on') : 'off'] }, [
                h('span', { class: 'mono bit' }, f.bit),
                h('span', { class: 'dot' }),
                h('span', f.value !== undefined ? `${f.text}: ${f.value}` : f.text)]))
              : h('div', { class: 'text-caption muted q-pl-sm' }, 'No flags set')
          ])
        })),
        r.children && h('div', { class: 'q-mt-sm' }, [
          h('div', { class: 'text-caption muted q-mb-xs' }, 'Contains'),
          h(QList, { bordered: true, separator: true, class: 'rounded-borders' },
            () => r.children.map((c, i) => h(TagRow, { key: c.tag + i, row: c, onlySet: p.onlySet, open: p.open })))
        ])
      ])
      return h(QExpansionItem, {
        modelValue: expanded.value,
        'onUpdate:modelValue': v => { expanded.value = v },
        dense: true,
        headerClass: 'q-py-sm'
      }, { header: () => header, default: () => body })
    }
  }
})
</script>

<style>
/* not scoped: the rows are rendered by TagRow (render function), which gets no scope id */
.emv .tag {
  display: inline-block; min-width: 46px; text-align: center; padding: 2px 6px; border-radius: 6px;
  font-size: 12px; font-weight: 600; background: var(--q-primary); color: #fff;
}
.emv .tag.unknown { background: #8a8f98; }
.emv .raw { word-break: break-all; }
.emv .details td { padding: 2px 12px 2px 0; vertical-align: top; }
.emv .bytes { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px; }
.emv .byte { border: 1px solid rgba(128, 128, 128, .25); border-radius: 8px; padding: 6px 8px; }
.emv .byte-head { margin-bottom: 4px; }
.emv .flag { display: flex; align-items: flex-start; gap: 6px; padding: 1px 0; }
.emv .flag .bit { width: 50px; flex: none; opacity: .7; }
.emv .flag .dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; flex: none; background: rgba(128, 128, 128, .35); }
.emv .flag.on .dot { background: var(--q-positive); }
.emv .flag.on.alert .dot { background: var(--q-negative); }
.emv .flag.on { font-weight: 600; }
.emv .flag.field .dot { background: var(--q-primary); }
.emv .flag.off { opacity: .55; }
</style>
