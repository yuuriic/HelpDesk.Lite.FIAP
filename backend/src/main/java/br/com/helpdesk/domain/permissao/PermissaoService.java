package br.com.helpdesk.domain.permissao;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.AcessoNegadoException;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.state.TransicaoStatus;
import lombok.RequiredArgsConstructor;

/**
 * A tabela de permissoes da secao 3 do documento, traduzida em codigo (ADR-004).
 *
 * <p>Classe pura: nao acessa banco, nao conhece HTTP. Recebe o usuario por parametro
 * (ADR-013) e pode ser instanciada com {@code new} num teste.</p>
 *
 * <p><b>Responde "quem pode", nunca "pode agora"</b> (ADR-015). O status atual do chamado
 * e assunto da {@link TransicaoStatus}, que roda depois e devolve 422. Por isso um
 * atendente que tenta agir no chamado de outro recebe 403 e nao descobre em que estado
 * aquele chamado esta.</p>
 *
 * <p>As regras perguntam a <b>capacidade</b> do perfil ({@link Perfil#enxergaTodosOsChamados()},
 * {@link Perfil#atendeChamados()}, {@link Perfil#gerenciaCadastros()}) em vez de comparar
 * identidade (DOM-03). A unica regra que depende do perfil <i>e</i> da relacao com o chamado
 * -- alterar status "so nos seus" -- e um {@code switch} exaustivo: um perfil novo nao compila
 * ate alguem decidir qual relacao ele exige.</p>
 */
@RequiredArgsConstructor
public class PermissaoService {

    private final TransicaoStatus transicaoStatus;

    /** Abrir chamado: todos os perfis. Existe para deixar a regra explicita. */
    public void verificarAbertura(Usuario usuario) {
        // Solicitante, atendente e admin podem abrir chamado. Nada a negar.
    }

    /**
     * Ver o chamado: quem nao enxerga a base inteira so ve os proprios.
     *
     * <p>Na listagem a mesma pergunta ({@link Perfil#enxergaTodosOsChamados()}) entra na query,
     * pela ChamadoSpecification (ARQ-03).</p>
     */
    public void verificarVisualizacao(Usuario usuario, Chamado chamado) {
        if (!podeVer(usuario, chamado)) {
            throw new AcessoNegadoException("Voce so pode acessar os chamados que abriu");
        }
    }

    /** Assumir: so quem atende chamados (atendente e admin). */
    public void verificarAssuncao(Usuario usuario) {
        if (!usuario.getPerfil().atendeChamados()) {
            throw new AcessoNegadoException("Apenas atendentes podem assumir chamados");
        }
    }

    /**
     * Alterar status: cruza o perfil, o status-alvo e a relacao com o chamado.
     *
     * <ul>
     *   <li>ADMIN: qualquer alvo alcancavel, sem restricao de relacao.</li>
     *   <li>ATENDENTE: so alvos que o perfil dele alcanca, e so em chamado que ele atende.</li>
     *   <li>SOLICITANTE: so alvos que o perfil dele alcanca, e so em chamado que ele abriu.</li>
     * </ul>
     *
     * <p>Voltar para ABERTO nao e alcancavel por ninguem, entao sempre cai em 403.</p>
     */
    public void verificarAlteracaoStatus(Usuario usuario, Chamado chamado, StatusChamado novoStatus) {
        Perfil perfil = usuario.getPerfil();

        if (!transicaoStatus.perfisQueLevamA(novoStatus).contains(perfil)) {
            throw new AcessoNegadoException(
                    "O perfil %s nao pode marcar um chamado como %s".formatted(perfil, novoStatus));
        }

        switch (perfil) {
            case ADMIN -> {
                // Sem restricao de relacao com o chamado.
            }
            case ATENDENTE -> {
                if (!chamado.eAtendidoPor(usuario.getId())) {
                    throw new AcessoNegadoException("Voce so pode alterar o status dos chamados que atende");
                }
            }
            case SOLICITANTE -> {
                if (!chamado.foiSolicitadoPor(usuario.getId())) {
                    throw new AcessoNegadoException("Voce so pode alterar o status dos chamados que abriu");
                }
            }
        }
    }

    /** Comentar segue a mesma visibilidade da leitura do chamado (ARQ-03). */
    public void verificarComentario(Usuario usuario, Chamado chamado) {
        if (!podeVer(usuario, chamado)) {
            throw new AcessoNegadoException("Voce so pode comentar nos chamados que abriu");
        }
    }

    public void verificarGestaoCategorias(Usuario usuario) {
        exigirGestaoDeCadastros(usuario, "gerenciar categorias");
    }

    public void verificarGestaoUsuarios(Usuario usuario) {
        exigirGestaoDeCadastros(usuario, "gerenciar usuarios");
    }

    /** Dashboard: indicadores da base inteira, entao so para quem enxerga a base inteira. */
    public void verificarDashboard(Usuario usuario) {
        if (!usuario.getPerfil().enxergaTodosOsChamados()) {
            throw new AcessoNegadoException("Apenas atendentes e administradores veem o dashboard");
        }
    }

    /** Regra unica de visibilidade do detalhe e dos comentarios. */
    private static boolean podeVer(Usuario usuario, Chamado chamado) {
        return usuario.getPerfil().enxergaTodosOsChamados() || chamado.foiSolicitadoPor(usuario.getId());
    }

    private void exigirGestaoDeCadastros(Usuario usuario, String acao) {
        if (!usuario.getPerfil().gerenciaCadastros()) {
            throw new AcessoNegadoException("Apenas administradores podem " + acao);
        }
    }
}
