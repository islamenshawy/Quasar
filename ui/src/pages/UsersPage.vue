<template>
  <q-page padding class="page">
    <PageHeader title="Users" subtitle="Console users and their roles. New users and password resets get a temporary password, changed at first sign-in.">
      <template #actions>
        <q-btn unelevated no-caps color="primary" icon="person_add" label="New user" @click="edit(null)" />
      </template>
    </PageHeader>

    <q-card flat bordered>
      <q-table flat :rows="rows" :columns="columns" row-key="id" :loading="loading" :pagination="{ rowsPerPage: 50 }"
               class="clickable-rows" @row-click="(e, r) => edit(r)">
        <template #body-cell-roles="p">
          <q-td :props="p"><q-chip v-for="r in p.value" :key="r" dense square size="sm" :label="r" /></q-td>
        </template>
        <template #body-cell-status="p">
          <q-td :props="p">
            <q-badge :color="p.value === 'ACTIVE' ? 'positive' : p.value === 'LOCKED' ? 'negative' : 'grey-6'" :label="label(p.value)" />
            <q-badge v-if="p.row.mustChangePassword" outline color="warning" label="temporary password" class="q-ml-xs" />
          </q-td>
        </template>
      </q-table>
    </q-card>

    <q-dialog v-model="dialog" persistent>
      <q-card style="width: 520px; max-width: 95vw">
        <q-form @submit="save">
          <q-card-section class="text-h6">{{ editing ? `Edit ${editing.username}` : 'New user' }}</q-card-section>
          <q-card-section class="q-pt-none q-gutter-md">
            <q-input v-model="f.username" outlined dense label="Username *" :readonly="!!editing" hint="3-64 of a-z, 0-9, . _ -"
                     :rules="[v => /^[a-z0-9._-]{3,64}$/.test(v || '') || 'Lower-case letters, digits, . _ -']" />
            <q-input v-model="f.fullName" outlined dense label="Full name *" :rules="[v => !!(v && v.trim()) || 'Required']" />
            <q-input v-model="f.email" outlined dense label="Email" />
            <div>
              <div class="text-caption muted">Roles *</div>
              <q-option-group v-model="f.roles" type="checkbox" :options="roleOptions" inline />
            </div>
            <q-select v-if="editing" v-model="f.status" outlined dense label="Status" :options="['ACTIVE', 'LOCKED', 'DISABLED']" />
          </q-card-section>
          <q-card-actions class="q-pa-md">
            <q-btn v-if="editing" flat no-caps color="warning" icon="lock_reset" label="Reset password" @click="reset" />
            <q-space />
            <q-btn flat no-caps label="Cancel" v-close-popup />
            <q-btn type="submit" unelevated no-caps color="primary" label="Save" :loading="busy" :disable="!f.roles.length" />
          </q-card-actions>
        </q-form>
      </q-card>
    </q-dialog>

    <q-dialog v-model="secretDialog" persistent>
      <q-card style="width: 460px; max-width: 95vw">
        <q-card-section>
          <div class="text-h6">Temporary password</div>
          <div class="text-body2 muted">Give it to <b>{{ secret.username }}</b> through a separate channel. It is shown once and must be changed at first sign-in.</div>
          <div class="mono text-h6 q-my-md">{{ secret.password }}</div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat no-caps icon="content_copy" label="Copy" @click="copyToClipboard(secret.password)" />
          <q-btn unelevated no-caps color="primary" label="Done" @click="secretDialog = false; secret = {}" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Notify, copyToClipboard, useQuasar } from 'quasar'
import PageHeader from '../components/PageHeader.vue'
import { api } from '../lib/api.js'
import { dateTime, label } from '../lib/format.js'

const $q = useQuasar()
const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const secretDialog = ref(false)
const secret = ref({})
const editing = ref(null)
const busy = ref(false)
const f = reactive({ username: '', fullName: '', email: '', roles: [], status: 'ACTIVE' })
const roleOptions = [
  { label: 'Admin (users, approval policy)', value: 'ADMIN' },
  { label: 'Supervisor (setup, approvals)', value: 'SUPERVISOR' },
  { label: 'Operator (day to day)', value: 'OPERATOR' },
  { label: 'Viewer (read only)', value: 'VIEWER' }
]
const columns = [
  { name: 'username', label: 'Username', field: 'username', align: 'left', classes: 'mono', sortable: true },
  { name: 'fullName', label: 'Name', field: 'fullName', align: 'left' },
  { name: 'roles', label: 'Roles', field: 'roles', align: 'left' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'lastLoginAt', label: 'Last sign-in', field: 'lastLoginAt', format: dateTime, align: 'left' },
  { name: 'failedLogins', label: 'Failed', field: 'failedLogins', align: 'right' }
]

async function load () {
  loading.value = true
  try { rows.value = await api.get('/admin/users') } finally { loading.value = false }
}

function edit (u) {
  editing.value = u
  Object.assign(f, u ? { username: u.username, fullName: u.fullName, email: u.email || '', roles: [...u.roles], status: u.status }
    : { username: '', fullName: '', email: '', roles: ['OPERATOR'], status: 'ACTIVE' })
  dialog.value = true
}

async function save () {
  busy.value = true
  try {
    if (editing.value) {
      await api.put(`/admin/users/${editing.value.id}`, { ...f })
      Notify.create({ type: 'positive', message: `${f.username} updated` })
    } else {
      secret.value = await api.post('/admin/users', { ...f })
      secretDialog.value = true
    }
    dialog.value = false
    load()
  } catch { /* shown */ } finally {
    busy.value = false
  }
}

function reset () {
  $q.dialog({ title: 'Reset password', message: `Give ${editing.value.username} a new temporary password? It also unlocks the account.`, cancel: true })
    .onOk(async () => {
      secret.value = await api.post(`/admin/users/${editing.value.id}/reset-password`, {})
      dialog.value = false
      secretDialog.value = true
      load()
    })
}

onMounted(load)
</script>
