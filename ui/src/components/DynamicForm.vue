<template>
  <div class="row q-col-gutter-md">
    <div v-for="f in visibleFields" :key="f.name" :class="f.col || 'col-12 col-sm-6'">
      <q-toggle v-if="f.type === 'toggle'" v-model="model[f.name]" :label="f.label"
                :disable="isLocked(f)" />

      <q-select v-else-if="f.type === 'select' || f.type === 'multiselect'" v-model="model[f.name]"
                :options="optionsOf(f)" emit-value map-options outlined dense
                :multiple="f.type === 'multiselect'" :use-chips="f.type === 'multiselect'"
                :clearable="!f.required && f.type !== 'multiselect'"
                :label="f.label + (f.required ? ' *' : '')" :hint="hintOf(f)" :readonly="isLocked(f)"
                :rules="rulesOf(f)" />

      <q-input v-else v-model="model[f.name]" outlined dense
               :type="f.type === 'number' ? 'number' : f.type === 'textarea' ? 'textarea' : f.type === 'date' ? 'date' : 'text'"
               :autogrow="f.type === 'textarea'" :step="f.step" :prefix="f.prefix" :suffix="f.suffix"
               :label="f.label + (f.required ? ' *' : '')" :hint="hintOf(f)" :maxlength="f.maxlength"
               :readonly="isLocked(f)" :input-class="f.mono ? 'mono' : ''" :stack-label="f.type === 'date'"
               :rules="rulesOf(f)" @update:model-value="v => onInput(f, v)" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * Renders a form from a field list, so setup screens are described as data.
 * Field: { name, label, type: text|number|textarea|date|toggle|select|multiselect, required,
 *          options (array or fn(model)), hint, col, maxlength, uppercase, mono, lockedOnEdit,
 *          visible(model), rules: [fn], number: true (coerce), step, prefix, suffix }
 */
const props = defineProps({
  fields: { type: Array, required: true },
  modelValue: { type: Object, required: true },
  editing: { type: Boolean, default: false }
})

const model = computed(() => props.modelValue)
const visibleFields = computed(() => props.fields.filter(f => !f.visible || f.visible(model.value)))

const isLocked = f => (props.editing && f.lockedOnEdit) || (typeof f.readonly === 'function' ? f.readonly(model.value) : !!f.readonly)
const hintOf = f => typeof f.hint === 'function' ? f.hint(model.value) : f.hint
const optionsOf = f => typeof f.options === 'function' ? f.options(model.value) : (f.options || [])

function rulesOf (f) {
  const r = []
  if (f.required) {
    r.push(v => (f.type === 'multiselect' ? Array.isArray(v) && v.length > 0 : v !== null && v !== undefined && String(v).trim() !== '') || `${f.label} is required`)
  }
  return r.concat(f.rules || [])
}

function onInput (f, v) {
  if (f.uppercase && typeof v === 'string') model.value[f.name] = v.toUpperCase()
  else if (f.type === 'number') model.value[f.name] = v === '' || v === null ? null : Number(v)
}
</script>
