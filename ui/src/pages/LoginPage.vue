<template>
  <q-layout>
    <q-page-container>
      <q-page class="flex flex-center login-bg">
        <q-card flat bordered style="width: 400px; max-width: 92vw">
          <q-card-section class="text-center q-pt-lg">
            <q-icon name="credit_card" size="40px" color="primary" />
            <div class="text-h6 q-mt-sm">CMS Console</div>
            <q-badge color="warning" text-color="dark" label="TEST ENVIRONMENT" class="q-mt-xs" />
          </q-card-section>

          <q-card-section v-if="mode === 'signin'">
            <q-form class="q-gutter-md" @submit="signIn">
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
            <div class="text-subtitle1 text-weight-medium">Choose a new password</div>
            <div class="text-body2 muted q-mb-md">
              Your password is temporary. At least 10 characters with upper case, lower case, a digit and a symbol.
            </div>
            <q-form class="q-gutter-md" @submit="change">
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
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api.js'
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
.login-bg { background: linear-gradient(160deg, #12324A 0%, #1F5B6E 55%, #f3f5f8 55%); }
.body--dark .login-bg { background: linear-gradient(160deg, #12324A 0%, #1F5B6E 55%, #0F151C 55%); }
</style>
