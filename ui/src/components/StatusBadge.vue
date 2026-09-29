<template>
  <q-badge :color="statusColor(status)" class="q-px-sm q-py-xs text-weight-medium" :label="label(status)" />
</template>

<script setup>
import { label, statusColor } from '../lib/format.js'

defineProps({ status: { type: String, default: '' } })
</script>
