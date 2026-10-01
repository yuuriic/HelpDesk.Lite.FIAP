import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { h, reactive } from 'vue'
import { Notify, QLayout, QPageContainer, Quasar } from 'quasar'

/**
 * Pagina de detalhe com o ChamadoAcoes de verdade: prova que o clique duplo nao dispara a acao
 * duas vezes (FE-02) e que uma acao que falha nao vira "unhandled rejection" (EXC-03).
 *
 * Substitui os testes [DEFEITO CONHECIDO FE-02], que montavam o componente sozinho: o defeito
 * estava na conversa pai <-> filho, entao a correcao so pode ser provada com os dois.
 *
 * Axios mockado (store, services e api.js rodam de verdade); o PATCH devolve uma promessa que
 * o teste resolve quando quiser -- e a "requisicao em voo".
 */

const chamadas = []
let respostaDoPatch = null

vi.mock('axios', () => {
  const registrar = (metodo, resposta) => vi.fn((url, ...resto) => {
    chamadas.push({ metodo, url, corpo: metodo === 'get' ? undefined : resto[0] })
    return resposta()
  })
  const instancia = {
    defaults: { headers: {} },
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
    get: registrar('get', () => Promise.resolve({ data: chamado('ABERTO') })),
    patch: registrar('patch', () => respostaDoPatch()),
    post: registrar('post', () => Promise.resolve({ data: {} }))
  }
  return { default: { create: () => instancia } }
})

vi.mock('vue-router', () => ({ useRoute: () => reactive({ params: { id: '10' } }) }))

const BRUNO = { id: 2, nome: 'Bruno', perfil: 'ATENDENTE' }

function chamado(status, extras = {}) {
  return {
    id: 10,
    titulo: 'Notebook nao liga',
    descricao: 'A tela fica preta.',
    status,
    prioridade: 'ALTA',
    categoria: { id: 1, nome: 'TI', slaHoras: 4, ativa: true },
    solicitante: { id: 1, nome: 'Ana', perfil: 'SOLICITANTE' },
    atendente: null,
    criadoEm: '2026-09-21T10:00:00Z',
    atualizadoEm: '2026-09-21T10:00:00Z',
    prazoSla: '2026-09-21T14:00:00Z',
    foraDoSla: false,
    comentarios: [],
    ...extras
  }
}

/** Uma requisicao que so termina quando o teste mandar. */
function requisicaoEmVoo() {
  let concluir, falhar
  const promessa = new Promise((resolve, reject) => { concluir = resolve; falhar = reject })
  return { promessa, concluir, falhar }
}

async function montarPagina() {
  localStorage.setItem('helpdesk.usuario', JSON.stringify(BRUNO))
  const pinia = createPinia()
  setActivePinia(pinia)
  const { default: ChamadoDetalhePage } = await import('src/pages/ChamadoDetalhePage.vue')
  // Como no app: a pagina vive dentro de um layout (o QPage exige um QLayout acima dele).
  const NoLayout = { render: () => h(QLayout, null, () => h(QPageContainer, null, () => h(ChamadoDetalhePage))) }
  const wrapper = mount(NoLayout, {
    global: {
      plugins: [[Quasar, { plugins: { Notify } }], pinia],
      stubs: { 'router-link': true }
    }
  })
  await flushPromises()
  return wrapper
}

const botao = (wrapper, chave) => wrapper.find(`[data-teste="acao-${chave}"]`)
const patches = () => chamadas.filter((c) => c.metodo === 'patch')

describe('ChamadoDetalhePage -- acoes do chamado', () => {
  beforeEach(() => {
    chamadas.length = 0
    localStorage.clear()
  })

  it('clique duplo em Assumir faz uma unica requisicao e trava os botoes ate a resposta', async () => {
    const voo = requisicaoEmVoo()
    respostaDoPatch = () => voo.promessa
    const wrapper = await montarPagina()

    await botao(wrapper, 'assumir').trigger('click')
    await botao(wrapper, 'assumir').trigger('click')

    expect(patches()).toHaveLength(1)
    expect(patches()[0].url).toBe('/chamados/10/assumir')
    expect(botao(wrapper, 'assumir').classes()).toContain('disabled')

    voo.concluir({ data: chamado('EM_ATENDIMENTO', { atendente: BRUNO }) })
    await flushPromises()

    // Assumido: some o Assumir, aparece o Resolver -- e ja destravado.
    expect(botao(wrapper, 'assumir').exists()).toBe(false)
    expect(botao(wrapper, 'resolver').exists()).toBe(true)
    expect(botao(wrapper, 'resolver').classes()).not.toContain('disabled')
  })

  it('se a acao falha, os botoes destravam e o erro nao escapa do handler', async () => {
    const voo = requisicaoEmVoo()
    respostaDoPatch = () => voo.promessa
    const wrapper = await montarPagina()

    await botao(wrapper, 'assumir').trigger('click')
    voo.falhar(new Error('HTTP 409'))
    await flushPromises()

    // O Vitest reprova o teste se a rejeicao ficar sem tratamento.
    expect(botao(wrapper, 'assumir').classes()).not.toContain('disabled')

    respostaDoPatch = () => Promise.resolve({ data: chamado('EM_ATENDIMENTO', { atendente: BRUNO }) })
    await botao(wrapper, 'assumir').trigger('click')
    await flushPromises()
    expect(patches()).toHaveLength(2)
  })
})
