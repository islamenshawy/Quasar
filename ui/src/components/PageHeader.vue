<template>
  <div class="row items-center q-col-gutter-md q-mb-md">
    <div class="col-12 col-md">
      <div class="row items-center no-wrap q-gutter-x-sm">
        <q-btn v-if="back" flat round dense icon="arrow_back" :to="back" aria-label="Back" />
        <div>
          <div class="text-h5 text-weight-medium row items-center q-gutter-x-sm">
            <span>{{ title }}</span>
            <slot name="badge" />
          </div>
          <div v-if="subtitle || $slots.subtitle" class="text-body2 muted">
            <slot name="subtitle">{{ subtitle }}</slot>
          </div>
        </div>
      </div>
    </div>
    <div class="col-12 col-md-auto row q-gutter-sm justify-end">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  back: { type: [String, Object], default: null }
})
</script>
