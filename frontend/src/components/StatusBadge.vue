<template>
  <q-badge :color="cor" :label="rotulo" class="text-weight-medium q-px-sm q-py-xs" />
</template>

<script setup>
import { computed } from 'vue'
import { STATUS } from 'src/constants/dominio'

const props = defineProps({
  status: { type: String, required: true }
})

const cor = computed(() => STATUS[props.status]?.cor ?? 'grey')
const rotulo = computed(() => STATUS[props.status]?.rotulo ?? props.status)
</script>
