import { date } from 'quasar'

/**
 * Instante da API (ISO-8601 em UTC) no fuso do navegador -- na demonstracao,
 * America/Sao_Paulo. Sem instante, devolve '-'. Antes, cada tela tinha a sua copia (FE-03).
 */
export function formatarDataHora(instante) {
  return instante ? date.formatDate(new Date(instante), 'DD/MM/YYYY HH:mm') : '-'
}
