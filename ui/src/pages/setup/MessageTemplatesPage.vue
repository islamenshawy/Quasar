<template>
  <q-page padding class="page">
    <PageHeader title="Message templates" subtitle="What cardholders receive, per event, channel and language. Changes need a supervisor's approval." />

    <div v-for="g in groups" :key="g.event" class="q-mb-lg">
      <div class="row items-center q-mb-sm">
        <div class="text-subtitle1 text-weight-medium">{{ label(g.event) }}</div>
        <span class="text-caption muted q-ml-sm">{{ hints[g.event] }}</span>
      </div>
      <div class="row q-col-gutter-md">
        <div v-for="t in g.items" :key="t.key" class="col-12 col-md-6 col-lg-4">
          <q-card flat bordered class="full-height cursor-pointer" :class="{ 'inactive-row': !t.active }" @click="edit(t)">
            <q-card-section class="row items-center q-pb-xs">
              <q-badge :color="t.channel === 'SMS' ? 'primary' : 'accent'" :label="t.channel" />
              <q-badge outline color="grey-7" class="q-ml-xs" :label="t.language" />
              <q-space />
              <span class="text-caption muted">{{ t.active ? '' : 'off' }}</span>
            </q-card-section>
            <q-card-section class="q-pt-xs text-body2" :dir="t.language === 'AR' ? 'rtl' : 'ltr'" style="white-space: pre-wrap">
              <div v-if="t.subject" class="text-weight-medium">{{ t.subject }}</div>{{ t.body }}
            </q-card-section>
          </q-card>
        </div>
      </div>
    </div>

    <q-dialog v-model="dialog" persistent>
      <q-card v-if="form" style="width: 820px; max-width: 96vw">
        <q-card-section class="row items-center">
          <div class="text-h6">{{ label(form.event) }} · {{ form.channel }} · {{ form.language }}</div>
          <q-space />
          <q-btn flat round dense icon="close" v-close-popup />
        </q-card-section>
        <q-card-section class="q-pt-none row q-col-gutter-md">
          <div class="col-12 col-md-7">
            <q-input v-if="form.channel === 'EMAIL'" v-model="form.subject" outlined dense label="Subject" class="q-mb-sm" maxlength="120" />
            <q-input ref="bodyRef" v-model="form.body" outlined type="textarea" autogrow label="Text" maxlength="1000"
                     :input-style="{ minHeight: '140px', direction: form.language === 'AR' ? 'rtl' : 'ltr' }" />
            <div class="row items-center q-mt-xs text-caption muted">
              <span>{{ form.body.length }} characters</span>
              <span v-if="form.channel === 'SMS'" class="q-ml-sm">· {{ segments }} SMS segment{{ segments === 1 ? '' : 's' }} ({{ unicode ? 'Unicode, 70' : 'GSM, 160' }} per segment)</span>
            </div>
            <div class="text-caption q-mt-sm q-mb-xs">Insert</div>
            <div class="row q-gutter-xs">
              <q-chip v-for="v in vars" :key="v" dense clickable color="indigo-1" text-color="indigo-10" class="mono" @click="insert(v)">{{ v }}</q-chip>
            </div>
            <q-toggle v-model="form.active" label="Send this message" class="q-mt-sm" />
          </div>
          <div class="col-12 col-md-5">
            <div class="text-caption muted q-mb-xs">Preview with sample data</div>
            <div class="qz-phone">
              <div class="qz-phone-body" style="max-height: 320px">
                <div class="qz-bubble" :class="form.channel === 'EMAIL' ? 'mail' : ''" :dir="form.language === 'AR' ? 'rtl' : 'ltr'">
                  <div v-if="form.channel === 'EMAIL' && form.subject" class="text-weight-bold">{{ render(form.subject) }}</div>
                  <div style="white-space: pre-wrap">{{ render(form.body) }}</div>
                </div>
              </div>
            </div>
          </div>
        </q-card-section>
        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat no-caps label="Cancel" v-close-popup />
          <q-btn v-if="can.supervise" unelevated no-caps color="primary" label="Save" :loading="busy" @click="save" />
          <span v-else class="text-caption muted">Changes need a supervisor</span>
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import PageHeader from '../../components/PageHeader.vue'
import { api, pending } from '../../lib/api.js'
import { can } from '../../lib/session.js'
import { label } from '../../lib/format.js'

const SAMPLE = { name: 'Nadia', pan: '****0139', amount: '600.00', currency: 'EGP', merchant: 'CITY STARS', balance: '1,234.50 EGP',
  date: '03 Oct 14:20', status: 'blocked', reason: 'Not sufficient funds', code: '493817', minutes: '5' }
const vars = Object.keys(SAMPLE).map(k => `{{${k}}}`)
const hints = {
  TXN_APPROVED: 'money movements, subject to the customer\'s minimum amount',
  TXN_DECLINED: 'insufficient funds, wrong PIN, limits, fraud',
  CARD_ISSUED: 'new, replacement and renewal cards',
  CARD_ACTIVATED: 'activated at the kiosk',
  CARD_STATUS: 'security: always by SMS',
  FRAUD_ALERT: 'security: a fraud rule matched',
  OTP: 'security: one-time password, must contain {{code}}'
}

const templates = ref([])
const dialog = ref(false)
const form = ref(null)
const busy = ref(false)
const bodyRef = ref(null)

const groups = computed(() => {
  const by = {}
  for (const t of templates.value) (by[t.event] ||= []).push(t)
  return Object.entries(by).map(([event, items]) => ({ event, items }))
})
const unicode = computed(() => /[^\x00-\x7F]/.test(render(form.value?.body || '')))
const segments = computed(() => {
  const n = render(form.value?.body || '').length
  const one = unicode.value ? 70 : 160
  const multi = unicode.value ? 67 : 153
  return n <= one ? 1 : Math.ceil(n / multi)
})

const render = text => text.replace(/\{\{(\w+)}}/g, (m, k) => SAMPLE[k] ?? m)

function edit (t) {
  form.value = { ...t, subject: t.subject || '' }
  dialog.value = true
}

function insert (v) {
  const el = bodyRef.value?.getNativeElement?.()
  const at = el ? el.selectionStart : form.value.body.length
  form.value.body = form.value.body.slice(0, at) + v + form.value.body.slice(at)
}

async function save () {
  busy.value = true
  try {
    const r = await api.put(`/admin/setup/notification-templates/${form.value.key}`,
      { subject: form.value.subject || null, body: form.value.body, active: form.value.active })
    if (!pending(r)) Notify.create({ type: 'positive', message: 'Template saved' })
    dialog.value = false
    load()
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

async function load () {
  templates.value = await api.get('/admin/setup/notification-templates')
}

onMounted(load)
</script>
