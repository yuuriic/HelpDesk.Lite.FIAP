<template>
  <q-page padding>
    <div class="pagina">
      <q-btn flat dense no-caps icon="arrow_back" label="Voltar" class="q-mb-md"
             :to="{ name: 'chamados' }" />

      <q-inner-loading :showing="chamados.carregando && !chamado">
        <q-spinner-dots size="40px" color="primary" />
      </q-inner-loading>

      <template v-if="chamado">
        <q-card flat bordered class="q-mb-md">
          <q-card-section>
            <div class="row items-start justify-between q-gutter-md">
              <div class="col">
                <div class="text-caption text-grey-7">Chamado #{{ chamado.id }}</div>
                <div class="text-h5 q-mt-xs">{{ chamado.titulo }}</div>
              </div>
              <div class="row q-gutter-sm items-center">
                <StatusBadge :status="chamado.status" />
                <PrioridadeBadge :prioridade="chamado.prioridade" />
                <q-badge v-if="chamado.foraDoSla" color="negative" label="Fora do SLA" />
              </div>
            </div>

            <div class="text-body1 q-mt-md" style="white-space: pre-wrap">{{ chamado.descricao }}</div>
          </q-card-section>

          <q-separator />

          <q-card-section class="row q-col-gutter-md text-body2">
            <div class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Categoria</div>
              <div>{{ chamado.categoria?.nome }} ({{ chamado.categoria?.slaHoras }}h de SLA)</div>
            </div>
            <div class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Solicitante</div>
              <div>{{ chamado.solicitante?.nome }}</div>
            </div>
            <div class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Atendente</div>
              <div>{{ chamado.atendente?.nome ?? 'Ainda nao atribuido' }}</div>
            </div>
            <div class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Prazo do SLA</div>
              <div>{{ formatarDataHora(chamado.prazoSla) }}</div>
            </div>

            <div class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Aberto em</div>
              <div>{{ formatarDataHora(chamado.criadoEm) }}</div>
            </div>
            <div class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Atualizado em</div>
              <div>{{ formatarDataHora(chamado.atualizadoEm) }}</div>
            </div>
            <div v-if="chamado.resolvidoEm" class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Resolvido em</div>
              <div>{{ formatarDataHora(chamado.resolvidoEm) }}</div>
            </div>
            <div v-if="chamado.fechadoEm" class="col-6 col-md-3">
              <div class="text-caption text-grey-7">Fechado em</div>
              <div>{{ formatarDataHora(chamado.fechadoEm) }}</div>
            </div>
          </q-card-section>

          <q-separator />

          <q-card-actions class="q-pa-md">
            <ChamadoAcoes
              :chamado="chamado"
              :perfil="auth.perfil"
              :usuario-id="auth.usuario?.id"
              :processando="processandoAcao"
              @assumir="assumir"
              @alterar-status="alterarStatus"
            />
          </q-card-actions>
        </q-card>

        <q-card flat bordered>
          <q-card-section>
            <div class="text-h6">Linha do tempo</div>
            <ComentarioTimeline :comentarios="chamados.comentarios" class="q-mt-md" />
          </q-card-section>

          <q-separator />

          <q-card-section>
            <q-input
              v-model="novoComentario"
              type="textarea"
              outlined
              autogrow
              counter
              maxlength="2000"
              label="Escrever um comentario"
              data-teste="campo-comentario"
              :rules="[(v) => !v || v.trim().length >= 2 || 'Escreva pelo menos 2 caracteres']"
            />
            <div class="row justify-end q-mt-sm">
              <q-btn
                unelevated
                no-caps
                color="primary"
                icon="send"
                label="Comentar"
                :disable="novoComentario.trim().length < 2"
                :loading="enviandoComentario"
                @click="comentar"
              />
            </div>
          </q-card-section>
        </q-card>
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuasar } from 'quasar'
import { formatarDataHora } from 'src/utils/formatacao'
import StatusBadge from 'components/StatusBadge.vue'
import PrioridadeBadge from 'components/PrioridadeBadge.vue'
import ChamadoAcoes from 'components/ChamadoAcoes.vue'
import ComentarioTimeline from 'components/ComentarioTimeline.vue'
import { useAuthStore } from 'stores/auth'
import { useChamadosStore } from 'stores/chamados'

const route = useRoute()
const $q = useQuasar()
const auth = useAuthStore()
const chamados = useChamadosStore()

const novoComentario = ref('')
const enviandoComentario = ref(false)
/** Uma acao (assumir, alterar status) em voo: trava os botoes ate a resposta (FE-02). */
const processandoAcao = ref(false)

const chamado = computed(() => chamados.detalhe)

watch(
  () => route.params.id,
  (id) => {
    if (id) {
      chamados.carregarDetalhe(id).catch(() => {})
    }
  },
  { immediate: true }
)

onUnmounted(() => chamados.limparDetalhe())

/**
 * Executa uma acao do chamado com os botoes travados ate a resposta.
 *
 * Erro de API ja vira notify no interceptor do api.js; aqui ele so e absorvido para nao
 * virar "unhandled rejection" no handler do componente -- o mesmo `.catch(() => {})` que as
 * paginas usam ao carregar dados (EXC-03).
 */
async function executarAcao(acao, mensagemDeSucesso) {
  if (processandoAcao.value) return
  processandoAcao.value = true
  try {
    await acao()
    $q.notify({ type: 'positive', message: mensagemDeSucesso })
  } catch {
    // Ja notificado pelo interceptor.
  } finally {
    processandoAcao.value = false
  }
}

function assumir() {
  return executarAcao(() => chamados.assumir(chamado.value.id),
    'Chamado assumido. Ele foi para EM ATENDIMENTO.')
}

function alterarStatus(status) {
  return executarAcao(() => chamados.alterarStatus(chamado.value.id, status),
    `Chamado atualizado para ${status.replace('_', ' ')}.`)
}

async function comentar() {
  enviandoComentario.value = true
  try {
    await chamados.comentar(chamado.value.id, novoComentario.value.trim())
    novoComentario.value = ''
  } catch {
    // Ja notificado pelo interceptor; o texto fica no campo para tentar de novo.
  } finally {
    enviandoComentario.value = false
  }
}
</script>
