<template>
  <q-page padding class="page">
    <PageHeader title="Switch simulator"
                subtitle="DEV ONLY · sends real BASE24 ISO 8583:1993 messages over TCP to this CMS, the way the ATM/POS switch would">
      <template #actions>
        <q-btn outline no-caps color="primary" icon="login" label="Sign-on" @click="network('801')" />
        <q-btn outline no-caps color="primary" icon="sync" label="Echo" @click="network('831')" />
        <q-btn outline no-caps color="primary" icon="key" label="Key exchange" @click="network('811')" />
      </template>
    </PageHeader>

    <q-banner v-if="unavailable" rounded class="bg-orange-1 text-dark q-mb-md">
      <template #avatar><q-icon name="warning" color="warning" /></template>
      The simulator is only available when the CMS runs with the <b>dev</b> profile.
    </q-banner>

    <div class="row q-col-gutter-md">
      <div class="col-12 col-lg-5">
        <q-card flat bordered>
          <q-form @submit="send">
            <q-card-section class="q-gutter-md">
              <div>
                <q-input v-model="cardQuery" outlined dense debounce="300" clearable label="Find card"
                         placeholder="Last 4 digits, name or CIF" @update:model-value="findCards">
                  <template #prepend><q-icon name="search" /></template>
                </q-input>
                <q-list v-if="cardOptions.length && !f.card" bordered dense class="rounded-borders q-mt-xs" style="max-height: 240px; overflow-y: auto">
                  <q-item v-for="c in cardOptions" :key="c.id" clickable @click="pickCard(c)">
                    <q-item-section>
                      <q-item-label class="mono">{{ c.maskedPan }}</q-item-label>
                      <q-item-label caption>{{ c.customerName }} · {{ c.productName }} · {{ c.status }}</q-item-label>
                    </q-item-section>
                  </q-item>
                </q-list>
                <q-chip v-if="f.card" removable color="primary" text-color="white" icon="credit_card" class="q-mt-sm"
                        @remove="f.card = null">
                  <span class="mono">{{ f.card.maskedPan }}</span>&nbsp;· {{ f.card.customerName }} · {{ f.card.status }}
                </q-chip>
              </div>
              <q-select v-model="f.type" :options="typeOptions" emit-value map-options outlined dense label="Transaction" />
              <div class="row q-col-gutter-sm">
                <div class="col-6">
                  <q-select v-model="f.channel" :options="channelsFor(f.type)" outlined dense label="Channel" />
                </div>
                <div class="col-6">
                  <q-select v-model="f.currency" :options="currencies" emit-value map-options outlined dense label="Currency" />
                </div>
              </div>
              <q-input v-if="needsAmount" v-model="f.amount" outlined dense type="number" step="any" label="Amount *"
                       :rules="[v => Number(v) > 0 || 'Must be positive']" />
              <div class="row q-col-gutter-sm">
                <div class="col-6">
                  <q-input v-model="f.pin" outlined dense type="password" maxlength="12" label="PIN" autocomplete="off"
                           :hint="f.channel === 'ATM' ? 'Required at ATM' : 'Optional'" />
                </div>
                <div v-if="f.type === 'PIN_CHANGE'" class="col-6">
                  <q-input v-model="f.newPin" outlined dense type="password" maxlength="12" label="New PIN *" autocomplete="off" />
                </div>
                <div v-else class="col-6">
                  <q-input v-model="f.terminalId" outlined dense maxlength="8" label="Terminal id" />
                </div>
              </div>
              <q-input v-if="f.channel !== 'ATM'" v-model="f.merchant" outlined dense maxlength="40" label="Merchant name / location" />
              <div class="row q-col-gutter-sm">
                <div class="col-6">
                  <q-input v-model="f.mcc" outlined dense maxlength="4" label="MCC" class="mono"
                           :hint="f.channel === 'ATM' ? 'Default 6011 (ATM)' : 'Default 5411; 7995 gambling, 4829 money transfer'" />
                </div>
                <div class="col-6">
                  <q-input v-model="f.country" outlined dense maxlength="3" label="Acquirer country (field 19)" class="mono"
                           hint="Blank = domestic; e.g. 840 US, 784 UAE" />
                </div>
              </div>
              <q-toggle v-model="f.chip" label="Chip (EMV): send field 55 with an ARQC" :disable="f.channel === 'ECOM'" />
              <q-toggle v-if="f.channel === 'POS'" v-model="f.contactless" label="Contactless tap (field 22 = M)" />
              <q-toggle v-if="f.chip" v-model="f.failScripts" label="Chip refuses issuer scripts (reports failure)" color="warning" />
              <q-input v-if="f.channel === 'ECOM'" v-model="f.cvv2" outlined dense maxlength="4" label="CVV2 (field 48)" class="mono" />
              <q-select v-if="f.chip" v-model="f.tvr" :options="tvrOptions" emit-value map-options outlined dense
                        label="Terminal verification results (TVR, tag 95)">
                <template #append><span class="mono text-caption">{{ f.tvr }}</span></template>
              </q-select>
              <q-toggle v-if="f.chip" v-model="f.tamper" label="Tamper with the ARQC (should decline 129)" color="negative" />
              <q-toggle v-model="f.advice" label="Send as stand-in advice (x220 / x120)" :disable="!['WITHDRAWAL','PURCHASE','PREAUTH'].includes(f.type)" />
            </q-card-section>
            <q-card-actions class="q-px-md q-pb-md">
              <q-btn type="submit" unelevated no-caps color="primary" icon="send" label="Send" :loading="busy" :disable="unavailable || !f.card" />
              <q-space />
              <span class="text-caption muted">{{ status.target }} · acquirer ZPK KCV {{ status.acquirerZpkKcv }}</span>
            </q-card-actions>
          </q-form>
        </q-card>
      </div>

      <div class="col-12 col-lg-7">
        <q-card v-if="last" flat bordered class="q-mb-md">
          <q-card-section class="row items-center">
            <div class="text-subtitle1 text-weight-medium">{{ last.mti }} → {{ last.response['0'] }}</div>
            <q-badge class="q-ml-sm text-body2 q-pa-sm" :color="last.approved ? 'positive' : 'negative'"
                     :label="`${last.actionCode} ${last.actionText}`" />
            <q-space />
            <span class="text-caption muted">{{ last.elapsedMs }} ms</span>
          </q-card-section>
          <q-card-section v-if="last.chip" class="q-pt-none text-body2 row items-center q-gutter-sm">
            <q-icon name="sim_card" />
            <span>Chip ATC {{ last.chip.atc }}:</span>
            <q-badge v-if="!last.chip.arpcReceived" color="grey-6" label="no ARPC returned" />
            <q-badge v-else :color="last.chip.arpcValid ? 'positive' : 'negative'"
                     :label="last.chip.arpcValid ? `ARPC valid · ARC ${last.chip.arc}` : 'ARPC invalid'" />
          </q-card-section>
          <q-card-section v-if="last.chip?.scripts?.length" class="q-pt-none">
            <div class="text-caption muted q-mb-xs">Issuer scripts run by the card</div>
            <div v-for="s in last.chip.scripts" :key="s.scriptId" class="row items-center q-gutter-sm text-body2">
              <q-icon :name="s.macValid ? 'verified' : 'gpp_bad'" :color="s.macValid ? 'positive' : 'negative'" />
              <span class="mono">{{ s.apdu }}</span>
              <q-badge :color="s.result === 'SUCCESSFUL' ? 'positive' : 'negative'" :label="s.result" />
              <span class="text-caption muted">reported in 9F5B on the next chip transaction</span>
            </div>
          </q-card-section>
          <q-card-section v-if="last.chip?.requestIcc" class="q-pt-none">
            <q-tabs v-model="iccTab" dense align="left" no-caps active-color="primary" indicator-color="primary" class="q-mb-sm">
              <q-tab name="request" :label="`Request DE55 (${last.mti})`" />
              <q-tab name="response" :label="`Response DE55 (${last.response['0']})`" :disable="!last.chip.responseIcc" />
            </q-tabs>
            <EmvTlvViewer :hex="iccTab === 'response' ? last.chip.responseIcc : last.chip.requestIcc" />
          </q-card-section>
          <q-card-section v-if="last.availableBalance != null" class="q-pt-none text-body2">
            Ledger <b class="mono">{{ money(last.ledgerBalance) }}</b> · Available <b class="mono">{{ money(last.availableBalance) }}</b>
          </q-card-section>
          <q-card-section class="q-pt-none row q-col-gutter-md">
            <div v-for="side in ['request', 'response']" :key="side" class="col-12 col-md-6">
              <div class="text-caption text-uppercase muted q-mb-xs">{{ side }}</div>
              <q-markup-table flat dense separator="horizontal" class="mono text-caption sim-fields">
                <tbody>
                  <tr v-for="(v, k) in last[side]" :key="k"><td class="text-right muted" style="width: 42px">{{ k }}</td><td style="word-break: break-all">{{ v }}</td></tr>
                </tbody>
              </q-markup-table>
            </div>
          </q-card-section>
        </q-card>

        <q-card flat bordered class="q-mb-md">
          <q-expansion-item icon="memory" label="Decode any DE55" caption="Paste field 55 hex from a log, a trace or another host"
                            header-class="text-subtitle1 text-weight-medium">
            <q-card-section class="q-pt-none">
              <q-input v-model="pasted" outlined dense autogrow type="textarea" class="mono" label="Field 55 (hex)"
                       placeholder="9F2608... 9F2701 80 9F1007 06011203A00000 ..." />
              <EmvTlvViewer v-if="pasted.trim()" :hex="pasted" class="q-mt-md" />
            </q-card-section>
          </q-expansion-item>
        </q-card>

        <q-card flat bordered>
          <q-card-section class="text-subtitle1 text-weight-medium">Sent in this session</q-card-section>
          <q-list separator>
            <q-item v-if="!history.length"><q-item-section class="muted">Nothing sent yet</q-item-section></q-item>
            <q-item v-for="h in history" :key="h.ref + h.at" clickable @click="last = h.result">
              <q-item-section avatar><q-badge :color="h.result.approved ? 'positive' : 'negative'" :label="h.result.actionCode" /></q-item-section>
              <q-item-section>
                <q-item-label>{{ h.label }}</q-item-label>
                <q-item-label caption class="mono">STAN {{ h.ref }} · {{ h.result.mti }} · {{ h.at }}</q-item-label>
              </q-item-section>
              <q-item-section side v-if="h.original">
                <div class="row q-gutter-xs">
                  <q-btn flat dense no-caps size="sm" label="Repeat" @click.stop="repeat(h)" />
                  <q-btn v-if="['WITHDRAWAL','PURCHASE','PREAUTH','REFUND','COMPLETION'].includes(h.type) && h.result.approved"
                         flat dense no-caps size="sm" color="negative" label="Reverse" @click.stop="reverse(h)" />
                  <q-btn v-if="h.type === 'PREAUTH' && h.result.approved" flat dense no-caps size="sm" color="primary"
                         label="Complete" @click.stop="complete(h)" />
                </div>
              </q-item-section>
            </q-item>
          </q-list>
        </q-card>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Notify, useQuasar } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import EmvTlvViewer from '../components/EmvTlvViewer.vue'
import { api, qs } from '../lib/api.js'
import { TVR_PRESETS } from '../lib/emv.js'
import { loadReference, reference } from '../lib/reference.js'
import { label, toMinor, money as fmt } from '../lib/format.js'

const $q = useQuasar()

const typeOptions = ['BALANCE_INQUIRY', 'WITHDRAWAL', 'PURCHASE', 'PREAUTH', 'REFUND', 'PIN_CHANGE'].map(t => ({ label: label(t), value: t }))
const f = reactive({ card: null, type: 'BALANCE_INQUIRY', channel: 'ATM', currency: '818', amount: null, pin: '', newPin: '', terminalId: 'ATM00001', merchant: '', advice: false, chip: false, tamper: false, tvr: '0000000000', mcc: '', country: '', contactless: false, failScripts: false, cvv2: '' })
const tvrOptions = TVR_PRESETS
const iccTab = ref('request')
const pasted = ref('')
const cardOptions = ref([])
const cardQuery = ref('')
const status = ref({})
const unavailable = ref(false)
const busy = ref(false)
const last = ref(null)
const history = ref([])
let exponent = 2

const needsAmount = computed(() => !['BALANCE_INQUIRY', 'PIN_CHANGE'].includes(f.type))
const currencies = computed(() => reference.currencies.map(c => ({ label: `${c.code} (${c.numericCode})`, value: c.numericCode })))
const money = v => fmt(v, exponent)

function channelsFor (t) {
  return ['WITHDRAWAL', 'BALANCE_INQUIRY', 'PIN_CHANGE'].includes(t) ? ['ATM'] : ['POS', 'ECOM']
}
watch(() => f.type, t => {
  if (!channelsFor(t).includes(f.channel)) f.channel = channelsFor(t)[0]
  f.terminalId = f.channel === 'ATM' ? 'ATM00001' : 'POS00001'
})

async function findCards (val) {
  f.card = null
  const page = await api.get('/admin/cards' + qs({ q: val || '', size: 8 }), { quiet: true }).catch(() => ({ items: [] }))
  cardOptions.value = page.items
}

function pickCard (c) {
  f.card = c
}

function record (label, type, result, original) {
  last.value = result
  history.value.unshift({ ref: result.ref, label, type, result, original, at: new Date().toLocaleTimeString() })
  if (history.value.length > 50) history.value.pop()
}

async function post (body, lbl, type, original = true) {
  busy.value = true
  try {
    const r = await api.post('/dev/iso/send', body)
    record(lbl, type, r, original)
    return r
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

async function send () {
  const acct = await api.get(`/admin/accounts/${f.card.accountId}`).catch(() => null)
  exponent = acct?.exponent ?? 2
  const body = {
    type: f.type, channel: f.channel, cardId: f.card.id, currency: f.currency,
    amount: needsAmount.value ? toMinor(f.amount, exponent) : null,
    pin: f.pin || null, newPin: f.type === 'PIN_CHANGE' ? f.newPin : null,
    terminalId: f.terminalId || null, merchant: f.merchant || null, advice: f.advice,
    chip: f.chip && f.channel !== 'ECOM', tamperArqc: f.chip && f.tamper, tvr: f.chip ? f.tvr : null,
    mcc: /^[0-9]{4}$/.test(f.mcc) ? f.mcc : null, country: /^[0-9]{3}$/.test(f.country) ? f.country : null,
    contactless: f.channel === 'POS' && f.contactless, failScripts: f.chip && f.failScripts,
    cvv2: f.channel === 'ECOM' && f.cvv2 ? f.cvv2 : null
  }
  const amt = needsAmount.value ? ` ${f.amount}` : ''
  await post(body, `${label(f.type)}${amt} · ${f.channel} · ${f.card.maskedPan}`, f.type)
  if (f.type === 'PIN_CHANGE' && last.value?.approved) f.pin = f.newPin
}

function repeat (h) {
  post({ type: h.type, repeat: true, originalRef: h.ref }, `Repeat of ${h.ref}`, h.type, false)
}

function reverse (h) {
  $q.dialog({
    title: 'Reversal', message: 'Amount actually completed (0 = full reversal)',
    prompt: { model: '0', type: 'number', outlined: true }, cancel: true
  }).onOk(v => post({ type: 'REVERSAL', originalRef: h.ref, amountCompleted: toMinor(v || 0, exponent) },
    `Reversal of ${h.ref}${Number(v) > 0 ? ` (completed ${v})` : ''}`, 'REVERSAL', false))
}

function complete (h) {
  $q.dialog({
    title: 'Completion', message: 'Final amount to capture',
    prompt: { model: '', type: 'number', outlined: true, isValid: v => Number(v) > 0 }, cancel: true
  }).onOk(v => post({ type: 'COMPLETION', channel: 'POS', originalRef: h.ref, amount: toMinor(v, exponent) },
    `Completion ${v} of ${h.ref}`, 'COMPLETION', true))
}

async function network (fc) {
  try {
    const r = await api.post('/dev/iso/network', { function: fc })
    record({ 801: 'Sign-on', 831: 'Echo test', 811: 'Key exchange (new acquirer ZPK)' }[fc], 'NETWORK', r, false)
    Notify.create({ type: r.approved ? 'positive' : 'negative', message: `1804/${fc} → ${r.actionCode} ${r.actionText}` })
    status.value = await api.get('/dev/iso/status')
  } catch { /* shown */ }
}

onMounted(async () => {
  loadReference()
  findCards('')
  try {
    status.value = await api.get('/dev/iso/status', { quiet: true })
  } catch {
    unavailable.value = true
  }
})
</script>

<style scoped>
.sim-fields :deep(td) { white-space: normal; }
</style>
