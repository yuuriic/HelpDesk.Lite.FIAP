import { useAuthStore } from 'stores/auth'
import { definirAoPerderASessao } from 'src/services/api'

/**
 * Mantem a sessao do frontend coerente com o backend.
 *
 * - EXC-03: um 401 com alguem logado (usuario desativado, por exemplo) encerra a sessao e
 *   volta para a tela de login, guardando a rota para depois do novo login.
 * - SEG-08: ao abrir o app, a sessao guardada no localStorage e conferida com o backend; se o
 *   perfil mudou e a rota atual nao e mais permitida, aplica a mesma regra do guard do router
 *   (router/index.js) e manda para /chamados.
 *
 * Nada de fluxo novo: usa o login e a tela de login que ja existem. A conferencia nao trava a
 * abertura do app -- sem backend, a sessao continua como estava e o interceptor ja avisou.
 *
 * Uma funcao exportada basta (sem o wrapper defineBoot), como no boot/tema.js: assim o modulo
 * continua importavel pelo Vitest.
 */
export default function ({ router, store }) {
  const auth = useAuthStore(store)

  definirAoPerderASessao(() => {
    const atual = router.currentRoute.value
    auth.sair()
    if (atual.name !== 'login') {
      router.push({ name: 'login', query: { destino: atual.fullPath } })
    }
  })

  return auth.revalidarSessao()
    .then(() => {
      const perfisDaRota = router.currentRoute.value.meta?.perfis
      if (auth.autenticado && Array.isArray(perfisDaRota) && !perfisDaRota.includes(auth.perfil)) {
        router.replace({ name: 'chamados' })
      }
    })
    .catch(() => {
      // Sem backend ou erro ja notificado pelo interceptor: a sessao fica como estava.
    })
}
