package br.com.helpdesk.integration;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.AcessoNegadoException;
import br.com.helpdesk.domain.exception.NaoAutenticadoException;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.ChamadoService;
import br.com.helpdesk.service.UsuarioService;
import br.com.helpdesk.service.dto.DadosUsuario;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.ChamadoBuilder;
import br.com.helpdesk.support.UsuarioBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As guardas de REG-01 (I11, ao menos um admin ativo) e REG-02 (I3, atendente com chamados em
 * andamento) sob requisicoes concorrentes -- o achado N-01 da auditoria final.
 *
 * <p><b>A causa.</b> As duas guardas sao "verifica e depois grava" sobre linhas diferentes: o
 * admin A rebaixa o B enquanto o B rebaixa o A; cada transacao le a contagem antes da outra
 * gravar, as duas passam, e o sistema fica sem admin. E <i>write skew</i>: nenhuma das duas
 * escreve na linha que a outra escreve, entao nem um {@code @Version} as faria conflitar.</p>
 *
 * <p><b>Intercalacao deterministica.</b> Cada teste roda duas "requisicoes" -- chamadas ao
 * service real, cada uma na sua transacao e na sua thread -- sempre na mesma ordem:</p>
 * <ol>
 *   <li>a primeira executa o caso de uso inteiro e para <b>antes do commit</b>;</li>
 *   <li>a segunda roda ate terminar, ou ate o H2 registrar que ela esta esperando um lock da
 *       primeira ({@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID});</li>
 *   <li>so entao a primeira confirma.</li>
 * </ol>
 * <p>Nao ha {@code sleep} nem dependencia de tempo: sem a correcao, a segunda nunca espera e
 * confirma sobre uma leitura que a primeira vai invalidar; com a correcao, ela sempre espera e
 * le o estado ja confirmado.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Concorrencia nas invariantes de usuario (integracao, N-01)")
class ConcorrenciaUsuarioIT {

    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private ChamadoService chamadoService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ChamadoRepository chamadoRepository;
    @Autowired
    private CategoriaRepository categoriaRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Usuario> criados = new ArrayList<>();
    private Usuario carla;
    private Usuario diego;
    private Usuario bruno;
    private Categoria categoria;
    private Chamado chamado;

    @BeforeEach
    void criarCenario() {
        assertThat(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN))
                .as("pre-condicao: nenhum outro admin ativo na base de teste").isZero();

        carla = criar(UsuarioBuilder.admin().comNome("Carla").comEmail("carla@n01.com"));
        diego = criar(UsuarioBuilder.admin().comNome("Diego").comEmail("diego@n01.com"));
        bruno = criar(UsuarioBuilder.atendente().comNome("Bruno").comEmail("bruno@n01.com"));
        Usuario ana = criar(UsuarioBuilder.solicitante().comNome("Ana").comEmail("ana@n01.com"));
        categoria = categoriaRepository.save(CategoriaBuilder.comSla(8).comNome("N-01 - TI").buildSemId());
        chamado = chamadoRepository.save(ChamadoBuilder.aberto()
                .comCategoria(categoria).comSolicitante(ana).criadoEm(Instant.now()).buildSemId());
    }

    @AfterEach
    void apagarCenario() {
        chamadoRepository.deleteById(chamado.getId());
        categoriaRepository.deleteById(categoria.getId());
        criados.forEach(usuario -> usuarioRepository.deleteById(usuario.getId()));
    }

    // ================================================================== REG-01 / I11

    @Nested
    @DisplayName("REG-01: ao menos um admin ativo")
    class AoMenosUmAdmin {

        @Test
        @DisplayName("dois admins rebaixando um ao outro ao mesmo tempo: o segundo e recusado e sobra um admin")
        void naoDeveZerarOsAdminsComRebaixamentosCruzados() throws Exception {
            Desfecho desfecho = intercalar(
                    () -> rebaixar(diego, carla),
                    () -> rebaixar(carla, diego));

            assertThat(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN)).isEqualTo(1);
            assertThat(recarregar(carla).getPerfil()).isEqualTo(Perfil.ADMIN);
            assertThat(desfecho.primeira()).as("a primeira requisicao confirma").isNull();
            assertThat(desfecho.segunda()).as("a segunda ve o rebaixamento ja confirmado")
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("ao menos um administrador ativo");
            assertThat(desfecho.segundaEsperouLock()).as("a segunda esperou a primeira").isTrue();
        }

        @Test
        @DisplayName("um admin desativa o outro enquanto este rebaixa o primeiro: sobra um admin")
        void naoDeveZerarOsAdminsComDesativacaoERebaixamento() throws Exception {
            Desfecho desfecho = intercalar(
                    () -> usuarioService.desativar(diego.getId(), carla),
                    () -> rebaixar(carla, diego));

            assertThat(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN)).isEqualTo(1);
            assertThat(recarregar(diego).isAtivo()).isFalse();
            assertThat(desfecho.primeira()).isNull();
            assertThat(desfecho.segunda()).isInstanceOf(RegraNegocioException.class);
            assertThat(desfecho.segundaEsperouLock()).isTrue();
        }
    }

    // ================================================================== REG-02 / I3

    @Nested
    @DisplayName("REG-02: atendente com chamados em andamento")
    class AtendenteComChamados {

        @Test
        @DisplayName("rebaixar o atendente enquanto ele assume: a assuncao ve o perfil novo e e recusada (403)")
        void naoDeveAssumirComPerfilRebaixadoNoMeioDoCaminho() throws Exception {
            Desfecho desfecho = intercalar(
                    () -> rebaixar(bruno, carla),
                    () -> chamadoService.assumir(chamado.getId(), bruno));

            assertThat(recarregarChamado().getStatus()).isEqualTo(StatusChamado.ABERTO);
            assertThat(recarregarChamado().getAtendente()).isNull();
            assertThat(desfecho.primeira()).isNull();
            assertThat(desfecho.segunda()).isInstanceOf(AcessoNegadoException.class);
            assertThat(desfecho.segundaEsperouLock()).isTrue();
        }

        @Test
        @DisplayName("assumir enquanto o admin rebaixa o atendente: o rebaixamento ve o chamado e e recusado (422)")
        void naoDeveRebaixarQuemAcabouDeAssumir() throws Exception {
            Desfecho desfecho = intercalar(
                    () -> chamadoService.assumir(chamado.getId(), bruno),
                    () -> rebaixar(bruno, carla));

            assertThat(recarregar(bruno).getPerfil()).isEqualTo(Perfil.ATENDENTE);
            assertThat(recarregarChamado().getAtendente().getId()).isEqualTo(bruno.getId());
            assertThat(desfecho.primeira()).isNull();
            assertThat(desfecho.segunda()).isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("atendente de chamados em atendimento");
            assertThat(desfecho.segundaEsperouLock()).isTrue();
        }

        @Test
        @DisplayName("desativar o atendente enquanto ele assume: a assuncao ve o usuario inativo e e recusada (401)")
        void naoDeveAssumirComUsuarioDesativadoNoMeioDoCaminho() throws Exception {
            Desfecho desfecho = intercalar(
                    () -> usuarioService.desativar(bruno.getId(), carla),
                    () -> chamadoService.assumir(chamado.getId(), bruno));

            assertThat(recarregarChamado().getAtendente()).isNull();
            assertThat(desfecho.primeira()).isNull();
            assertThat(desfecho.segunda()).isInstanceOf(NaoAutenticadoException.class);
            assertThat(desfecho.segundaEsperouLock()).isTrue();
        }

        /**
         * Sem threads: o filtro carregou o usuario antes do rebaixamento ser confirmado, e a
         * requisicao chega ao service com esse objeto. A assuncao tem de decidir pelo perfil
         * gravado, nao pelo que o filtro leu.
         */
        @Test
        @DisplayName("o usuario lido pelo filtro antes do rebaixamento nao consegue assumir depois dele (403)")
        void naoDeveAssumirComOUsuarioDesatualizadoDoFiltro() {
            Usuario lidoPeloFiltro = recarregar(bruno);
            rebaixar(bruno, carla);

            Throwable erro = capturar(() -> chamadoService.assumir(chamado.getId(), lidoPeloFiltro));

            assertThat(recarregarChamado().getAtendente()).isNull();
            assertThat(erro).isInstanceOf(AcessoNegadoException.class);
        }
    }

    // ================================================================== apoio

    private Usuario criar(UsuarioBuilder builder) {
        Usuario salvo = usuarioRepository.save(builder.buildSemId());
        criados.add(salvo);
        return salvo;
    }

    private void rebaixar(Usuario alvo, Usuario quemPede) {
        usuarioService.atualizar(alvo.getId(),
                new DadosUsuario(alvo.getNome(), alvo.getEmail(), Perfil.SOLICITANTE), quemPede);
    }

    private Usuario recarregar(Usuario usuario) {
        return usuarioRepository.findById(usuario.getId()).orElseThrow();
    }

    private Chamado recarregarChamado() {
        return chamadoRepository.findWithRelacionamentosById(chamado.getId()).orElseThrow();
    }

    /** Erro de cada requisicao ({@code null} se confirmou) e se a segunda chegou a esperar lock. */
    private record Desfecho(Throwable primeira, Throwable segunda, boolean segundaEsperouLock) {
    }

    /** Executa as duas requisicoes na ordem descrita no Javadoc da classe. */
    private Desfecho intercalar(Runnable primeira, Runnable segunda) throws Exception {
        CountDownLatch primeiraExecutou = new CountDownLatch(1);
        CountDownLatch podeConfirmar = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = executor.submit(() -> capturar(() -> novaTransacao().executeWithoutResult(t -> {
                try {
                    primeira.run();
                } finally {
                    primeiraExecutou.countDown();
                }
                aguardar(podeConfirmar);
            })));
            assertThat(primeiraExecutou.await(10, TimeUnit.SECONDS)).as("a primeira executou").isTrue();

            Future<Throwable> b = executor.submit(() -> capturar(() -> novaTransacao().executeWithoutResult(t -> segunda.run())));
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean esperouLock = false;
            while (!b.isDone() && !esperouLock) {
                esperouLock = haSessaoEsperandoLock();
                assertThat(System.nanoTime()).as("a segunda nem terminou nem bloqueou").isLessThan(limite);
            }

            podeConfirmar.countDown();
            return new Desfecho(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS), esperouLock);
        } finally {
            podeConfirmar.countDown();
            executor.shutdownNow();
        }
    }

    private TransactionTemplate novaTransacao() {
        return new TransactionTemplate(transactionManager);
    }

    private boolean haSessaoEsperandoLock() {
        Integer bloqueadas = jdbc.queryForObject(
                "select count(*) from information_schema.sessions where blocker_id is not null", Integer.class);
        return bloqueadas != null && bloqueadas > 0;
    }

    private static Throwable capturar(Runnable acao) {
        try {
            acao.run();
            return null;
        } catch (Throwable erro) {
            return erro;
        }
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("a primeira requisicao nao foi liberada para confirmar");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
