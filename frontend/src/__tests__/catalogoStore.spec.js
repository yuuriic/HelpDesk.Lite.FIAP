import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * Caracterizacao do catalogoStore: rotas chamadas e recarga depois de cada escrita.
 *
 * Duas listas de categoria (FE-04): `categorias` (so ativas, formulario e filtro) e
 * `todasCategorias` (administracao). O teste [DEFEITO CONHECIDO FE-04] que fixava a lista unica
 * foi substituido por "salvar no admin nao mexe na lista do formulario".
 */

// Axios mockado, no mesmo estilo do chamadosStore.spec: store, services e api.js reais.
const chamadas = []
const respostas = {}

vi.mock('axios', () => {
  const registrar = (metodo) => vi.fn((url, ...resto) => {
    const config = metodo === 'get' ? resto[0] : undefined
    const corpo = metodo === 'get' || metodo === 'delete' ? undefined : resto[0]
    chamadas.push({ metodo, url, params: config?.params, corpo })
    const resposta = typeof respostas[metodo] === 'function' ? respostas[metodo](url, config) : respostas[metodo]
    return Promise.resolve({ data: resposta ?? {} })
  })

  const instancia = {
    defaults: { headers: {} },
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
    get: registrar('get'),
    post: registrar('post'),
    put: registrar('put'),
    delete: registrar('delete')
  }
  return { default: { create: () => instancia } }
})

const { useCatalogoStore } = await import('src/stores/catalogo')

const TI = { id: 1, nome: 'TI', slaHoras: 4, ativa: true }
const RH_INATIVA = { id: 2, nome: 'RH', slaHoras: 48, ativa: false }

/** Simula o backend: sem incluirInativas so as ativas; com ele, todas. */
function categoriasDoBackend(url, config) {
  return config?.params?.incluirInativas ? [TI, RH_INATIVA] : [TI]
}

describe('catalogoStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    chamadas.length = 0
    Object.keys(respostas).forEach((chave) => delete respostas[chave])
    respostas.get = categoriasDoBackend
  })

  describe('categorias', () => {
    it('carrega so as ativas por padrao, sem mandar incluirInativas', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.carregarCategorias()

      expect(chamadas.at(-1)).toMatchObject({ metodo: 'get', url: '/categorias', params: { incluirInativas: undefined } })
      expect(catalogo.categorias).toEqual([TI])
      expect(catalogo.carregandoCategorias).toBe(false)
    })

    it('manda incluirInativas=true para a tela de administracao', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.carregarTodasCategorias()

      expect(chamadas.at(-1).params).toEqual({ incluirInativas: true })
      expect(catalogo.todasCategorias).toEqual([TI, RH_INATIVA])
      expect(catalogo.categorias).toEqual([])
    })

    it('cria com POST quando nao ha id e recarrega a lista completa', async () => {
      respostas.post = { id: 3, nome: 'Facilities', slaHoras: 24, ativa: true }
      const catalogo = useCatalogoStore()

      const salva = await catalogo.salvarCategoria({ id: null, nome: 'Facilities', slaHoras: 24 })

      expect(salva.id).toBe(3)
      expect(chamadas.map((c) => `${c.metodo} ${c.url}`)).toEqual(['post /categorias', 'get /categorias'])
      expect(chamadas[1].params).toEqual({ incluirInativas: true })
    })

    it('atualiza com PUT quando ha id', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.salvarCategoria({ id: 1, nome: 'TI', slaHoras: 6 })

      expect(chamadas[0]).toMatchObject({ metodo: 'put', url: '/categorias/1', corpo: { id: 1, nome: 'TI', slaHoras: 6 } })
    })

    it('desativa com DELETE e recarrega a lista completa', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.desativarCategoria(1)

      expect(chamadas.map((c) => `${c.metodo} ${c.url}`)).toEqual(['delete /categorias/1', 'get /categorias'])
      expect(chamadas[1].params).toEqual({ incluirInativas: true })
    })

    it('salvar ou desativar no admin nao poe categorias inativas na lista do formulario de chamado', async () => {
      const catalogo = useCatalogoStore()
      await catalogo.carregarCategorias() // o que NovoChamadoPage faz ao montar
      expect(catalogo.categorias).toEqual([TI])

      await catalogo.salvarCategoria({ id: 1, nome: 'TI', slaHoras: 6 }) // o que CategoriasPage faz
      await catalogo.desativarCategoria(2)

      expect(catalogo.categorias).toEqual([TI])
      expect(catalogo.todasCategorias).toEqual([TI, RH_INATIVA])
    })
  })

  describe('usuarios', () => {
    beforeEach(() => {
      respostas.get = [{ id: 1, nome: 'Ana', email: 'ana@empresa.com', perfil: 'SOLICITANTE', ativo: true }]
    })

    it('carrega a lista administrativa', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.carregarUsuarios()

      expect(chamadas.at(-1)).toMatchObject({ metodo: 'get', url: '/usuarios' })
      expect(catalogo.usuarios).toHaveLength(1)
    })

    it('cria com POST, atualiza com PUT e recarrega a lista depois de cada um', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.salvarUsuario({ id: null, nome: 'Davi', email: 'davi@empresa.com', perfil: 'ATENDENTE' })
      await catalogo.salvarUsuario({ id: 1, nome: 'Ana', email: 'ana@empresa.com', perfil: 'ADMIN' })

      expect(chamadas.map((c) => `${c.metodo} ${c.url}`))
        .toEqual(['post /usuarios', 'get /usuarios', 'put /usuarios/1', 'get /usuarios'])
    })

    it('carregar usuarios nao liga o carregamento de categorias, e vice-versa', async () => {
      const catalogo = useCatalogoStore()

      const usuarios = catalogo.carregarUsuarios()
      expect(catalogo.carregandoUsuarios).toBe(true)
      expect(catalogo.carregandoCategorias).toBe(false)
      await usuarios

      expect(catalogo.carregandoUsuarios).toBe(false)
    })

    it('desativa com DELETE e recarrega', async () => {
      const catalogo = useCatalogoStore()

      await catalogo.desativarUsuario(1)

      expect(chamadas.map((c) => `${c.metodo} ${c.url}`)).toEqual(['delete /usuarios/1', 'get /usuarios'])
    })
  })
})
