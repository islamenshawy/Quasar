<template>
  <!--
    The Q-mark. A quasar seen at an angle is a letter Q: the accretion disk is the bowl,
    the relativistic jet through the core is the tail. Short counter-jet above, long jet below.
  -->
  <svg :width="size" :height="size" viewBox="0 0 64 64" role="img" aria-label="Quasar" class="qz-mark" :class="{ spin: animated }">
    <defs>
      <radialGradient :id="`${id}-core`" cx="50%" cy="50%" r="50%">
        <stop offset="0%" stop-color="#FFFFFF" />
        <stop offset="35%" stop-color="#E6ECFF" />
        <stop offset="70%" stop-color="#8EA2FF" stop-opacity=".55" />
        <stop offset="100%" stop-color="#5B4BE8" stop-opacity="0" />
      </radialGradient>
      <linearGradient :id="`${id}-disk`" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#3E6BFF" />
        <stop offset="45%" stop-color="#C9D4FF" />
        <stop offset="60%" stop-color="#FFFFFF" />
        <stop offset="100%" stop-color="#5B4BE8" />
      </linearGradient>
      <linearGradient :id="`${id}-jet`" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#FFF3D6" />
        <stop offset="55%" stop-color="#FFC98A" />
        <stop offset="100%" stop-color="#FF6B5B" stop-opacity="0" />
      </linearGradient>
      <linearGradient :id="`${id}-cjet`" x1="0" y1="1" x2="0" y2="0">
        <stop offset="0%" stop-color="#FFF3D6" stop-opacity=".9" />
        <stop offset="100%" stop-color="#FFF3D6" stop-opacity="0" />
      </linearGradient>
    </defs>

    <!-- halo -->
    <circle cx="28" cy="28" r="18" :fill="`url(#${id}-core)`" opacity=".35" />
    <!-- counter-jet (top left) and back half of the disk, behind the jet -->
    <g transform="rotate(-42 28 28)">
      <rect x="26.6" y="3" width="2.8" height="20" rx="1.4" :fill="`url(#${id}-cjet)`" />
    </g>
    <g transform="rotate(-18 28 28)">
      <path d="M7 28 A21 15 0 0 1 49 28" fill="none" :stroke="`url(#${id}-disk)`" stroke-width="6" stroke-linecap="round" opacity=".6" />
    </g>
    <!-- the jet = the tail of the Q, leaving to the lower right -->
    <g transform="rotate(-42 28 28)">
      <rect x="26" y="30" width="4" height="36" rx="2" :fill="`url(#${id}-jet)`" />
    </g>
    <!-- front half of the disk, over the jet -->
    <g transform="rotate(-18 28 28)">
      <path d="M49 28 A21 15 0 0 1 7 28" fill="none" :stroke="`url(#${id}-disk)`" stroke-width="6.5" stroke-linecap="round" />
    </g>
    <!-- core -->
    <circle cx="28" cy="28" r="7" :fill="`url(#${id}-core)`" />
    <circle cx="28" cy="28" r="3" fill="#fff" />
  </svg>
</template>

<script setup>
import { useId } from 'vue'

defineProps({
  size: { type: [Number, String], default: 32 },
  animated: { type: Boolean, default: false }
})
const id = `qz${useId().replace(/[^a-zA-Z0-9]/g, '')}`
</script>

<style scoped>
.qz-mark { display: block; flex: none; overflow: visible; filter: drop-shadow(0 0 8px rgba(142, 162, 255, .5)); }
.qz-mark.spin { animation: qz-breathe 6s ease-in-out infinite; }
@keyframes qz-breathe {
  0%, 100% { filter: drop-shadow(0 0 6px rgba(142, 162, 255, .45)); }
  50% { filter: drop-shadow(0 0 16px rgba(255, 201, 138, .7)); }
}
</style>
