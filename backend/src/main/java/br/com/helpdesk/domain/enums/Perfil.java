package br.com.helpdesk.domain.enums;

/**
 * Perfis de acesso do sistema.
 *
 * <p>Cada perfil responde a perguntas de <b>capacidade</b> (DOM-03): quem pergunta nao
 * compara identidade ({@code perfil == ADMIN}), pergunta o que o perfil pode fazer. Um perfil
 * novo declara as proprias respostas aqui, num lugar so.</p>
 *
 * <p>A tabela de permissoes que combina capacidade com a relacao usuario-chamado vive em
 * PermissaoService; as transicoes de status, em TransicaoStatus.</p>
 */
public enum Perfil {

    /** Abre chamados e acompanha apenas os proprios. */
    SOLICITANTE(false, false, false),

    /** Assume e resolve chamados. Enxerga a base inteira. */
    ATENDENTE(true, true, false),

    /** Faz tudo, mais os cadastros de usuario e categoria. */
    ADMIN(true, true, true);

    private final boolean enxergaTodosOsChamados;
    private final boolean atendeChamados;
    private final boolean gerenciaCadastros;

    Perfil(boolean enxergaTodosOsChamados, boolean atendeChamados, boolean gerenciaCadastros) {
        this.enxergaTodosOsChamados = enxergaTodosOsChamados;
        this.atendeChamados = atendeChamados;
        this.gerenciaCadastros = gerenciaCadastros;
    }

    /**
     * Ve (e comenta) qualquer chamado, e ve os indicadores do dashboard. Quem nao enxerga tudo
     * fica restrito aos chamados que abriu -- na query e no detalhe.
     */
    public boolean enxergaTodosOsChamados() {
        return enxergaTodosOsChamados;
    }

    /** Pode assumir chamados e ser o atendente de um chamado (regra I3). */
    public boolean atendeChamados() {
        return atendeChamados;
    }

    /** Gerencia os cadastros de usuarios e categorias. */
    public boolean gerenciaCadastros() {
        return gerenciaCadastros;
    }
}
