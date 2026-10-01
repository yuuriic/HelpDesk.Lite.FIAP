/**
 * Setup do Vitest (nao e um arquivo de teste -- o glob so pega *.spec.js).
 *
 * O jsdom nao implementa `window.matchMedia`, e o modo `auto` do Dark do Quasar depende
 * dele para acompanhar o `prefers-color-scheme` do sistema. Sem este stub, qualquer
 * chamada a `Dark.set('auto')` explode no ambiente de teste -- no navegador funciona.
 */
/**
 * O jsdom tambem nao tem `ResizeObserver`, que o QLayout usa para medir o cabecalho. Os testes
 * que montam uma pagina dentro de um layout (ChamadoDetalhePage.spec) precisam dele.
 */
if (typeof window.ResizeObserver !== 'function') {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

if (typeof window.matchMedia !== 'function') {
  window.matchMedia = (consulta) => ({
    matches: false,
    media: consulta,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })
}
