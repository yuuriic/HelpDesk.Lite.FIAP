import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { Quasar } from 'quasar'
import ChamadoAcoes from 'components/ChamadoAcoes.vue'

/**
 * Estado "processando" do ChamadoAcoes (FE-02).
 *
 * Quem sabe se a acao ainda esta em voo e o pai, que faz a requisicao: ele passa a prop
 * `processando`. O clique duplo de ponta a ponta (pagina + componente) e provado em
 * ChamadoDetalhePage.spec.js. Aqui fica o contrato do componente.
 *
 * Substitui os dois testes [DEFEITO CONHECIDO FE-02], que fixavam o botao destravado durante a
 * requisicao e o clique duplo emitindo duas vezes.
 */

const ATENDENTE = 'ATENDENTE'
const BRUNO = { id: 2, nome: 'Bruno', perfil: ATENDENTE }

function montar(status = 'ABERTO', processando = false) {
  return mount(ChamadoAcoes, {
    props: {
      chamado: { id: 10, status, solicitante: { id: 1 }, atendente: status === 'ABERTO' ? null : BRUNO },
      perfil: ATENDENTE,
      usuarioId: BRUNO.id,
      processando
    },
    global: { plugins: [Quasar] }
  })
}

const botao = (wrapper, chave) => wrapper.find(`[data-teste="acao-${chave}"]`)

describe('ChamadoAcoes -- processando', () => {
  it('sem processamento os botoes ficam livres e o clique emite', async () => {
    const wrapper = montar()

    expect(botao(wrapper, 'assumir').classes()).not.toContain('disabled')
    await botao(wrapper, 'assumir').trigger('click')

    expect(wrapper.emitted('assumir')).toHaveLength(1)
  })

  it('enquanto o pai processa, os botoes ficam travados e o clique nao emite', async () => {
    const wrapper = montar('ABERTO', true)

    expect(botao(wrapper, 'assumir').classes()).toContain('disabled')
    expect(botao(wrapper, 'assumir').attributes('aria-disabled')).toBe('true')
    await botao(wrapper, 'assumir').trigger('click')

    expect(wrapper.emitted('assumir')).toBeUndefined()
  })

  it('o loading aparece no botao clicado e some quando o pai termina', async () => {
    const wrapper = montar('ABERTO')

    await botao(wrapper, 'assumir').trigger('click')
    await wrapper.setProps({ processando: true })
    // O q-btn em loading troca o conteudo por um spinner.
    expect(botao(wrapper, 'assumir').find('.q-spinner').exists()).toBe(true)

    await wrapper.setProps({ processando: false })
    expect(botao(wrapper, 'assumir').find('.q-spinner').exists()).toBe(false)
    expect(botao(wrapper, 'assumir').classes()).not.toContain('disabled')
  })

  it('alterar status emite o status de destino da aresta', async () => {
    const wrapper = montar('EM_ATENDIMENTO')

    await botao(wrapper, 'resolver').trigger('click')

    expect(wrapper.emitted('alterar-status')).toEqual([['RESOLVIDO']])
  })
})
