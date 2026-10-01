package br.com.helpdesk.domain;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.Prioridade;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.exception.TransicaoInvalidaException;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Comentario;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.state.TransicaoStatus;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.ChamadoBuilder;
import br.com.helpdesk.support.Fixtures;
import br.com.helpdesk.support.UsuarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O agregado Chamado (DOM-01, DOM-02): o estado so muda pelos metodos de intencao, que validam
 * na {@link TransicaoStatus} e aplicam os carimbos de tempo.
 *
 * <p>Sem Spring e sem mocks -- a {@code TransicaoStatus} e a de verdade, instanciada com
 * {@code new}. Os testes de service continuam cobrindo a orquestracao (ordem 403 -> 422,
 * persistencia, metricas).</p>
 */
@DisplayName("Chamado (agregado)")
class ChamadoTest {

    private static final Instant AGORA = Fixtures.AGORA;

    private final TransicaoStatus transicoes = new TransicaoStatus();
    private final Categoria categoria = CategoriaBuilder.comSla(4).comId(1L).build();
    private final Usuario ana = UsuarioBuilder.solicitante().comId(1L).build();
    private final Usuario bruno = UsuarioBuilder.atendente().comId(2L).build();
    private final Usuario carla = UsuarioBuilder.admin().comId(3L).build();

    @Nested
    @DisplayName("abrir")
    class Abrir {

        @Test
        @DisplayName("nasce em ABERTO, sem atendente, com criadoEm = atualizadoEm = agora")
        void deveNascerAberto() {
            Chamado chamado = Chamado.abrir("Notebook nao liga", "A tela fica preta.", Prioridade.ALTA,
                    categoria, ana, AGORA);

            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.ABERTO);
            assertThat(chamado.getAtendente()).isNull();
            assertThat(chamado.getSolicitante()).isEqualTo(ana);
            assertThat(chamado.getCategoria()).isEqualTo(categoria);
            assertThat(chamado.getPrioridade()).isEqualTo(Prioridade.ALTA);
            assertThat(chamado.getCriadoEm()).isEqualTo(AGORA);
            assertThat(chamado.getAtualizadoEm()).isEqualTo(AGORA);
            assertThat(chamado.getResolvidoEm()).isNull();
            assertThat(chamado.getFechadoEm()).isNull();
        }

        @Test
        @DisplayName("categoria inativa e recusada pelo proprio agregado (regra I8)")
        void deveRecusarCategoriaInativa() {
            Categoria inativa = CategoriaBuilder.comSla(4).comId(9L).comNome("RH").inativa().build();

            assertThatThrownBy(() -> Chamado.abrir("Titulo valido", "Descricao valida.", Prioridade.BAIXA,
                    inativa, ana, AGORA))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("RH");
        }
    }

    @Nested
    @DisplayName("assumir")
    class Assumir {

        @Test
        @DisplayName("define o atendente, vai para EM_ATENDIMENTO e carimba atualizadoEm")
        void deveAssumirChamadoAberto() {
            Chamado chamado = ChamadoBuilder.aberto().criadoEm(Fixtures.horasAtras(2)).build();

            chamado.assumir(bruno, transicoes, AGORA);

            assertThat(chamado.getAtendente()).isEqualTo(bruno);
            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.EM_ATENDIMENTO);
            assertThat(chamado.getAtualizadoEm()).isEqualTo(AGORA);
            assertThat(chamado.getCriadoEm()).isEqualTo(Fixtures.horasAtras(2));
        }

        @Test
        @DisplayName("o admin tambem assume")
        void deveDeixarOAdminAssumir() {
            Chamado chamado = ChamadoBuilder.aberto().build();

            chamado.assumir(carla, transicoes, AGORA);

            assertThat(chamado.getAtendente()).isEqualTo(carla);
        }

        @Test
        @DisplayName("chamado ja em atendimento: 422 e nada muda -- nem o atendente")
        void deveRecusarAssumirChamadoJaAssumido() {
            Chamado chamado = ChamadoBuilder.emAtendimento().criadoEm(Fixtures.horasAtras(2)).build();
            Usuario atendenteAtual = chamado.getAtendente();
            Usuario eva = UsuarioBuilder.atendente().comId(8L).build();

            assertThatThrownBy(() -> chamado.assumir(eva, transicoes, AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class);

            assertThat(chamado.getAtendente()).isEqualTo(atendenteAtual);
            assertThat(chamado.getAtualizadoEm()).isEqualTo(Fixtures.horasAtras(2));
        }

        @Test
        @DisplayName("solicitante nao executa a aresta de assumir (422 da maquina de estados)")
        void deveRecusarSolicitanteComoAtendente() {
            Chamado chamado = ChamadoBuilder.aberto().build();

            assertThatThrownBy(() -> chamado.assumir(ana, transicoes, AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class);
            assertThat(chamado.getAtendente()).isNull();
            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.ABERTO);
        }
    }

    @Nested
    @DisplayName("alterar status")
    class AlterarStatus {

        @Test
        @DisplayName("resolver carimba resolvidoEm e atualizadoEm")
        void deveResolver() {
            Chamado chamado = ChamadoBuilder.emAtendimento().criadoEm(Fixtures.horasAtras(3)).build();

            chamado.alterarStatus(StatusChamado.RESOLVIDO, Perfil.ATENDENTE, transicoes, AGORA);

            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.RESOLVIDO);
            assertThat(chamado.getResolvidoEm()).isEqualTo(AGORA);
            assertThat(chamado.getAtualizadoEm()).isEqualTo(AGORA);
            assertThat(chamado.tempoAteResolucao()).isEqualTo(Duration.ofHours(3));
        }

        @Test
        @DisplayName("fechar carimba fechadoEm e mantem resolvidoEm")
        void deveFecharMantendoAResolucao() {
            Chamado chamado = ChamadoBuilder.resolvido().build();
            Instant resolvidoEm = chamado.getResolvidoEm();

            chamado.alterarStatus(StatusChamado.FECHADO, Perfil.SOLICITANTE, transicoes, AGORA);

            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.FECHADO);
            assertThat(chamado.getFechadoEm()).isEqualTo(AGORA);
            assertThat(chamado.getResolvidoEm()).isEqualTo(resolvidoEm);
        }

        @Test
        @DisplayName("reabrir zera resolvidoEm e fechadoEm e mantem o atendente")
        void deveReabrirMantendoOAtendente() {
            Chamado chamado = ChamadoBuilder.resolvido().build();
            Usuario atendente = chamado.getAtendente();

            chamado.alterarStatus(StatusChamado.EM_ATENDIMENTO, Perfil.SOLICITANTE, transicoes, AGORA);

            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.EM_ATENDIMENTO);
            assertThat(chamado.getResolvidoEm()).isNull();
            assertThat(chamado.getFechadoEm()).isNull();
            assertThat(chamado.getAtendente()).isEqualTo(atendente);
        }

        @Test
        @DisplayName("transicao inexistente lanca e nao altera status nem carimbos")
        void deveRecusarTransicaoInexistenteSemAlterarNada() {
            Chamado chamado = ChamadoBuilder.aberto().criadoEm(Fixtures.horasAtras(2)).build();

            assertThatThrownBy(() -> chamado.alterarStatus(StatusChamado.RESOLVIDO, Perfil.ADMIN, transicoes, AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class);

            assertThat(chamado.getStatus()).isEqualTo(StatusChamado.ABERTO);
            assertThat(chamado.getResolvidoEm()).isNull();
            assertThat(chamado.getAtualizadoEm()).isEqualTo(Fixtures.horasAtras(2));
        }

        @Test
        @DisplayName("perfil errado na aresta certa tambem lanca: o atendente nao fecha")
        void deveRecusarPerfilQueNaoExecutaAAresta() {
            Chamado chamado = ChamadoBuilder.resolvido().build();

            assertThatThrownBy(() -> chamado.alterarStatus(StatusChamado.FECHADO, Perfil.ATENDENTE, transicoes, AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class);
            assertThat(chamado.getFechadoEm()).isNull();
        }

        @Test
        @DisplayName("FECHADO e final: nenhuma alteracao sai dele")
        void deveTratarFechadoComoFinal() {
            Chamado chamado = ChamadoBuilder.fechado().build();

            for (StatusChamado destino : StatusChamado.values()) {
                assertThatThrownBy(() -> chamado.alterarStatus(destino, Perfil.ADMIN, transicoes, AGORA))
                        .as("FECHADO -> %s", destino)
                        .isInstanceOf(TransicaoInvalidaException.class);
            }
        }

        @Test
        @DisplayName("tempo ate a resolucao nao existe para chamado nao resolvido")
        void naoDeveCalcularTempoDeResolucaoSemResolucao() {
            assertThatThrownBy(() -> ChamadoBuilder.emAtendimento().build().tempoAteResolucao())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("comentario")
    class NovoComentario {

        @Test
        @DisplayName("registrar cria o comentario com autor, texto e instante")
        void deveRegistrarComentario() {
            Chamado chamado = ChamadoBuilder.emAtendimento().build();

            Comentario comentario = Comentario.registrar(chamado, bruno, "Ja estou olhando.", AGORA);

            assertThat(comentario.getChamado()).isEqualTo(chamado);
            assertThat(comentario.getAutor()).isEqualTo(bruno);
            assertThat(comentario.getTexto()).isEqualTo("Ja estou olhando.");
            assertThat(comentario.getCriadoEm()).isEqualTo(AGORA);
        }
    }

    /**
     * Guarda estrutural do DOM-01 e da regra I4: se alguem voltar a por {@code @Setter} (ou um
     * setter a mao) no agregado, este teste falha antes de qualquer regra ser burlada.
     */
    @Test
    @DisplayName("Chamado e Comentario nao expoem setters publicos")
    void naoDeveExporSetters() {
        for (Class<?> tipo : new Class<?>[]{Chamado.class, Comentario.class}) {
            assertThat(Arrays.stream(tipo.getDeclaredMethods())
                    .filter(metodo -> Modifier.isPublic(metodo.getModifiers()))
                    .map(Method::getName)
                    .filter(nome -> nome.startsWith("set")))
                    .as("setters publicos em %s", tipo.getSimpleName())
                    .isEmpty();
        }
    }
}
