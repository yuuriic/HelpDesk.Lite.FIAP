package br.com.helpdesk.integration;

import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.ComentarioRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.ChamadoService;
import br.com.helpdesk.service.MetricasNegocio;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.ChamadoBuilder;
import br.com.helpdesk.support.UsuarioBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Metricas de negocio e log de auditoria (Fase 8 do plano).
 *
 * <ul>
 *   <li><b>OBS-02</b> -- contadores, timer e linha de auditoria so contam o que foi
 *       <b>confirmado</b>: a operacao que falha no commit (o perdedor de uma assuncao
 *       concorrente, 409) nao aparece em nenhum deles;</li>
 *   <li><b>formato da auditoria</b> (secao 4.11) -- antes em ChamadoContratoApiIT, que roda numa
 *       transacao de teste que nunca e confirmada; com a auditoria emitida depois do commit, o
 *       teste precisa de commits de verdade;</li>
 *   <li><b>OBS-01 reclassificado</b> -- os gauges so mudam na leitura do dashboard por decisao
 *       da ADR-018; antes era um [DEFEITO CONHECIDO] em DefeitosConhecidosIT.</li>
 * </ul>
 *
 * <p>Sem {@code @Transactional}: as requisicoes confirmam de verdade. O {@code @AfterEach} apaga
 * o que o teste criou -- o banco H2 e compartilhado com as outras suites. Os contadores sao
 * globais ao contexto, entao cada teste compara o antes e o depois.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Metricas e auditoria (integracao, com commit real)")
class ObservabilidadeIT {

    private static final String HEADER_USUARIO = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private ChamadoService chamadoService;
    @Autowired
    private ChamadoRepository chamadoRepository;
    @Autowired
    private ComentarioRepository comentarioRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private CategoriaRepository categoriaRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> chamadosCriados = new ArrayList<>();
    private Usuario ana;
    private Usuario bruno;
    private Usuario eva;
    private Categoria categoria;

    @BeforeEach
    void criarCenario() {
        ana = usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("ana@obs.com").buildSemId());
        bruno = usuarioRepository.save(UsuarioBuilder.atendente().comEmail("bruno@obs.com").buildSemId());
        eva = usuarioRepository.save(UsuarioBuilder.atendente().comNome("Eva").comEmail("eva@obs.com").buildSemId());
        categoria = categoriaRepository.save(CategoriaBuilder.comSla(4).comNome("Obs - TI").buildSemId());
    }

    @AfterEach
    void apagarOQueFoiCriado() {
        // Comentarios antes dos chamados (FK); chamados antes dos usuarios e da categoria.
        chamadosCriados.forEach(id -> comentarioRepository.deleteAll(comentarioRepository.findAllByChamadoIdOrderByCriadoEmAsc(id)));
        chamadosCriados.forEach(chamadoRepository::deleteById);
        categoriaRepository.deleteById(categoria.getId());
        List.of(ana, bruno, eva).forEach(usuario -> usuarioRepository.deleteById(usuario.getId()));
    }

    // ------------------------------------------------------------------ apoio

    private double transicoes(String de, String para) {
        Counter contador = registry.find(MetricasNegocio.CHAMADOS_TRANSICOES)
                .tags("de", de, "para", para, "perfil", "ATENDENTE").counter();
        return contador == null ? 0 : contador.count();
    }

    private double abertos() {
        Counter contador = registry.find(MetricasNegocio.CHAMADOS_ABERTOS)
                .tags("categoria", categoria.getNome(), "prioridade", "MEDIA").counter();
        return contador == null ? 0 : contador.count();
    }

    private long resolucoes() {
        Timer timer = registry.find(MetricasNegocio.TEMPO_RESOLUCAO).tags("categoria", categoria.getNome()).timer();
        return timer == null ? 0 : timer.count();
    }

    private static long ocorrencias(String texto, String trecho) {
        return Pattern.compile(Pattern.quote(trecho)).matcher(texto).results().count();
    }

    private long abrirPelaApi() throws Exception {
        String corpo = mockMvc.perform(post("/api/chamados").header(HEADER_USUARIO, ana.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Impressora travando\",\"descricao\":\"A impressora trava ao imprimir.\","
                                + "\"categoriaId\":" + categoria.getId() + ",\"prioridade\":\"MEDIA\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(corpo).get("id").asLong();
        chamadosCriados.add(id);
        return id;
    }

    // ================================================================== OBS-02 + formato

    @Nested
    @DisplayName("operacoes confirmadas: cada uma conta uma vez, no formato da secao 4.11")
    class Confirmadas {

        @Test
        @DisplayName("abrir, assumir e resolver contam uma vez cada e geram as linhas de auditoria")
        void deveContarEAuditarCadaOperacaoConfirmada(CapturedOutput saida) throws Exception {
            double abertosAntes = abertos();
            double assumidosAntes = transicoes("ABERTO", "EM_ATENDIMENTO");
            double resolvidosAntes = transicoes("EM_ATENDIMENTO", "RESOLVIDO");
            long resolucoesAntes = resolucoes();

            long id = abrirPelaApi();
            mockMvc.perform(patch("/api/chamados/{id}/assumir", id).header(HEADER_USUARIO, bruno.getId()))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/chamados/{id}/status", id).header(HEADER_USUARIO, bruno.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVIDO\"}"))
                    .andExpect(status().isOk());

            assertThat(abertos() - abertosAntes).isEqualTo(1);
            assertThat(transicoes("ABERTO", "EM_ATENDIMENTO") - assumidosAntes).isEqualTo(1);
            assertThat(transicoes("EM_ATENDIMENTO", "RESOLVIDO") - resolvidosAntes).isEqualTo(1);
            assertThat(resolucoes() - resolucoesAntes).isEqualTo(1);

            // Formato da linha de auditoria (secao 4.11), com usr= vindo do MDC.
            assertThat(saida.getOut())
                    .contains("acao=ABRIR_CHAMADO chamadoId=" + id + " perfil=SOLICITANTE statusDe=null statusPara=ABERTO")
                    .contains("acao=ASSUMIR_CHAMADO chamadoId=" + id
                            + " perfil=ATENDENTE statusDe=ABERTO statusPara=EM_ATENDIMENTO")
                    .contains("acao=ALTERAR_STATUS chamadoId=" + id
                            + " perfil=ATENDENTE statusDe=EM_ATENDIMENTO statusPara=RESOLVIDO")
                    .contains("usr=" + bruno.getId() + "]");
        }

        @Test
        @DisplayName("comentar gera a linha de auditoria do comentario")
        void deveAuditarOComentario(CapturedOutput saida) throws Exception {
            long id = abrirPelaApi();

            mockMvc.perform(post("/api/chamados/{id}/comentarios", id).header(HEADER_USUARIO, ana.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"Segue o print.\"}"))
                    .andExpect(status().isCreated());

            assertThat(saida.getOut()).containsPattern("acao=COMENTAR chamadoId=" + id + " comentarioId=\\d+ perfil=SOLICITANTE");
        }
    }

    // ================================================================== OBS-02: o que nao foi confirmado

    /**
     * A mesma intercalacao deterministica do ConcorrenciaChamadoIT: a Eva le o chamado em ABERTO,
     * o Bruno assume e confirma no meio, e a Eva tenta gravar sobre a leitura antiga -- falha no
     * commit (409 na API).
     */
    @Test
    @DisplayName("a assuncao que falha no commit nao conta transicao nem gera auditoria -- so a vencedora")
    void naoDeveContarNemAuditarAAssuncaoQuePerdeNoCommit(CapturedOutput saida) {
        Chamado chamado = chamadoRepository.save(ChamadoBuilder.aberto()
                .comCategoria(categoria).comSolicitante(ana).criadoEm(Instant.now()).buildSemId());
        chamadosCriados.add(chamado.getId());
        double antes = transicoes("ABERTO", "EM_ATENDIMENTO");

        TransactionTemplate requisicaoDaEva = new TransactionTemplate(transactionManager);
        TransactionTemplate requisicaoDoBruno = new TransactionTemplate(transactionManager);
        requisicaoDoBruno.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> requisicaoDaEva.executeWithoutResult(transacao -> {
            chamadoRepository.findWithRelacionamentosById(chamado.getId()).orElseThrow();
            requisicaoDoBruno.executeWithoutResult(outra -> chamadoService.assumir(chamado.getId(), bruno));
            chamadoService.assumir(chamado.getId(), eva);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(transicoes("ABERTO", "EM_ATENDIMENTO") - antes).as("so a assuncao confirmada conta").isEqualTo(1);
        assertThat(ocorrencias(saida.getOut(), "acao=ASSUMIR_CHAMADO chamadoId=" + chamado.getId() + " "))
                .as("so a assuncao confirmada e auditada").isEqualTo(1);
    }

    // ================================================================== OBS-01 (ADR-018)

    /**
     * OBS-01 reclassificado: era [DEFEITO CONHECIDO] em DefeitosConhecidosIT. Os gauges de negocio
     * sao recalculados na leitura do dashboard por decisao registrada (ADR-018): nenhum agendador
     * e nenhuma consulta disparada pelo scrape; o custo aceito no MVP e o gauge ficar no valor da
     * ultima leitura. Este teste fixa essa decisao como contrato.
     */
    @Test
    @DisplayName("ADR-018: o gauge por status muda na leitura do dashboard, nao na abertura do chamado")
    void deveAtualizarOGaugeNaLeituraDoDashboard() throws Exception {
        double antes = registry.get(MetricasNegocio.CHAMADOS_POR_STATUS).tag("status", "ABERTO").gauge().value();

        abrirPelaApi();
        abrirPelaApi();
        assertThat(registry.get(MetricasNegocio.CHAMADOS_POR_STATUS).tag("status", "ABERTO").gauge().value())
                .as("abrir chamado nao mexe no gauge").isEqualTo(antes);

        mockMvc.perform(get("/api/dashboard/resumo").header(HEADER_USUARIO, bruno.getId()))
                .andExpect(status().isOk());

        assertThat(registry.get(MetricasNegocio.CHAMADOS_POR_STATUS).tag("status", "ABERTO").gauge().value())
                .as("a leitura do dashboard republica o valor do banco").isEqualTo(2.0);
    }
}
