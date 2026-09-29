<template>
  <div class="cms-card" :data-tier="tier" aria-label="Card preview">
    <div class="tier">{{ productName || 'Card' }}</div>
    <div class="scheme">{{ scheme }}</div>
    <div class="chip" />
    <div class="pan">{{ groupedPan }}</div>
    <div class="bottom">
      <span class="ellipsis">{{ name || 'NAME ON CARD' }}</span>
      <span>{{ expiry(expiryYymm) }}</span>
    </div>
    <q-badge v-if="status" class="status" :color="statusColor(status)" :label="label(status)" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { expiry, label, statusColor } from '../lib/format.js'

const props = defineProps({
  pan: { type: String, default: '' },
  name: { type: String, default: '' },
  expiryYymm: { type: String, default: '' },
  productName: { type: String, default: '' },
  scheme: { type: String, default: '' },
  tier: { type: String, default: 'CLASSIC' },
  status: { type: String, default: '' }
})

const groupedPan = computed(() => (props.pan || '•••• •••• •••• ••••').replace(/(.{4})(?=.)/g, '$1 '))
</script>

<style scoped>
.cms-card {
  --card-a: #12324A; --card-b: #1F5B6E;
  aspect-ratio: 85.6 / 53.98; max-width: 420px; border-radius: 14px; padding: 20px 22px; color: #EAF2F5;
  position: relative; overflow: hidden; background: linear-gradient(135deg, var(--card-a), var(--card-b));
  box-shadow: 0 6px 18px rgba(0, 0, 0, .18);
}
.cms-card[data-tier=GOLD] { --card-a: #5B4514; --card-b: #9C7A2B; }
.cms-card[data-tier=PLATINUM] { --card-a: #3A3F46; --card-b: #7B838C; }
.cms-card[data-tier=PAYROLL] { --card-a: #1E4D3A; --card-b: #2E7A57; }
.tier { position: absolute; top: 18px; left: 22px; font-size: 13px; opacity: .85; }
.scheme { position: absolute; top: 16px; right: 22px; font-weight: 600; letter-spacing: .04em; }
.chip { width: 44px; height: 33px; border-radius: 6px; background: linear-gradient(135deg, #D7C27A, #A88D3E); margin-top: 28px; }
.pan { font: 500 clamp(15px, 4.4vw, 21px)/1 "IBM Plex Mono", Consolas, monospace; letter-spacing: .08em; margin-top: 18px; text-shadow: 0 1px 0 rgba(0, 0, 0, .35); white-space: nowrap; }
.bottom { display: flex; justify-content: space-between; gap: 12px; margin-top: 14px; font-family: "IBM Plex Mono", Consolas, monospace; font-size: 14px; }
.status { position: absolute; bottom: 12px; right: 14px; }
</style>
