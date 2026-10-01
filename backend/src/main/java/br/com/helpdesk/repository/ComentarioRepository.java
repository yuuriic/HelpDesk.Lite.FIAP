package br.com.helpdesk.repository;

import br.com.helpdesk.domain.model.Comentario;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComentarioRepository extends JpaRepository<Comentario, Long> {

    /**
     * Linha do tempo do chamado, com o autor de cada comentario na mesma consulta (CON-06):
     * sem o grafo, cada autor que nao fosse o solicitante nem o atendente -- ja carregados com o
     * chamado -- custava um SELECT a mais.
     */
    @EntityGraph(attributePaths = "autor")
    List<Comentario> findAllByChamadoIdOrderByCriadoEmAsc(Long chamadoId);
}
