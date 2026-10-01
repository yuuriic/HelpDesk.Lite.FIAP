package br.com.helpdesk.domain.exception;

/**
 * A transicao pedida nao existe na maquina de estados, ou o perfil nao a executa.
 * Vira HTTP 422 com codigo TRANSICAO_INVALIDA.
 *
 * <p>Os status de origem e destino ja vao na mensagem. Os campos {@code de}/{@code para}
 * que a classe carregava nunca foram lidos fora dos testes e sairam na limpeza (DOM-05).</p>
 */
public class TransicaoInvalidaException extends RuntimeException {

    public TransicaoInvalidaException(String mensagem) {
        super(mensagem);
    }
}
