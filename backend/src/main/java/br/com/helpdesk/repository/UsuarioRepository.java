package br.com.helpdesk.repository;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.model.Usuario;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    List<Usuario> findAllByAtivoTrueOrderByNomeAsc();

    List<Usuario> findAllByOrderByNomeAsc();

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    boolean existsByEmailIgnoreCase(String email);

    /** Base da regra "sempre existe ao menos um admin ativo" (REG-01). */
    long countByPerfilAndAtivoTrue(Perfil perfil);

    /**
     * Trava ({@code SELECT ... FOR UPDATE}) os usuarios ativos do perfil, em ordem de id (N-01).
     *
     * <p>Quem rebaixa ou desativa um admin trava todos os admins ativos antes de conta-los: um
     * segundo rebaixamento concorrente espera o primeiro confirmar e so entao conta. A ordem de
     * id e fixa para duas transacoes nunca travarem as mesmas linhas em ordem inversa.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Usuario> findComTravaByPerfilAndAtivoTrueOrderByIdAsc(Perfil perfil);

    /**
     * O usuario como esta gravado, com a linha travada ate o fim da transacao (N-01): quem o
     * altera e quem o torna atendente de um chamado passam por esta trava, um de cada vez.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Usuario> findComTravaById(Long id);
}
