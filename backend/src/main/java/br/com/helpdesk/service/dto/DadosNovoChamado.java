package br.com.helpdesk.service.dto;

import br.com.helpdesk.domain.enums.Prioridade;

/**
 * Entrada do caso de uso "abrir chamado" (ARQ-01).
 *
 * <p>Os services recebem estes records, nunca os {@code *Request} da camada web: o request
 * carrega o que e da interface HTTP (Bean Validation, Swagger) e se converte para ca no
 * controller. Assim o contrato da API pode mudar sem tocar no caso de uso, e o caso de uso pode
 * ser chamado por outro adaptador.</p>
 */
public record DadosNovoChamado(String titulo, String descricao, Long categoriaId, Prioridade prioridade) {
}
