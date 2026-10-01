<template>
  <div>
    <q-timeline v-if="comentarios.length > 0" color="primary" layout="dense">
      <q-timeline-entry
        v-for="comentario in comentarios"
        :key="comentario.id"
        :subtitle="`${comentario.autor.nome} · ${formatarDataHora(comentario.criadoEm)}`"
        :icon="PERFIS[comentario.autor.perfil]?.icone ?? 'person'"
        :color="PERFIS[comentario.autor.perfil]?.cor ?? 'grey-7'"
      >
        <div class="text-body2" style="white-space: pre-wrap">{{ comentario.texto }}</div>
      </q-timeline-entry>
    </q-timeline>

    <div v-else class="text-grey-6 q-py-md">
      Nenhum comentario ainda. Seja o primeiro a escrever.
    </div>
  </div>
</template>

<script setup>
import { PERFIS } from 'src/constants/dominio'
import { formatarDataHora } from 'src/utils/formatacao'

defineProps({
  comentarios: { type: Array, required: true }
})
</script>
