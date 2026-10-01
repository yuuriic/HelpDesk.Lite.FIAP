import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'

/**
 * Caracterizacao do cliente HTTP (src/services/api.js): headers enviados e traducao do JSON
 * de erro em q-notify.
 *
 * Aqui o Axios e o de verdade -- so o adapter (a ida a rede) e trocado, para os
 * interceptors rodarem exatamente como em producao. O Notify do Quasar e espionado.
 *
 * O teste [DEFEITO CONHECIDO EXC-03] ("um 401 nao encerra a sessao") foi substituido pelo
 * grupo "401 com sessao" -- quem encerra a sessao e o boot/sessao.js, avisado por aqui.
 */

const notificacoes = []

vi.mock('quasar', async (original) => ({
  ...(await original()),
  Notify: { create: vi.fn((opcoes) => notificacoes.push(opcoes)) }
}))

const { api, definirUsuarioAtual, definirAoPerderASessao, HEADER_USUARIO, HEADER_REQUEST_ID } =
  await import('src/services/api')

const enviadas = []
let proxima = null

/** Proxima resposta do "servidor". status >= 400 vira erro, como faria o adapter real. */
function responder(status, data = {}, headers = {}) {
  proxima = { status, data, headers }
}

function falharNaRede() {
  proxima = { rede: true }
}

beforeEach(() => {
  enviadas.length = 0
  notificacoes.length = 0
  definirUsuarioAtual(null)
  responder(200)

  api.defaults.adapter = async (config) => {
    enviadas.push(config)
    if (proxima.rede) {
      throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config)
    }
    const resposta = { status: proxima.status, statusText: '', data: proxima.data, headers: proxima.headers, config }
    if (proxima.status >= 400) {
      throw new AxiosError('falha', AxiosError.ERR_BAD_REQUEST, config, null, resposta)
    }
    return resposta
  }
})

describe('api.js', () => {
  describe('headers da requisicao', () => {
    it('manda um X-Request-Id diferente em cada requisicao', async () => {
      await api.get('/chamados')
      await api.get('/chamados')

      const [primeiro, segundo] = enviadas.map((config) => config.headers[HEADER_REQUEST_ID])
      expect(primeiro).toBeTruthy()
      expect(segundo).toBeTruthy()
      expect(primeiro).not.toBe(segundo)
    })

    it('nao manda X-User-Id sem usuario logado', async () => {
      await api.get('/auth/usuarios-disponiveis')

      expect(enviadas[0].headers[HEADER_USUARIO]).toBeUndefined()
    })

    it('manda o id do usuario logado como texto no X-User-Id', async () => {
      definirUsuarioAtual(3)

      await api.get('/usuarios')

      expect(enviadas[0].headers[HEADER_USUARIO]).toBe('3')
    })

    it('usa a URL padrao do backend quando nao ha API_BASE_URL', async () => {
      await api.get('/chamados')

      expect(enviadas[0].baseURL).toBe('http://localhost:8080/api')
    })
  })

  describe('traducao de erro em notify', () => {
    it('mostra a mensagem do backend com status e o request id devolvido', async () => {
      responder(422, { status: 422, erro: 'TRANSICAO_INVALIDA', mensagem: 'Chamado ABERTO nao pode ir direto para RESOLVIDO' },
        { 'x-request-id': 'id-do-servidor' })

      await expect(api.patch('/chamados/1/status', { status: 'RESOLVIDO' })).rejects.toBeInstanceOf(AxiosError)

      expect(notificacoes).toHaveLength(1)
      expect(notificacoes[0]).toMatchObject({
        type: 'negative',
        message: 'Chamado ABERTO nao pode ir direto para RESOLVIDO',
        caption: 'HTTP 422 · request id: id-do-servidor'
      })
    })

    it('acrescenta a lista de campos no 400 de validacao', async () => {
      responder(400, {
        erro: 'VALIDACAO',
        mensagem: 'Requisicao invalida',
        campos: [{ campo: 'titulo', mensagem: 'nao deve estar em branco' }, { campo: 'prioridade', mensagem: 'e obrigatoria' }]
      })

      await expect(api.post('/chamados', {})).rejects.toBeTruthy()

      expect(notificacoes[0].message)
        .toBe('Requisicao invalida (titulo: nao deve estar em branco; prioridade: e obrigatoria)')
    })

    it('usa o tipo warning para 401 e 403, negative para o resto', async () => {
      for (const [status, tipo] of [[401, 'warning'], [403, 'warning'], [404, 'negative'], [500, 'negative']]) {
        responder(status, { mensagem: `erro ${status}` })
        await api.get('/qualquer').catch(() => {})
        expect(notificacoes.at(-1).type).toBe(tipo)
      }
    })

    it('cai numa mensagem generica quando o corpo de erro nao tem mensagem', async () => {
      responder(500, {})

      await api.get('/qualquer').catch(() => {})

      expect(notificacoes[0].message).toBe('Erro inesperado ao chamar a API')
    })

    it('em erro de rede avisa que a API nao respondeu e usa o request id enviado', async () => {
      falharNaRede()

      await expect(api.get('/chamados')).rejects.toBeTruthy()

      const idEnviado = enviadas[0].headers[HEADER_REQUEST_ID]
      expect(notificacoes[0].message).toContain('Nao foi possivel falar com o servidor')
      expect(notificacoes[0].caption).toBe(`request id: ${idEnviado}`)
    })

    it('sempre notifica e sempre rejeita: quem chama nao consegue silenciar o notify', async () => {
      responder(422, { mensagem: 'regra' })

      await expect(api.get('/qualquer', { silencioso: true })).rejects.toBeTruthy()

      expect(notificacoes).toHaveLength(1)
    })
  })

  describe('401 com sessao (EXC-03)', () => {
    const avisos = []

    beforeEach(() => {
      avisos.length = 0
      definirAoPerderASessao(() => avisos.push('sessao perdida'))
    })

    it('avisa que a sessao foi perdida quando alguem logado recebe 401', async () => {
      definirUsuarioAtual(7)
      responder(401, { erro: 'NAO_AUTENTICADO', mensagem: 'Usuario invalido ou inativo' })

      await api.get('/chamados').catch(() => {})

      expect(avisos).toEqual(['sessao perdida'])
      expect(notificacoes[0].type).toBe('warning') // o notify continua aparecendo
    })

    it('nao avisa num 401 sem ninguem logado -- um login recusado nao tem sessao a encerrar', async () => {
      responder(401, { erro: 'NAO_AUTENTICADO', mensagem: 'Usuario invalido ou inativo' })

      await api.post('/auth/login', { usuarioId: 999 }).catch(() => {})

      expect(avisos).toEqual([])
    })

    it('nao avisa em 403 nem em outros erros: a sessao continua valida', async () => {
      definirUsuarioAtual(2)
      for (const status of [403, 404, 422, 500]) {
        responder(status, { mensagem: `erro ${status}` })
        await api.get('/qualquer').catch(() => {})
      }

      expect(avisos).toEqual([])
    })
  })
})
