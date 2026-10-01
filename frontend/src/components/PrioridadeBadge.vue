<template>
  <q-badge :color="cor" :label="rotulo" class="text-weight-medium q-px-sm q-py-xs">
    <q-icon :name="icone" size="14px" class="q-ml-xs" />
  </q-badge>
</template>

<script setup>
import { computed } from 'vue'
import { PRIORIDADES } from 'src/constants/dominio'

const props = defineProps({
  prioridade: { type: String, required: true }
})

const cor = computed(() => PRIORIDADES[props.prioridade]?.cor ?? 'grey')
const rotulo = computed(() => PRIORIDADES[props.prioridade]?.rotulo ?? props.prioridade)
const icone = computed(() => PRIORIDADES[props.prioridade]?.icone ?? 'help_outline')
</script>
