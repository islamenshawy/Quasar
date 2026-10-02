<template>
  <div class="row q-col-gutter-md">
    <div class="col-12 col-md-4">
      <q-card flat bordered>
        <q-card-section>
          <div class="text-subtitle1 text-weight-medium">Alert preferences</div>
          <div class="text-caption muted q-mb-md">
            Security messages (card blocked, fraud alerts, one-time passwords) always go to the mobile number.
          </div>
          <template v-if="p">
            <q-toggle v-model="p.notifySms" :disable="!can.write || !p.mobile" label="SMS" />
            <div class="text-caption muted q-ml-xl q-mb-sm">{{ p.mobile || 'No mobile number on file' }}</div>
            <q-toggle v-model="p.notifyEmail" :disable="!can.write || !p.email" label="E-mail" />
            <div class="text-caption muted q-ml-xl q-mb-md">{{ p.email || 'No e-mail on file' }}</div>
            <q-btn-toggle v-model="p.language" :disable="!can.write" no-caps unelevated toggle-color="primary" spread class="q-mb-md"
                          :options="[{ label: 'English', value: 'EN' }, { label: 'العربية', value: 'AR' }]" />
            <q-input v-model.number="threshold" :readonly="!can.write" outlined dense type="number" step="any"
                     label="Tell me about transactions from" hint="0 = every transaction" />
            <q-btn v-if="can.write" unelevated no-caps color="primary" label="Save" class="q-mt-md full-width" :loading="busy" @click="save" />
          </template>
        </q-card-section>
      </q-card>
    </div>
    <div class="col-12 col-md-8">
      <q-card flat bordered>
        <q-card-section class="row items-center">
          <div class="text-subtitle1 text-weight-medium">Messages sent</div>
          <q-space />
          <q-btn flat dense round icon="refresh" @click="load" />
        </q-card-section>
        <q-list separator>
          <q-item v-if="!messages.length"><q-item-section class="muted">No messages yet</q-item-section></q-item>
          <q-item v-for="m in messages" :key="m.id">
            <q-item-section avatar>
              <q-avatar size="34px" :color="m.channel === 'SMS' ? 'indigo-1' : 'pink-1'" :text-color="m.channel === 'SMS' ? 'indigo-9' : 'pink-9'"
                        :icon="m.channel === 'SMS' ? 'sms' : 'mail'" />
            </q-item-section>
            <q-item-section>
              <q-item-label caption>{{ label(m.event) }} · {{ dateTime(m.createdAt) }} · {{ m.destination }}</q-item-label>
              <q-item-label :dir="/[؀-ۿ]/.test(m.body) ? 'rtl' : 'ltr'" class="text-body2" style="white-space: pre-wrap">{{ m.subject ? m.subject + ' — ' : '' }}{{ m.body }}</q-item-label>
            </q-item-section>
            <q-item-section side><StatusBadge :status="m.status" /></q-item-section>
          </q-item>
        </q-list>
      </q-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Notify } from 'quasar'
import StatusBadge from './StatusBadge.vue'
import { api, qs } from '../lib/api.js'
import { can } from '../lib/session.js'
import { dateTime, label } from '../lib/format.js'

const props = defineProps({ customerId: { type: [Number, String], required: true } })
const p = ref(null)
const threshold = ref(0)
const messages = ref([])
const busy = ref(false)

async function load () {
  const [prefs, page] = await Promise.all([
    api.get(`/admin/customers/${props.customerId}/notification-settings`),
    api.get('/admin/notifications' + qs({ customerId: props.customerId, size: 30 }))])
  p.value = prefs
  threshold.value = prefs.alertThreshold / 100
  messages.value = page.items
}

async function save () {
  busy.value = true
  try {
    p.value = await api.put(`/admin/customers/${props.customerId}/notification-settings`,
      { ...p.value, alertThreshold: Math.round(Number(threshold.value || 0) * 100) })
    Notify.create({ type: 'positive', message: 'Alert preferences saved' })
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

onMounted(load)
</script>
