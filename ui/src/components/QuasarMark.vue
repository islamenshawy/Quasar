<template>
  <!-- Quasar mark: two bright cores (the double quasar) inside a tilted accretion ring -->
  <svg :width="size" :height="size" viewBox="0 0 48 48" role="img" aria-label="Quasar" class="qz-mark">
    <defs>
      <radialGradient :id="`${id}-halo`" cx="50%" cy="50%" r="50%">
        <stop offset="0%" stop-color="#BFD0FF" stop-opacity=".9" />
        <stop offset="45%" stop-color="#6E8FFF" stop-opacity=".45" />
        <stop offset="100%" stop-color="#5B4BE8" stop-opacity="0" />
      </radialGradient>
      <linearGradient :id="`${id}-ring`" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#8A5CF6" />
        <stop offset="55%" stop-color="#D946B0" />
        <stop offset="100%" stop-color="#FF6B5B" />
      </linearGradient>
      <linearGradient :id="`${id}-ring2`" x1="1" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#3E6BFF" />
        <stop offset="100%" stop-color="#8A5CF6" stop-opacity=".2" />
      </linearGradient>
    </defs>
    <circle cx="24" cy="24" r="17" :fill="`url(#${id}-halo)`" />
    <ellipse cx="24" cy="24" rx="21" ry="8.5" transform="rotate(-28 24 24)" fill="none"
             :stroke="`url(#${id}-ring)`" stroke-width="2.6" stroke-linecap="round" stroke-dasharray="88 18" />
    <ellipse cx="24" cy="24" rx="15" ry="5.5" transform="rotate(-28 24 24)" fill="none"
             :stroke="`url(#${id}-ring2)`" stroke-width="1.4" opacity=".9" />
    <circle cx="20.5" cy="21.5" r="3.4" fill="#fff" />
    <circle cx="27.5" cy="26.5" r="2.7" fill="#fff" />
    <circle cx="20.5" cy="21.5" r="5.5" fill="#fff" opacity=".18" />
    <circle cx="27.5" cy="26.5" r="4.6" fill="#fff" opacity=".16" />
  </svg>
</template>

<script setup>
import { useId } from 'vue'

defineProps({ size: { type: [Number, String], default: 32 } })
const id = `qz${useId().replace(/[^a-zA-Z0-9]/g, '')}`
</script>

<style scoped>
.qz-mark { display: block; flex: none; filter: drop-shadow(0 0 6px rgba(110, 143, 255, .45)); }
</style>
