package br.com.helpdesk.repository;

import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.model.Chamado;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChamadoRepository extends JpaRepository<Chamado, Long>,
        JpaSpecificationExecutor<Chamado> {

    /**
     * Listagem paginada com filtros e visibilidade (ADR-007), agora com as tres associacoes
     * {@code @ManyToOne} na mesma consulta (CON-03).
     *
     * <p>Sem este override o metodo herdado de {@link JpaSpecificationExecutor} trazia so a
     * tabela chamado, e o mapper disparava um SELECT por categoria e por usuario distintos da
     * pagina -- ate 1 + 3 x size consultas. Como as tres sao para-um, o join nao multiplica
     * linhas: {@code offset}/{@code fetch first} continuam no SQL e a consulta de
     * {@code count} da paginacao nao e afetada pelo grafo.</p>
     */
    @Override
    @EntityGraph(attributePaths = {"categoria", "solicitante", "atendente"})
    Page<Chamado> findAll(Specification<Chamado> filtros, Pageable paginacao);

    /** Detalhe: traz categoria, solicitante e atendente numa consulta so. */
    @EntityGraph(attributePaths = {"categoria", "solicitante", "atendente"})
    Optional<Chamado> findWithRelacionamentosById(Long id);

    /** Base do dashboard: a agregacao de SLA precisa da categoria de cada chamado. */
    @EntityGraph(attributePaths = {"categoria", "solicitante", "atendente"})
    @Query("select c from Chamado c")
    List<Chamado> buscarTodosParaDashboard();

    /** O usuario ainda responde por algum chamado nesses status? Base da invariante I3 (REG-02). */
    boolean existsByAtendenteIdAndStatusIn(Long atendenteId, Collection<StatusChamado> status);
}
