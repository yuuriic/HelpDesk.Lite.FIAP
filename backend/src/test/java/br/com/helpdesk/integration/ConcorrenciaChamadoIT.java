package br.com.helpdesk.integration;

import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.ChamadoService;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.ChamadoBuilder;
import br.com.helpdesk.support.UsuarioBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Controle de concorrencia na assuncao do chamado (CON-01, Fase 2 do plano).
 *
 * <p>Estrategia: <b>lock otimista</b> com {@code @Version} em {@code Chamado}. Quem grava com
 * uma versao desatualizada falha no commit ({@code ObjectOptimisticLockingFailureException}),
 * e a API responde 409 CONFLITO. Substitui o teste de defeito CON-01 de
 * DefeitosConhecidosIT (removida na Fase 9, com o ultimo defeito tratado), que provava que a segunda assuncao sobrescrevia a primeira.</p>
 *
 * <p>Sem {@code @Transactional} de proposito: as transacoes precisam ser confirmadas de verdade
 * para a concorrencia existir. Por isso o teste apaga no {@code @AfterEach} tudo o que criou --
 * o banco H2 e compartilhado com as outras suites.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Concorrencia na assuncao do chamado (integracao)")
class ConcorrenciaChamadoIT {

    private static final String HEADER_USUARIO = "X-User-Id";
    private static final int ATENDENTES_CONCORRENTES = 6;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChamadoService chamadoService;
    @Autowired
    private ChamadoRepository chamadoRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private CategoriaRepository categoriaRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Usuario> atendentes = new ArrayList<>();
    private Usuario ana;
    private Categoria categoria;
    private Chamado chamado;

    @BeforeEach
    void criarChamadoAberto() {
        ana = usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("ana@concorrencia.com").buildSemId());
        for (int i = 1; i <= ATENDENTES_CONCORRENTES; i++) {
            atendentes.add(usuarioRepository.save(UsuarioBuilder.atendente().comNome("Atendente " + i)
                    .comEmail("atendente" + i + "@concorrencia.com").buildSemId()));
        }
        categoria = categoriaRepository.save(CategoriaBuilder.comSla(4).comNome("Concorrencia - TI").buildSemId());
        chamado = chamadoRepository.save(ChamadoBuilder.aberto()
                .comCategoria(categoria).comSolicitante(ana).criadoEm(Instant.now()).buildSemId());
    }

    @AfterEach
    void apagarOQueFoiCriado() {
        chamadoRepository.deleteById(chamado.getId());
        categoriaRepository.deleteById(categoria.getId());
        atendentes.forEach(usuario -> usuarioRepository.deleteById(usuario.getId()));
        usuarioRepository.deleteById(ana.getId());
    }

    private Chamado recarregado() {
        return chamadoRepository.findWithRelacionamentosById(chamado.getId()).orElseThrow();
    }

    @Test
    @DisplayName("o chamado nasce na versao 0")
    void deveNascerNaVersaoZero() {
        assertThat(recarregado().getVersao()).isZero();
    }

    /**
     * Intercalacao deterministica de duas requisicoes pelo service real:
     * <ol>
     *   <li>a requisicao da Eva le o chamado em ABERTO (versao 0);</li>
     *   <li>no meio dela, a do Bruno assume e confirma numa transacao propria (versao 1);</li>
     *   <li>a da Eva continua com o que leu -- para ela o chamado ainda esta ABERTO, entao a
     *       TransicaoStatus deixa passar -- e tenta gravar sobre a versao 0.</li>
     * </ol>
     * Sem {@code @Version} a Eva sobrescreveria o Bruno sem erro (o defeito CON-01).
     */
    @Test
    @DisplayName("quem assume sobre uma leitura desatualizada falha com conflito de versao, e o primeiro vence")
    void deveRecusarAAssuncaoBaseadaEmLeituraDesatualizada() {
        Usuario bruno = atendentes.get(0);
        Usuario eva = atendentes.get(1);
        TransactionTemplate requisicaoDaEva = new TransactionTemplate(transactionManager);
        TransactionTemplate requisicaoDoBruno = new TransactionTemplate(transactionManager);
        requisicaoDoBruno.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> requisicaoDaEva.executeWithoutResult(transacao -> {
            assertThat(recarregado().getStatus()).isEqualTo(StatusChamado.ABERTO);

            requisicaoDoBruno.executeWithoutResult(outra -> chamadoService.assumir(chamado.getId(), bruno));

            chamadoService.assumir(chamado.getId(), eva);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Chamado gravado = recarregado();
        assertThat(gravado.getStatus()).isEqualTo(StatusChamado.EM_ATENDIMENTO);
        assertThat(gravado.getAtendente().getId()).as("a assuncao do Bruno nao se perde").isEqualTo(bruno.getId());
        assertThat(gravado.getVersao()).isEqualTo(1L);
    }

    /**
     * Concorrencia real pela API. A ordem das threads nao e controlada, mas o resultado tem de
     * ser o mesmo em qualquer ordem: um unico vencedor. Quem leu antes do commit do vencedor
     * leva 409 (versao); quem leu depois ja ve EM_ATENDIMENTO e leva 422 (transicao).
     */
    @Test
    @DisplayName("varios atendentes assumindo ao mesmo tempo: exatamente um vence, os outros levam 409 ou 422")
    void deveTerUmUnicoVencedorComAssuncoesSimultaneas() throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        Map<Long, Integer> statusPorAtendente = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(ATENDENTES_CONCORRENTES);
        try {
            List<Future<?>> tarefas = new ArrayList<>();
            for (Usuario atendente : atendentes) {
                tarefas.add(executor.submit(() -> {
                    largada.await();
                    MvcResult resultado = mockMvc.perform(patch("/api/chamados/{id}/assumir", chamado.getId())
                            .header(HEADER_USUARIO, atendente.getId())).andReturn();
                    statusPorAtendente.put(atendente.getId(), resultado.getResponse().getStatus());
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> tarefa : tarefas) {
                tarefa.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(statusPorAtendente).hasSize(ATENDENTES_CONCORRENTES);
        List<Long> vencedores = statusPorAtendente.entrySet().stream()
                .filter(entrada -> entrada.getValue() == 200).map(Map.Entry::getKey).toList();
        assertThat(vencedores).as("status por atendente: %s", statusPorAtendente).hasSize(1);
        assertThat(statusPorAtendente.values()).as("nenhum 500, nenhum segundo 200")
                .allMatch(status -> status == 200 || status == 409 || status == 422);

        Chamado gravado = recarregado();
        assertThat(gravado.getAtendente().getId()).isEqualTo(vencedores.get(0));
        assertThat(gravado.getVersao()).as("uma unica gravacao confirmada").isEqualTo(1L);
    }
}
