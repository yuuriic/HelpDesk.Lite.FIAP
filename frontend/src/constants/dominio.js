/**
 * Rotulos, cores e icones dos enums do dominio, num lugar so (FE-03).
 *
 * Antes cada tela tinha a sua copia -- e o perfil ADMIN aparecia como "Admin" no login e na lista
 * de usuarios, e como "Administrador" no cabecalho e no formulario. A ordem das chaves e a ordem
 * das opcoes nos selects.
 */

export const PERFIS = Object.freeze({
  SOLICITANTE: { rotulo: 'Solicitante', cor: 'blue-7', icone: 'person' },
  ATENDENTE: { rotulo: 'Atendente', cor: 'teal-7', icone: 'support_agent' },
  ADMIN: { rotulo: 'Administrador', cor: 'purple-6', icone: 'support_agent' }
})

export const STATUS = Object.freeze({
  ABERTO: { rotulo: 'Aberto', cor: 'blue-7' },
  EM_ATENDIMENTO: { rotulo: 'Em atendimento', cor: 'orange-8' },
  RESOLVIDO: { rotulo: 'Resolvido', cor: 'teal-7' },
  FECHADO: { rotulo: 'Fechado', cor: 'grey-7' }
})

export const PRIORIDADES = Object.freeze({
  BAIXA: { rotulo: 'Baixa', cor: 'green-7', icone: 'south' },
  MEDIA: { rotulo: 'Media', cor: 'amber-8', icone: 'drag_handle' },
  ALTA: { rotulo: 'Alta', cor: 'red-7', icone: 'north' }
})

/** `[{ label, value }]` no formato dos q-select, na ordem das chaves. */
export function opcoesDe(enumeracao) {
  return Object.entries(enumeracao).map(([valor, { rotulo }]) => ({ label: rotulo, value: valor }))
}
