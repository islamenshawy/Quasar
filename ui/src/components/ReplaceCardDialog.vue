<template>
  <q-dialog v-model="show" persistent>
    <q-card style="width: 520px; max-width: 95vw">
      <template v-if="!issued">
        <q-form @submit="save">
          <q-card-section>
            <div class="text-h6">Replace card</div>
            <div class="text-body2 muted">{{ card.maskedPan }} · {{ card.productName }}</div>
          </q-card-section>
          <q-card-section class="q-pt-none q-gutter-md">
            <q-select v-model="f.reason" outlined dense emit-value map-options label="Reason *" :options="reasons" />
            <q-toggle v-model="f.samePan" :disable="compromised" label="Keep the card number (new expiry, next sequence number)" />
            <q-banner v-if="compromised" dense rounded class="bg-orange-1 text-dark">
              <template #avatar><q-icon name="warning" color="warning" /></template>
              A {{ label(f.reason).toLowerCase() }} card gets a new number<span v-if="['LOST','STOLEN'].includes(f.reason)">, and the old card is blocked as {{ f.reason }} now</span>.
            </q-banner>
            <div v-else class="text-caption muted">The old card keeps working until the replacement is activated at the kiosk.</div>
            <div class="row q-col-gutter-sm">
              <div class="col-7">
                <q-input v-model="f.embossingName" outlined dense label="Name on card" maxlength="26" input-class="mono"
                         @update:model-value="v => f.embossingName = (v || '').toUpperCase()" />
              </div>
              <div class="col-5"><q-input v-model="f.branchId" outlined dense label="Branch / kiosk" maxlength="32" /></div>
            </div>
          </q-card-section>
          <q-card-actions align="right" class="q-pa-md">
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn type="submit" unelevated no-caps color="primary" label="Replace" :loading="busy" />
          </q-card-actions>
        </q-form>
      </template>
      <template v-else>
        <q-card-section>
          <div class="text-h6">Replacement created</div>
          <div class="text-body2 muted">Enter this number in Dexxis to print. Shown once.</div>
          <div class="mono text-h5 q-my-md" style="letter-spacing: .06em">{{ (issued.pan || issued.maskedPan).replace(/(.{4})(?=.)/g, '$1 ') }}</div>
          <div class="text-caption muted">Expiry {{ expiry(issued.expiryYYMM) }} · waiting for print</div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn v-if="issued.pan" flat no-caps icon="content_copy" label="Copy" @click="copyToClipboard(issued.pan)" />
          <q-btn unelevated no-caps color="primary" label="Open new card" @click="done(true)" />
          <q-btn flat no-caps label="Close" @click="done(false)" />
        </q-card-actions>
      </template>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { copyToClipboard } from 'quasar'
import { useRouter } from 'vue-router'
import { api, pending } from '../lib/api.js'
import { expiry, label } from '../lib/format.js'

const props = defineProps({ modelValue: Boolean, card: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue', 'done'])
const show = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const router = useRouter()

const reasons = ['DAMAGED', 'RENEWAL', 'LOST', 'STOLEN', 'NOT_RECEIVED', 'OTHER'].map(r => ({ label: label(r), value: r }))
const f = reactive({ reason: 'DAMAGED', samePan: true, embossingName: '', branchId: '' })
const busy = ref(false)
const issued = ref(null)
const compromised = computed(() => ['LOST', 'STOLEN', 'NOT_RECEIVED'].includes(f.reason))

watch(compromised, c => { if (c) f.samePan = false })
watch(() => props.modelValue, v => {
  if (!v) return
  issued.value = null
  Object.assign(f, { reason: 'DAMAGED', samePan: true, embossingName: props.card.embossingName, branchId: '' })
})

async function save () {
  busy.value = true
  try {
    const r = await api.post(`/admin/cards/${props.card.id}/replace`, { ...f })
    if (pending(r)) { emit('done', null); show.value = false; return }
    issued.value = r
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

function done (open) {
  const id = issued.value?.cardId
  issued.value = null   // drop the PAN from memory
  show.value = false
  emit('done', id)
  if (open && id) router.push(`/cards/${id}`)
}
</script>
