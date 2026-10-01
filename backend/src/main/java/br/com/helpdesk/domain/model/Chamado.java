package br.com.helpdesk.domain.model;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.Prioridade;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.state.TransicaoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Chamado: o agregado central do sistema.
 *
 * <p><b>O estado so muda pelos metodos de intencao</b> (DOM-01): {@link #abrir},
 * {@link #assumir} e {@link #alterarStatus}. Nao ha setters -- nenhum codigo de fora consegue
 * por o chamado em FECHADO sem passar pela maquina de estados, nem esquecer um carimbo de
 * tempo. Cada metodo valida a transicao na {@link TransicaoStatus} (a unica tabela de
 * transicoes do sistema) e so entao aplica os efeitos dela (DOM-02).</p>
 *
 * <p>O que continua fora da entidade, de proposito: <i>quem</i> pode agir neste chamado
 * (PermissaoService, 403, chamado pelo service <b>antes</b> destes metodos -- ADR-015), a
 * persistencia, as metricas e o log de auditoria.</p>
 *
 * <p>O {@code @Builder} existe so para reconstituir chamados ja existentes (fixtures de teste);
 * chamado novo nasce por {@link #abrir}.</p>
 *
 * <p>Todas as associacoes sao LAZY; as consultas de detalhe usam {@code @EntityGraph}.
 * Indices em status, solicitante e atendente cobrem os filtros da listagem.</p>
 *
 * <p>Lock otimista por {@link #versao} (CON-01): duas requisicoes que leram o mesmo chamado
 * nao gravam uma por cima da outra. A segunda falha no commit com
 * {@code ObjectOptimisticLockingFailureException}, que a API devolve como 409 CONFLITO.</p>
 */
@Entity
@Table(name = "chamado", indexes = {
        @Index(name = "idx_chamado_status", columnList = "status"),
        @Index(name = "idx_chamado_solicitante", columnList = "solicitante_id"),
        @Index(name = "idx_chamado_atendente", columnList = "atendente_id")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Chamado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String titulo;

    @Column(nullable = false, length = 2000)
    private String descricao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusChamado status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Prioridade prioridade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categoria_id", nullable = false)
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solicitante_id", nullable = false)
    private Usuario solicitante;

    /** Nulo ate o chamado ser assumido. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atendente_id")
    private Usuario atendente;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    /** Preenchido ao entrar em RESOLVIDO; zerado ao reabrir. */
    @Column(name = "resolvido_em")
    private Instant resolvidoEm;

    @Column(name = "fechado_em")
    private Instant fechadoEm;

    /**
     * Versao do lock otimista. So o Hibernate a altera. O default 0 na coluna cobre as linhas
     * inseridas sem ela (o {@code data.sql} e dados ja existentes).
     */
    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long versao;

    // ------------------------------------------------------------------ ciclo de vida

    /**
     * Abre um chamado: nasce em ABERTO, sem atendente, com os dois carimbos iguais.
     *
     * @throws RegraNegocioException se a categoria estiver inativa (regra I8)
     */
    public static Chamado abrir(String titulo, String descricao, Prioridade prioridade,
                                Categoria categoria, Usuario solicitante, Instant agora) {
        if (!categoria.isAtiva()) {
            throw new RegraNegocioException("A categoria %s esta inativa".formatted(categoria.getNome()));
        }
        Chamado chamado = new Chamado();
        chamado.titulo = titulo;
        chamado.descricao = descricao;
        chamado.prioridade = prioridade;
        chamado.categoria = categoria;
        chamado.solicitante = solicitante;
        chamado.status = StatusChamado.ABERTO;
        chamado.criadoEm = agora;
        chamado.atualizadoEm = agora;
        return chamado;
    }

    /**
     * O atendente vira responsavel e o chamado vai para EM_ATENDIMENTO.
     *
     * <p>Assumir um chamado que ja nao esta em ABERTO lanca 422: a aresta
     * {@code EM_ATENDIMENTO -> EM_ATENDIMENTO} nao existe na maquina de estados.</p>
     *
     * @throws br.com.helpdesk.domain.exception.TransicaoInvalidaException se a transicao nao vale agora
     */
    public void assumir(Usuario novoAtendente, TransicaoStatus transicoes, Instant agora) {
        transicoes.validar(status, StatusChamado.EM_ATENDIMENTO, novoAtendente.getPerfil());
        this.atendente = novoAtendente;
        mudarPara(StatusChamado.EM_ATENDIMENTO, agora);
    }

    /**
     * Leva o chamado a {@code novoStatus}, com os efeitos daquela transicao.
     *
     * @param perfil perfil de quem executa -- a tabela diz quais perfis percorrem cada aresta
     * @throws br.com.helpdesk.domain.exception.TransicaoInvalidaException se a transicao nao vale agora
     */
    public void alterarStatus(StatusChamado novoStatus, Perfil perfil, TransicaoStatus transicoes, Instant agora) {
        transicoes.validar(status, novoStatus, perfil);
        aplicarEfeitosDaTransicao(novoStatus, agora);
        mudarPara(novoStatus, agora);
    }

    /** Tempo entre a abertura e a resolucao. So faz sentido com o chamado ja resolvido. */
    public Duration tempoAteResolucao() {
        if (resolvidoEm == null) {
            throw new IllegalStateException("Chamado %s ainda nao foi resolvido".formatted(id));
        }
        return Duration.between(criadoEm, resolvidoEm);
    }

    /**
     * Carimbos de tempo de cada transicao.
     *
     * <p>Reabrir zera resolvidoEm e fechadoEm: o chamado voltou a estar em aberto, e
     * conta-lo como resolvido distorceria o tempo medio e o calculo de SLA. O atendente e
     * mantido, para o chamado voltar para quem ja o conhece.</p>
     */
    private void aplicarEfeitosDaTransicao(StatusChamado novoStatus, Instant agora) {
        switch (novoStatus) {
            case RESOLVIDO -> resolvidoEm = agora;
            case FECHADO -> fechadoEm = agora;
            case EM_ATENDIMENTO -> {
                resolvidoEm = null;
                fechadoEm = null;
            }
            case ABERTO -> {
                // Inalcancavel: nenhuma aresta leva de volta para ABERTO (a validacao lanca antes).
            }
        }
    }

    private void mudarPara(StatusChamado novoStatus, Instant agora) {
        this.status = novoStatus;
        this.atualizadoEm = agora;
    }

    // ------------------------------------------------------------------ consultas

    public boolean eAtendidoPor(Long usuarioId) {
        return atendente != null && Objects.equals(atendente.getId(), usuarioId);
    }

    public boolean foiSolicitadoPor(Long usuarioId) {
        return solicitante != null && Objects.equals(solicitante.getId(), usuarioId);
    }

    @Override
    public boolean equals(Object outro) {
        if (this == outro) {
            return true;
        }
        return outro instanceof Chamado chamado && id != null && Objects.equals(id, chamado.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
