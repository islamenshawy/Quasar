<template>
  <q-btn-dropdown v-if="targets.length" outline color="primary" icon="swap_horiz" label="Change status" no-caps>
    <q-list>
      <q-item v-for="t in targets" :key="t" clickable v-close-popup @click="open(t)">
        <q-item-section avatar><q-icon :name="statusIcon(t)" :color="statusColor(t)" /></q-item-section>
        <q-item-section>{{ verb(t) }}</q-item-section>
      </q-item>
    </q-list>
  </q-btn-dropdown>

  <q-dialog v-model="dialog">
    <q-card style="width: 460px; max-width: 92vw">
      <q-form @submit="submit">
        <q-card-section>
          <div class="text-h6">{{ verb(target) }} {{ entity }}</div>
          <div class="text-body2 muted q-mt-xs">
            {{ label(current) }} → <b>{{ label(target) }}</b>
            <span v-if="final.includes(target)"> · this cannot be undone</span>
          </div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <q-input v-model="reason" outlined autofocus :label="reasonRequired ? 'Reason *' : 'Reason (optional)'"
                   maxlength="128" counter
                   :rules="reasonRequired ? [v => !!(v && v.trim()) || 'Reason is required'] : []" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="Back" v-close-popup no-caps />
          <q-btn type="submit" unelevated no-caps :color="final.includes(target) ? 'negative' : 'primary'"
                 :label="verb(target)" :loading="busy" />
        </q-card-actions>
      </q-form>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { label, statusColor, statusIcon, verb } from '../lib/format.js'

const props = defineProps({
  current: { type: String, required: true },
  targets: { type: Array, default: () => [] },
  entity: { type: String, default: '' },
  // statuses that may be set without a reason (reactivation)
  reasonOptional: { type: Array, default: () => [] },
  final: { type: Array, default: () => ['CLOSED', 'CANCELLED', 'LOST', 'STOLEN'] },
  // async (status, reason) => void; the dialog closes when it resolves
  onChange: { type: Function, required: true }
})

const dialog = ref(false)
const target = ref('')
const reason = ref('')
const busy = ref(false)
const reasonRequired = computed(() => !props.reasonOptional.includes(target.value))

function open (t) {
  target.value = t
  reason.value = ''
  dialog.value = true
}

async function submit () {
  busy.value = true
  try {
    await props.onChange(target.value, reason.value.trim())
    dialog.value = false
  } catch { /* error already shown */ } finally {
    busy.value = false
  }
}
</script>
