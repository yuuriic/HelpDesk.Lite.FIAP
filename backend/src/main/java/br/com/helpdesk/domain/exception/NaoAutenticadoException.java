package br.com.helpdesk.domain.exception;

/**
 * O usuario informado nao pode entrar: nao existe ou esta inativo (regra I9).
 * Vira HTTP 401 com codigo NAO_AUTENTICADO.
 *
 * <p>A mensagem e a mesma nos dois casos de proposito: responder "nao encontrado" para um
 * id e "inativo" para outro permitiria descobrir quais ids existem.</p>
 */
public class NaoAutenticadoException extends RuntimeException {

    /** Mensagem publica unica, usada tambem pelo UsuarioAtualFilter. */
    public static final String MENSAGEM_PADRAO = "Usuario invalido ou inativo";

    public NaoAutenticadoException() {
        super(MENSAGEM_PADRAO);
    }
}
