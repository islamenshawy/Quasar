<template>
  <section class="qz-hero">
    <div class="qz-hero-stars" aria-hidden="true" />
    <div class="qz-hero-text">
      <div class="qz-hero-eyebrow">{{ eyebrow }}</div>
      <h1 class="qz-hero-title">{{ title }}</h1>
      <div class="qz-hero-stats">
        <div><span class="v ok">{{ approved }}</span><span class="l">approved today</span></div>
        <div><span class="v bad">{{ declined }}</span><span class="l">declined today</span></div>
        <div><span class="v">{{ total }}</span><span class="l">messages</span></div>
      </div>
      <div class="qz-hero-actions"><slot /></div>
    </div>

    <!-- approval rate as an accretion disk: the lit arc is the share approved -->
    <div class="qz-gauge" role="img" :aria-label="`Approval rate ${rateLabel}`">
      <svg viewBox="0 0 240 200">
        <defs>
          <linearGradient id="qz-g-arc" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#3E6BFF" /><stop offset="50%" stop-color="#C9D4FF" /><stop offset="100%" stop-color="#8A5CF6" />
          </linearGradient>
          <radialGradient id="qz-g-core" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stop-color="#fff" /><stop offset="40%" stop-color="#E6ECFF" stop-opacity=".9" />
            <stop offset="100%" stop-color="#5B4BE8" stop-opacity="0" />
          </radialGradient>
          <linearGradient id="qz-g-jet" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#FFF3D6" stop-opacity="0" /><stop offset="45%" stop-color="#FFC98A" stop-opacity=".9" />
            <stop offset="100%" stop-color="#FF6B5B" stop-opacity="0" />
          </linearGradient>
        </defs>
        <g transform="rotate(-38 120 100)"><rect x="117" y="-10" width="6" height="220" rx="3" fill="url(#qz-g-jet)" class="jet" /></g>
        <g transform="rotate(-14 120 100)">
          <ellipse cx="120" cy="100" rx="100" ry="46" fill="none" stroke="rgba(255,255,255,.1)" stroke-width="14" />
          <ellipse v-if="total" cx="120" cy="100" rx="100" ry="46" fill="none" stroke="url(#qz-g-arc)" stroke-width="14" stroke-linecap="round"
                   pathLength="100" :stroke-dasharray="`${rate} 100`" class="arc" />
          <ellipse cx="120" cy="100" rx="70" ry="30" fill="none" stroke="rgba(201,212,255,.25)" stroke-width="1.5" stroke-dasharray="2 5" class="spin" />
        </g>
        <circle cx="120" cy="100" r="46" fill="url(#qz-g-core)" opacity=".75" />
      </svg>
      <div class="qz-gauge-label">
        <div class="qz-gauge-value">{{ rateLabel }}</div>
        <div class="qz-gauge-caption">approval rate</div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, default: '' },
  approved: { type: Number, default: 0 },
  declined: { type: Number, default: 0 }
})
const total = computed(() => props.approved + props.declined)
const rate = computed(() => total.value ? Math.round(100 * props.approved / total.value) : 0)
const rateLabel = computed(() => total.value ? `${rate.value}%` : '—')
</script>

<style>
.qz-hero { position: relative; overflow: hidden; display: flex; align-items: center; gap: 24px; padding: 28px 32px; border-radius: 22px;
  color: #fff; min-height: 230px;
  background:
    radial-gradient(420px 260px at 82% 50%, rgba(91, 75, 232, .65), transparent 70%),
    radial-gradient(360px 220px at 100% 110%, rgba(217, 70, 176, .4), transparent 70%),
    radial-gradient(300px 200px at 0% 0%, rgba(62, 107, 255, .3), transparent 70%),
    linear-gradient(120deg, #0A0819 0%, #15103D 60%, #0E0B26 100%);
  box-shadow: 0 30px 60px -30px rgba(40, 28, 130, .7); }
.qz-hero-stars { position: absolute; inset: 0; pointer-events: none; opacity: .8;
  background-image: radial-gradient(1px 1px at 10% 20%, #fff 50%, transparent 51%), radial-gradient(1px 1px at 40% 80%, #fff 50%, transparent 51%),
    radial-gradient(1.4px 1.4px at 66% 14%, #dfe6ff 50%, transparent 51%), radial-gradient(1px 1px at 90% 60%, #ffd6f0 50%, transparent 51%);
  background-size: 220px 120px; }
.qz-hero-text { position: relative; flex: 1; min-width: 0; }
.qz-hero-eyebrow { font-size: 12px; font-weight: 700; letter-spacing: .16em; text-transform: uppercase; color: #C9C2FF; }
.qz-hero-title { font: 600 30px/1.15 var(--qz-font-display); letter-spacing: -.01em; margin: 8px 0 18px; }
.qz-hero-stats { display: flex; flex-wrap: wrap; gap: 28px; margin-bottom: 20px; }
.qz-hero-stats > div { display: flex; flex-direction: column; }
.qz-hero-stats .v { font: 600 30px/1 var(--qz-font-display); }
.qz-hero-stats .v.ok { color: #5EF0C3; }
.qz-hero-stats .v.bad { color: #FF9AA0; }
.qz-hero-stats .l { font-size: 12.5px; color: rgba(233, 231, 255, .65); margin-top: 4px; }
.qz-hero-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.qz-hero-actions .q-btn--outline { color: #fff !important; }
.qz-hero-actions .q-btn--outline:before { border-color: rgba(255, 255, 255, .35) !important; }
.qz-gauge { position: relative; width: 300px; flex: none; }
.qz-gauge svg { width: 100%; display: block; overflow: visible; }
.qz-gauge .arc { transition: stroke-dasharray 1.2s cubic-bezier(.2, .8, .2, 1); filter: drop-shadow(0 0 6px rgba(142, 162, 255, .8)); }
.qz-gauge .spin { transform-origin: 120px 100px; animation: qz-orbit 30s linear infinite; }
.qz-gauge .jet { animation: qz-jet 4s ease-in-out infinite; }
@keyframes qz-orbit { to { transform: rotate(360deg); } }
@keyframes qz-jet { 0%, 100% { opacity: .65; } 50% { opacity: 1; } }
.qz-gauge-label { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.qz-gauge-value { font: 700 34px/1 var(--qz-font-display); color: #0E0B26; text-shadow: 0 0 18px rgba(255, 255, 255, .9); }
.qz-gauge-caption { font-size: 11px; font-weight: 700; letter-spacing: .12em; text-transform: uppercase; color: #fff; margin-top: 6px;
  text-shadow: 0 1px 6px rgba(10, 8, 25, .9); }
@media (max-width: 767px) {
  .qz-hero { flex-direction: column; align-items: stretch; padding: 22px 18px; }
  .qz-gauge { width: 100%; max-width: 230px; align-self: center; }
  .qz-hero-title { font-size: 24px; }
}
</style>
