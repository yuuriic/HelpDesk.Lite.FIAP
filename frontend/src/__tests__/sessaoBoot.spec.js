import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { ref } from 'vue'

/**
 * boot/sessao.js: liga o "401 com sessao" do api.js ao encerramento da sessao (EXC-03) e
 * confere a sessao guardada ao abrir o app (SEG-08).
 *
 * O api.js e substituido por um modulo falso que guarda o callback registrado e responde o
 * login; o authStore e o de verdade. O router e um objeto minimo com a rota atual.
 */

const estado = { aoPerderASessao: null, respostaDoLogin: null }

vi.mock('src/services/api', () => {
  const api = {
    get: vi.fn(() => Promise.resolve({ data: [] })),
    post: vi.fn(() => estado.respostaDoLogin())
  }
  return {
    default: api,
    api,
    definirUsuarioAtual: vi.fn(),
    definirAoPerderASessao: vi.fn((callback) => { estado.aoPerderASessao = callback })
  }
})

const { default: iniciarSessao } = await import('src/boot/sessao')
const { useAuthStore } = await import('src/stores/auth')

const CARLA = { id: 3, nome: 'Carla', perfil: 'ADMIN' }

function routerEm(nome, fullPath, perfis) {
  return {
    currentRoute: ref({ name: nome, fullPath, meta: perfis ? { perfis } : {} }),
    push: vi.fn(),
    replace: vi.fn()
  }
}

async function iniciar(router, sessao = CARLA) {
  if (sessao) {
    localStorage.setItem('helpdesk.usuario', JSON.stringify(sessao))
  }
  const store = createPinia()
  await iniciarSessao({ router, store })
  return useAuthStore(store)
}

describe('boot/sessao', () => {
  beforeEach(() => {
    localStorage.clear()
    estado.aoPerderASessao = null
    estado.respostaDoLogin = () => Promise.resolve({ data: CARLA })
  })

  describe('401 com sessao (EXC-03)', () => {
    it('encerra a sessao e volta ao login guardando a rota atual', async () => {
      const router = routerEm('usuarios', '/usuarios', ['ADMIN'])
      const auth = await iniciar(router)
      expect(auth.autenticado).toBe(true)

      estado.aoPerderASessao()

      expect(auth.autenticado).toBe(false)
      expect(localStorage.getItem('helpdesk.usuario')).toBeNull()
      expect(router.push).toHaveBeenCalledWith({ name: 'login', query: { destino: '/usuarios' } })
    })

    it('ja na tela de login, so encerra a sessao, sem redirecionar de novo', async () => {
      const router = routerEm('login', '/login')
      const auth = await iniciar(router)

      estado.aoPerderASessao()

      expect(auth.autenticado).toBe(false)
      expect(router.push).not.toHaveBeenCalled()
    })
  })

  describe('conferir a sessao ao abrir o app (SEG-08)', () => {
    it('perfil rebaixado no backend: sai da rota que o perfil novo nao pode ver', async () => {
      estado.respostaDoLogin = () => Promise.resolve({ data: { ...CARLA, perfil: 'SOLICITANTE' } })
      const router = routerEm('usuarios', '/usuarios', ['ADMIN'])

      const auth = await iniciar(router)

      expect(auth.perfil).toBe('SOLICITANTE')
      expect(router.replace).toHaveBeenCalledWith({ name: 'chamados' })
    })

    it('perfil igual: continua onde esta', async () => {
      const router = routerEm('usuarios', '/usuarios', ['ADMIN'])

      await iniciar(router)

      expect(router.replace).not.toHaveBeenCalled()
    })

    it('sem backend: a sessao fica como estava e o app abre normalmente', async () => {
      estado.respostaDoLogin = () => Promise.reject(new Error('Network Error'))
      const router = routerEm('chamados', '/chamados')

      const auth = await iniciar(router)

      expect(auth.autenticado).toBe(true)
      expect(auth.perfil).toBe('ADMIN')
      expect(router.replace).not.toHaveBeenCalled()
    })

    it('sem sessao guardada nao chama o backend', async () => {
      const router = routerEm('login', '/login')
      const { default: api } = await import('src/services/api')
      api.post.mockClear()

      await iniciar(router, null)

      expect(api.post).not.toHaveBeenCalled()
    })
  })
})
