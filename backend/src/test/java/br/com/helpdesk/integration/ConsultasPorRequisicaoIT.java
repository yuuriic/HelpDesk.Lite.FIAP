package br.com.helpdesk.integration;

import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Comentario;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.ComentarioRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.ChamadoBuilder;
import br.com.helpdesk.support.UsuarioBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quantidade de comandos SQL por requisicao (Fase 4 do plano).
 *
 * <p>Substitui o teste de defeito CON-03 de DefeitosConhecidosIT (removida na Fase 9, com o ultimo defeito tratado) (8 consultas para
 * listar 3 chamados) e cobre o CON-06 (autor de comentario carregado um a um) e o REG-05
 * (consulta feita so para o log). O que importa e que a contagem <b>nao cresce com os dados</b>:
 * cada cenario roda com poucos e com muitos registros relacionados distintos.</p>
 *
 * <p>Como medir, igual para todos os cenarios:</p>
 * <ul>
 *   <li>o contexto de persistencia e esvaziado antes da requisicao, como numa requisicao real;</li>
 *   <li>quem faz a requisicao nao participa de nenhum chamado -- senao o usuario carregado pelo
 *       filter ja estaria em memoria e esconderia uma consulta;</li>
 *   <li>a classe e {@code @Transactional}, entao nada e confirmado: um {@code flush} depois da
 *       requisicao manda os UPDATEs para o banco e eles entram na conta.</li>
 * </ul>
 * <p>A conta inclui sempre 1 consulta do UsuarioAtualFilter (usuario do {@code X-User-Id}).</p>
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Consultas SQL por requisicao (integracao)")
class ConsultasPorRequisicaoIT {

    private static final String HEADER_USUARIO = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private CategoriaRepository categoriaRepository;
    @Autowired
    private ChamadoRepository chamadoRepository;
    @Autowired
    private ComentarioRepository comentarioRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @PersistenceContext
    private EntityManager entityManager;

    private Usuario admin;
    private Usuario atendenteDaFila;
    private int sequencia;

    @BeforeEach
    void criarQuemConsulta() {
        admin = usuarioRepository.save(UsuarioBuilder.admin().comEmail("admin@consultas.com").buildSemId());
        atendenteDaFila = usuarioRepository.save(UsuarioBuilder.atendente().comEmail("fila@consultas.com").buildSemId());
    }

    // ------------------------------------------------------------------ apoio

    private Usuario novoUsuario(UsuarioBuilder builder) {
        sequencia++;
        return usuarioRepository.save(builder.comEmail("u" + sequencia + "@consultas.com").buildSemId());
    }

    /**
     * {@code quantidade} chamados EM_ATENDIMENTO, cada um com categoria, solicitante e atendente
     * proprios: o pior caso para o N+1, com 3 entidades relacionadas distintas por chamado.
     */
    private List<Chamado> chamadosComRelacionamentosDistintos(int quantidade) {
        List<Chamado> criados = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            Categoria categoria = categoriaRepository.save(
                    CategoriaBuilder.comSla(8).comNome("Consultas - Cat " + (++sequencia)).buildSemId());
            criados.add(chamadoRepository.save(ChamadoBuilder.emAtendimento()
                    .comCategoria(categoria)
                    .comSolicitante(novoUsuario(UsuarioBuilder.solicitante()))
                    .comAtendente(novoUsuario(UsuarioBuilder.atendente()))
                    .criadoEm(Instant.now())
                    .buildSemId()));
        }
        return criados;
    }

    /** Chamado com {@code autores} comentarios, cada um de um autor diferente do solicitante e do atendente. */
    private Chamado chamadoComComentariosDeAutoresDistintos(int autores) {
        Chamado chamado = chamadosComRelacionamentosDistintos(1).get(0);
        for (int i = 0; i < autores; i++) {
            comentarioRepository.save(Comentario.builder()
                    .chamado(chamado)
                    .autor(novoUsuario(UsuarioBuilder.atendente()))
                    .texto("Comentario " + i)
                    .criadoEm(Instant.now())
                    .build());
        }
        return chamado;
    }

    /** Executa a requisicao com o contexto limpo e devolve quantos comandos SQL ela disparou. */
    private long consultasDe(RequestBuilder requisicao, ResultActionsCheck verificacao) throws Exception {
        entityManager.flush();
        entityManager.clear();
        Statistics estatisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        estatisticas.clear();

        verificacao.verificar(mockMvc.perform(requisicao));
        entityManager.flush();

        return estatisticas.getPrepareStatementCount();
    }

    @FunctionalInterface
    private interface ResultActionsCheck {
        void verificar(ResultActions resposta) throws Exception;
    }

    // ================================================================== CON-03

    @Nested
    @DisplayName("listagem de chamados (CON-03)")
    class Listagem {

        @Test
        @DisplayName("o cenario do defeito conhecido: 3 chamados em 2 consultas (antes: 8)")
        void deveListarOCenarioDaAuditoriaEmDuasConsultas() throws Exception {
            chamadosComRelacionamentosDistintos(3);

            long consultas = consultasDe(get("/api/chamados").header(HEADER_USUARIO, admin.getId()),
                    r -> r.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3)));

            // 1 usuario do filter + 1 pagina com categoria, solicitante e atendente no join.
            assertThat(consultas).isEqualTo(2);
        }

        @Test
        @DisplayName("a contagem nao cresce com a pagina: 20 chamados com 60 relacionados distintos, 3 consultas")
        void naoDeveCrescerComOTamanhoDaPagina() throws Exception {
            chamadosComRelacionamentosDistintos(20);

            long consultas = consultasDe(get("/api/chamados").param("size", "20").header(HEADER_USUARIO, admin.getId()),
                    r -> r.andExpect(status().isOk())
                            .andExpect(jsonPath("$.content.length()").value(20))
                            .andExpect(jsonPath("$.content[0].categoria.nome").exists())
                            .andExpect(jsonPath("$.content[0].solicitante.nome").exists())
                            .andExpect(jsonPath("$.content[0].atendente.nome").exists()));

            // Pagina cheia (20 de 20): o Spring Data tambem roda o count para saber se ha proxima.
            // Agora: 1 usuario + 1 pagina com join + 1 count = 3.
            // Antes: 1 + 1 + 1 count + 20 categorias + 20 solicitantes + 20 atendentes = 63.
            assertThat(consultas).isEqualTo(3);
        }

        @Test
        @DisplayName("pagina cheia soma so a consulta de count, sem nova ida por relacionamento")
        void deveSomarSoOCountQuandoAPaginaVemCheia() throws Exception {
            chamadosComRelacionamentosDistintos(5);

            long consultas = consultasDe(get("/api/chamados").param("size", "2").param("page", "1")
                            .header(HEADER_USUARIO, admin.getId()),
                    r -> r.andExpect(status().isOk())
                            .andExpect(jsonPath("$.totalElements").value(5))
                            .andExpect(jsonPath("$.totalPages").value(3))
                            .andExpect(jsonPath("$.content.length()").value(2)));

            // 1 usuario + 1 pagina + 1 count.
            assertThat(consultas).isEqualTo(3);
        }

        @Test
        @DisplayName("visibilidade do solicitante continua no WHERE e custa as mesmas 2 consultas")
        void deveManterAVisibilidadeDoSolicitante() throws Exception {
            Chamado dele = chamadosComRelacionamentosDistintos(4).get(0);
            Usuario solicitante = dele.getSolicitante();

            long consultas = consultasDe(get("/api/chamados").header(HEADER_USUARIO, solicitante.getId()),
                    r -> r.andExpect(status().isOk())
                            .andExpect(jsonPath("$.totalElements").value(1))
                            .andExpect(jsonPath("$.content[0].id").value(dele.getId())));

            // O solicitante e o dono: ja vem no filter, mas o service tem outro contexto.
            assertThat(consultas).isEqualTo(2);
        }

        @Test
        @DisplayName("filtro e ordenacao por campo de associacao continuam em 2 consultas")
        void deveManterFiltrosEOrdenacao() throws Exception {
            chamadosComRelacionamentosDistintos(6);

            long consultas = consultasDe(get("/api/chamados")
                            .param("status", StatusChamado.EM_ATENDIMENTO.name())
                            .param("sort", "categoria.nome,desc")
                            .header(HEADER_USUARIO, admin.getId()),
                    r -> r.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6)));

            assertThat(consultas).isEqualTo(2);
        }

        @Test
        @DisplayName("a fila pessoal (meus=true) filtra no banco e tambem custa 2 consultas")
        void deveManterAFilaPessoal() throws Exception {
            chamadosComRelacionamentosDistintos(3);
            chamadoRepository.save(ChamadoBuilder.emAtendimento()
                    .comCategoria(categoriaRepository.save(CategoriaBuilder.comSla(4).comNome("Consultas - Fila").buildSemId()))
                    .comSolicitante(novoUsuario(UsuarioBuilder.solicitante()))
                    .comAtendente(atendenteDaFila)
                    .criadoEm(Instant.now())
                    .buildSemId());

            long consultas = consultasDe(get("/api/chamados").param("meus", "true")
                            .header(HEADER_USUARIO, atendenteDaFila.getId()),
                    r -> r.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)));

            assertThat(consultas).isEqualTo(2);
        }
    }

    // ================================================================== CON-06

    @Nested
    @DisplayName("comentarios com autor na mesma consulta (CON-06)")
    class Comentarios {

        @Test
        @DisplayName("detalhe: 3 consultas com 1 ou com 6 autores distintos (antes: 3 + 1 por autor)")
        void deveCarregarOsAutoresJuntoComOsComentarios() throws Exception {
            for (int autores : new int[]{1, 6}) {
                Chamado chamado = chamadoComComentariosDeAutoresDistintos(autores);

                long consultas = consultasDe(get("/api/chamados/{id}", chamado.getId()).header(HEADER_USUARIO, admin.getId()),
                        r -> r.andExpect(status().isOk())
                                .andExpect(jsonPath("$.comentarios.length()").value(autores))
                                .andExpect(jsonPath("$.comentarios[0].autor.nome").exists()));

                // 1 usuario + 1 chamado com as tres associacoes + 1 comentarios com autor.
                assertThat(consultas).as("%d autores", autores).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("GET /comentarios: 3 consultas, qualquer que seja o numero de autores")
        void deveListarOsComentariosSemConsultaPorAutor() throws Exception {
            Chamado chamado = chamadoComComentariosDeAutoresDistintos(5);

            long consultas = consultasDe(get("/api/chamados/{id}/comentarios", chamado.getId())
                            .header(HEADER_USUARIO, admin.getId()),
                    r -> r.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5)));

            assertThat(consultas).isEqualTo(3);
        }

        @Test
        @DisplayName("alterar status devolve o detalhe: 3 consultas + 1 UPDATE, com 5 autores distintos")
        void deveAlterarStatusSemConsultaPorAutor() throws Exception {
            Chamado chamado = chamadoComComentariosDeAutoresDistintos(5);
            Usuario atendente = chamado.getAtendente();

            long consultas = consultasDe(patch("/api/chamados/{id}/status", chamado.getId())
                            .header(HEADER_USUARIO, atendente.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVIDO\"}"),
                    r -> r.andExpect(status().isOk()).andExpect(jsonPath("$.comentarios.length()").value(5)));

            assertThat(consultas).isEqualTo(4);
        }
    }

    // ================================================================== REG-05 e dashboard

    @Test
    @DisplayName("desativar categoria: usuario + categoria + UPDATE, sem consultar chamados (REG-05; antes: 4)")
    void deveDesativarCategoriaSemConsultarChamados() throws Exception {
        Categoria categoria = chamadosComRelacionamentosDistintos(1).get(0).getCategoria();

        long consultas = consultasDe(delete("/api/categorias/{id}", categoria.getId()).header(HEADER_USUARIO, admin.getId()),
                r -> r.andExpect(status().isNoContent()));

        assertThat(consultas).isEqualTo(3);
    }

    /**
     * O dashboard ja trazia tudo numa consulta com join -- nao e N+1. O custo dele (CON-04) e de
     * memoria: le a base inteira. Fica fixado aqui para uma mudanca futura nao reintroduzir
     * consultas por chamado.
     */
    @Test
    @DisplayName("dashboard: 2 consultas com 10 chamados de relacionamentos distintos (inalterado)")
    void deveManterODashboardEmDuasConsultas() throws Exception {
        chamadosComRelacionamentosDistintos(10);

        long consultas = consultasDe(get("/api/dashboard/resumo").header(HEADER_USUARIO, admin.getId()),
                r -> r.andExpect(status().isOk()).andExpect(jsonPath("$.totalChamados").value(10)));

        assertThat(consultas).isEqualTo(2);
    }
}
