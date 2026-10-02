<template>
  <q-layout>
    <q-page-container>
      <q-page class="qz-login">
        <!-- the scene: nebula clouds, starfield, the two quasar cores -->
        <div class="scene" aria-hidden="true">
          <div class="cloud c1" /><div class="cloud c2" /><div class="cloud c3" /><div class="jet" />
          <div class="stars s1" /><div class="stars s2" />
          <div class="core k1" /><div class="core k2" />
        </div>

        <div class="content">
          <section class="hero gt-sm">
            <QuasarMark :size="64" />
            <h1 class="qz-wordmark qz-gradient-text">Quasar</h1>
            <p class="tagline">Card management at the speed of light.</p>
            <ul>
              <li><q-icon name="bolt" /> Real-time authorisation, BASE24 ISO 8583</li>
              <li><q-icon name="credit_card" /> Issuing, renewal and full card lifecycle</li>
              <li><q-icon name="verified_user" /> Chip, PIN and HSM-backed security</li>
            </ul>
          </section>

        <q-card flat class="glass">
          <q-card-section class="q-pt-lg q-pb-sm">
            <div class="row items-center q-gutter-x-sm lt-md q-mb-md">
              <QuasarMark :size="36" /><span class="qz-wordmark" style="font-size: 24px">Quasar</span>
            </div>
            <div class="row items-center justify-between">
              <div class="text-h6">{{ mode === 'signin' ? 'Sign in' : 'New password' }}</div>
              <span class="env">TEST ENVIRONMENT</span>
            </div>
            <div class="text-body2 muted">{{ mode === 'signin' ? 'Use your Quasar operator account.' : '' }}</div>
          </q-card-section>

          <q-card-section v-if="mode === 'signin'">
            <q-form class="q-gutter-y-md" @submit="signIn">
              <q-input v-model="username" outlined label="Username" autocomplete="username" autofocus
                       :rules="[v => !!v || 'Required']" />
              <q-input v-model="password" outlined label="Password" :type="show ? 'text' : 'password'"
                       autocomplete="current-password" :rules="[v => !!v || 'Required']">
                <template #append>
                  <q-icon :name="show ? 'visibility_off' : 'visibility'" class="cursor-pointer" @click="show = !show" />
                </template>
              </q-input>
              <q-banner v-if="error" dense rounded class="bg-red-1 text-negative">{{ error }}</q-banner>
              <q-btn type="submit" unelevated no-caps color="primary" label="Sign in" class="full-width" :loading="busy" />
            </q-form>
          </q-card-section>

          <q-card-section v-else>
            <div class="text-body2 muted q-mb-md">
              Your password is temporary. At least 10 characters with upper case, lower case, a digit and a symbol.
            </div>
            <q-form class="q-gutter-y-md" @submit="change">
              <q-input v-model="current" outlined label="Current (temporary) password" type="password"
                       autocomplete="current-password" :rules="[v => !!v || 'Required']" />
              <q-input v-model="next" outlined label="New password" type="password" autocomplete="new-password"
                       :rules="[v => strong(v) || 'Too weak']" />
              <q-input v-model="repeat" outlined label="Repeat new password" type="password" autocomplete="new-password"
                       :rules="[v => v === next || 'Does not match']" />
              <q-banner v-if="error" dense rounded class="bg-red-1 text-negative">{{ error }}</q-banner>
              <q-btn type="submit" unelevated no-caps color="primary" label="Change password" class="full-width" :loading="busy" />
              <q-btn flat no-caps label="Sign out" class="full-width" @click="signOut" />
            </q-form>
          </q-card-section>
        </q-card>
        </div>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api.js'
import QuasarMark from '../components/QuasarMark.vue'
import { clearUser, session, setUser } from '../lib/session.js'

const route = useRoute()
const router = useRouter()
const mode = ref(route.query.mode === 'password' ? 'password' : 'signin')
const username = ref('')
const password = ref('')
const show = ref(false)
const current = ref('')
const next = ref('')
const repeat = ref('')
const busy = ref(false)
const error = ref('')

const strong = v => !!v && v.length >= 10 && /[A-Z]/.test(v) && /[a-z]/.test(v) && /\d/.test(v) && /[^A-Za-z0-9]/.test(v)
const goOn = () => router.replace(typeof route.query.next === 'string' && route.query.next.startsWith('/') ? route.query.next : '/')

async function signIn () {
  busy.value = true
  error.value = ''
  try {
    await api.get('/auth/csrf', { quiet: true })
    const me = await api.post('/auth/login', { username: username.value, password: password.value }, { quiet: true })
    setUser(me)
    if (me.mustChangePassword) {
      current.value = password.value
      mode.value = 'password'
    } else {
      goOn()
    }
  } catch (e) {
    error.value = e.message
  } finally {
    password.value = ''
    busy.value = false
  }
}

async function change () {
  busy.value = true
  error.value = ''
  try {
    const me = await api.post('/auth/password', { current: current.value, next: next.value }, { quiet: true })
    setUser(me)
    goOn()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function signOut () {
  await api.post('/auth/logout', {}, { quiet: true }).catch(() => {})
  clearUser()
  mode.value = 'signin'
}

onMounted(() => {
  api.get('/auth/csrf', { quiet: true }).catch(() => {})
  if (session.user?.mustChangePassword) mode.value = 'password'
})
</script>

<style scoped>
/* always deep space, in both themes: this is the brand moment */
.qz-login { position: relative; overflow: hidden; min-height: 100vh; background: #07060F; color: #ECEAFB; }
.scene { position: absolute; inset: 0; pointer-events: none; }
.cloud { position: absolute; border-radius: 50%; filter: blur(60px); opacity: .75; animation: drift 38s ease-in-out infinite alternate; }
.c1 { width: 70vw; height: 46vw; left: 10vw; top: 6vh; background: radial-gradient(closest-side, rgba(91, 75, 232, .75), rgba(62, 107, 255, .25) 60%, transparent); }
.c2 { width: 52vw; height: 30vw; left: 38vw; top: 38vh; background: radial-gradient(closest-side, rgba(217, 70, 176, .55), rgba(138, 92, 246, .2) 60%, transparent); animation-duration: 46s; animation-direction: alternate-reverse; }
.c3 { width: 40vw; height: 24vw; left: -6vw; top: 52vh; background: radial-gradient(closest-side, rgba(62, 107, 255, .5), transparent); animation-duration: 52s; }
.jet { position: absolute; width: 22vw; height: 1.2vw; left: 64vw; top: 70vh; border-radius: 50%; filter: blur(8px);
  transform: rotate(18deg); background: linear-gradient(90deg, transparent, rgba(255, 107, 91, .85), rgba(217, 70, 176, .7), transparent); opacity: .7; }
.stars { position: absolute; inset: 0; }
.s1 { background-image:
    radial-gradient(1px 1px at 10% 20%, #fff 50%, transparent 51%), radial-gradient(1px 1px at 30% 80%, #fff 50%, transparent 51%),
    radial-gradient(1.5px 1.5px at 55% 15%, #dfe6ff 50%, transparent 51%), radial-gradient(1px 1px at 75% 55%, #fff 50%, transparent 51%),
    radial-gradient(1px 1px at 90% 85%, #ffd6f0 50%, transparent 51%), radial-gradient(1.2px 1.2px at 42% 48%, #fff 50%, transparent 51%);
  background-size: 260px 220px; opacity: .8; animation: twinkle 7s ease-in-out infinite alternate; }
.s2 { background-image:
    radial-gradient(1px 1px at 20% 40%, rgba(255, 255, 255, .6) 50%, transparent 51%), radial-gradient(1px 1px at 65% 70%, rgba(200, 210, 255, .7) 50%, transparent 51%),
    radial-gradient(1px 1px at 85% 25%, rgba(255, 255, 255, .5) 50%, transparent 51%);
  background-size: 170px 150px; opacity: .6; }
.core { position: absolute; border-radius: 50%; background: #fff;
  box-shadow: 0 0 18px 6px #fff, 0 0 60px 24px rgba(160, 185, 255, .75), 0 0 160px 70px rgba(91, 75, 232, .45);
  animation: pulse 5.5s ease-in-out infinite; }
.k1 { width: 12px; height: 12px; left: 44vw; top: 41vh; }
.k2 { width: 10px; height: 10px; left: 50vw; top: 50vh; animation-delay: -2.7s; }
@keyframes drift { from { transform: translate3d(0, 0, 0) scale(1); } to { transform: translate3d(3vw, -2vh, 0) scale(1.08); } }
@keyframes pulse { 0%, 100% { opacity: .85; transform: scale(1); } 50% { opacity: 1; transform: scale(1.25); } }
@keyframes twinkle { from { opacity: .45; } to { opacity: .9; } }

.content { position: relative; z-index: 1; min-height: 100vh; display: flex; align-items: center; justify-content: center;
  gap: 8vw; padding: 32px 16px; }
.hero { max-width: 440px; }
.hero h1 { font-size: 76px; margin: 18px 0 6px; line-height: 1; }
.tagline { font-size: 19px; color: rgba(236, 234, 251, .78); margin: 0 0 28px; }
.hero ul { list-style: none; padding: 0; margin: 0; display: grid; gap: 12px; }
.hero li { display: flex; align-items: center; gap: 12px; font-size: 15px; color: rgba(236, 234, 251, .86); }
.hero li .q-icon { width: 34px; height: 34px; border-radius: 10px; font-size: 19px; color: #fff;
  background: rgba(255, 255, 255, .08); border: 1px solid rgba(255, 255, 255, .14); }

.glass { width: 410px; max-width: 100%; border-radius: 20px; color: var(--qz-text);
  background: rgba(255, 255, 255, .9); backdrop-filter: blur(18px) saturate(1.3);
  border: 1px solid rgba(255, 255, 255, .6); box-shadow: 0 40px 100px -30px rgba(20, 10, 80, .9); }
.body--dark .glass { background: rgba(21, 18, 43, .78); border-color: rgba(200, 190, 255, .14); }
.env { font-size: 10px; font-weight: 700; letter-spacing: .12em; padding: 3px 7px; border-radius: 6px;
  color: #9A5B00; background: rgba(232, 150, 45, .14); border: 1px solid rgba(232, 150, 45, .35); }
.body--dark .env { color: #FFD9A0; }
</style>
