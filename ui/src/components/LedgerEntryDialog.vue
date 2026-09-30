<template>
  <q-dialog v-model="show" persistent>
    <q-card style="width: 480px; max-width: 95vw">
      <q-form @submit="save">
        <q-card-section>
          <div class="text-h6">Post entry</div>
          <div class="text-body2 muted">Account {{ account.accountNumber }} · available {{ money(account.availableBalance, account.exponent, account.currencyCode) }}</div>
        </q-card-section>
        <q-card-section class="q-pt-none q-gutter-md">
          <q-option-group v-model="type" :options="types" color="primary" />
          <q-input v-model="amount" outlined dense type="number" step="any" :prefix="account.currencyCode" label="Amount *"
                   :rules="[v => Number(v) > 0 || 'Must be positive',
                            v => type !== 'DEBIT_ADJUSTMENT' || toMinor(v, account.exponent) <= account.availableBalance || 'More than the available balance']" />
          <q-input v-model="narrative" outlined dense label="Narrative *" maxlength="128" counter
                   :rules="[v => !!(v && v.trim()) || 'Required']" hint="Shown on the statement" />
        </q-card-section>
        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat no-caps label="Cancel" v-close-popup />
          <q-btn type="submit" unelevated no-caps color="primary" label="Post" :loading="busy" />
        </q-card-actions>
      </q-form>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Notify } from 'quasar'
import { api } from '../lib/api.js'
import { money, toMinor } from '../lib/format.js'

const props = defineProps({ modelValue: Boolean, account: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue', 'saved'])
const show = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })

const types = [
  { label: 'Funding (top-up) — credit from funding suspense', value: 'FUNDING' },
  { label: 'Credit adjustment', value: 'CREDIT_ADJUSTMENT' },
  { label: 'Debit adjustment', value: 'DEBIT_ADJUSTMENT' }
]
const type = ref('FUNDING')
const amount = ref(null)
const narrative = ref('')
const busy = ref(false)

watch(() => props.modelValue, v => { if (v) { type.value = 'FUNDING'; amount.value = null; narrative.value = '' } })

async function save () {
  busy.value = true
  try {
    await api.post(`/admin/accounts/${props.account.id}/entries`, {
      type: type.value, amount: toMinor(amount.value, props.account.exponent), narrative: narrative.value
    })
    Notify.create({ type: 'positive', message: 'Entry posted' })
    emit('saved')
    show.value = false
  } catch { /* shown */ } finally {
    busy.value = false
  }
}
</script>
