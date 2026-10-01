<template>
  <div class="row q-gutter-sm items-center">
    <q-btn
      v-for="acao in acoes"
      :key="acao.chave"
      :color="acao.cor"
      :icon="acao.icone"
      :label="acao.rotulo"
      :loading="processando && acaoClicada === acao.chave"
      :disable="processando"
      :data-teste="`acao-${acao.chave}`"
      unelevated
      no-caps
      @click="executar(acao)"
    />

    <div v-if="acoes.length === 0" class="text-grey-6 text-caption">
      Nenhuma acao disponivel para o seu perfil neste status.
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

/**
 * Botoes de acao do chamado, filtrados por perfil + status + relacao com o chamado.
 *
 * Esconder o botao e so UX: quem recusa de verdade e o backend, com 403 ou 422.
 */
const props = defineProps({
  chamado: { type: Object, required: true },
  perfil: { type: String, required: true },
  usuarioId: { type: [Number, String], default: null },
  /**
   * A acao emitida ainda esta em andamento? Quem sabe e o pai, que faz a requisicao: o
   * `emit` nao espera o handler, entao o componente sozinho nao tem como saber (FE-02).
   * Enquanto `true`, todos os botoes ficam travados e o clicado mostra o loading.
   */
  processando: { type: Boolean, default: false }
})

const emit = defineEmits(['assumir', 'alterar-status'])

/**
 * As arestas da maquina de estados, uma por linha, NA MESMA ORDEM e com os MESMOS perfis
 * da tabela do backend (TransicaoStatus.TABELA). Mudou la, muda aqui -- e so aqui (FE-01).
 *
 * `evento` e a forma de pedir a transicao a API: assumir tem rota propria, as demais vao
 * pela rota de status. A ordem da tabela e a ordem dos botoes na tela.
 */
const ARESTAS = [
  { de: 'ABERTO', para: 'EM_ATENDIMENTO', perfis: ['ATENDENTE', 'ADMIN'], evento: 'assumir',
    chave: 'assumir', rotulo: 'Assumir', icone: 'assignment_ind', cor: 'primary' },
  { de: 'EM_ATENDIMENTO', para: 'RESOLVIDO', perfis: ['ATENDENTE', 'ADMIN'], evento: 'alterar-status',
    chave: 'resolver', rotulo: 'Resolver', icone: 'task_alt', cor: 'teal-7' },
  { de: 'RESOLVIDO', para: 'FECHADO', perfis: ['SOLICITANTE', 'ADMIN'], evento: 'alterar-status',
    chave: 'fechar', rotulo: 'Confirmar e fechar', icone: 'check_circle', cor: 'positive' },
  { de: 'RESOLVIDO', para: 'EM_ATENDIMENTO', perfis: ['SOLICITANTE', 'ADMIN'], evento: 'alterar-status',
    chave: 'reabrir', rotulo: 'Reabrir', icone: 'restart_alt', cor: 'orange-8' }
]

/**
 * Relacao com o chamado que cada perfil precisa ter para ALTERAR O STATUS -- a mesma regra do
 * PermissaoService.verificarAlteracaoStatus: o admin age em qualquer chamado; o atendente, so
 * no que atende; o solicitante, so no que abriu. Assumir nao exige relacao (verificarAssuncao).
 */
const RELACAO_PARA_ALTERAR_STATUS = {
  ADMIN: () => true,
  ATENDENTE: (chamado, usuarioId) => mesmoId(chamado.atendente?.id, usuarioId),
  SOLICITANTE: (chamado, usuarioId) => mesmoId(chamado.solicitante?.id, usuarioId)
}

/** Compara como texto: o id da rota chega string, o da API chega numero. */
function mesmoId(a, b) {
  return a != null && String(a) === String(b)
}

function cumpreRelacao(aresta) {
  if (aresta.evento === 'assumir') return true
  const relacao = RELACAO_PARA_ALTERAR_STATUS[props.perfil]
  return relacao !== undefined && relacao(props.chamado, props.usuarioId)
}

const acoes = computed(() => ARESTAS.filter((aresta) =>
  aresta.de === props.chamado?.status &&
  aresta.perfis.includes(props.perfil) &&
  cumpreRelacao(aresta)
))

/** So para saber em qual botao mostrar o loading enquanto o pai processa. */
const acaoClicada = ref(null)

function executar(acao) {
  if (props.processando) return
  acaoClicada.value = acao.chave
  if (acao.evento === 'assumir') {
    emit('assumir')
  } else {
    emit('alterar-status', acao.para)
  }
}
</script>
