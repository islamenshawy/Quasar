import { computed, reactive } from 'vue'

/**
 * Signed-in user (from GET /api/auth/me). Filled by the router guard; cleared on sign-out or 401.
 * Roles: ADMIN, SUPERVISOR, OPERATOR, VIEWER.
 */
export const session = reactive({
  loaded: false,
  user: null
})

export function setUser (user) {
  session.user = user
  session.loaded = true
}

export function clearUser () {
  session.user = null
  session.loaded = true
}

const has = r => !!session.user?.roles?.includes(r)

export const can = reactive({
  admin: computed(() => has('ADMIN')),
  supervise: computed(() => has('ADMIN') || has('SUPERVISOR')),
  write: computed(() => has('ADMIN') || has('SUPERVISOR') || has('OPERATOR'))
})
