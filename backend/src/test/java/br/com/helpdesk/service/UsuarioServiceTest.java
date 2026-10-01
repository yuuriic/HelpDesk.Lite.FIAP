package br.com.helpdesk.service;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.AcessoNegadoException;
import br.com.helpdesk.domain.exception.NaoAutenticadoException;
import br.com.helpdesk.domain.exception.RecursoNaoEncontradoException;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.permissao.PermissaoService;
import br.com.helpdesk.domain.state.TransicaoStatus;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.dto.DadosUsuario;
import br.com.helpdesk.service.dto.UsuarioResponse;
import br.com.helpdesk.service.dto.UsuarioResumoResponse;
import br.com.helpdesk.service.mapper.UsuarioMapper;
import br.com.helpdesk.support.UsuarioBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioService")
class UsuarioServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private ChamadoRepository chamadoRepository;

    private UsuarioService usuarioService;

    private final Usuario ana = UsuarioBuilder.solicitante().comId(1L).build();
    private final Usuario bruno = UsuarioBuilder.atendente().comId(2L).build();
    private final Usuario carla = UsuarioBuilder.admin().comId(3L).build();

    @BeforeEach
    void prepararServico() {
        usuarioService = new UsuarioService(usuarioRepository, chamadoRepository,
                new PermissaoService(new TransicaoStatus()), new UsuarioMapper());
    }

    @Test
    void deveListarSomenteUsuariosAtivosNaTelaDeLogin() {
        when(usuarioRepository.findAllByAtivoTrueOrderByNomeAsc()).thenReturn(List.of(ana, bruno, carla));

        // SEG-03: rota publica, entao so o resumo (sem e-mail).
        List<UsuarioResumoResponse> disponiveis = usuarioService.listarDisponiveisParaLogin();

        assertThat(disponiveis).hasSize(3)
                .extracting(UsuarioResumoResponse::perfil)
                .containsExactly(Perfil.SOLICITANTE, Perfil.ATENDENTE, Perfil.ADMIN);
    }

    @Test
    void deveAutenticarUsuarioAtivo() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(ana));

        assertThat(usuarioService.autenticar(1L).id()).isEqualTo(1L);
    }

    @Test
    void deveRecusarLoginDeUsuarioInativo() {
        Usuario inativo = UsuarioBuilder.solicitante().comId(7L).inativo().build();
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(inativo));

        // REG-04: 401 (NaoAutenticado), como no filter, e nao mais 422.
        assertThatThrownBy(() -> usuarioService.autenticar(7L))
                .isInstanceOf(NaoAutenticadoException.class)
                .hasMessage(NaoAutenticadoException.MENSAGEM_PADRAO);
    }

    @Test
    @DisplayName("inexistente recebe a mesma recusa do inativo -- o login nao revela quais ids existem")
    void deveRecusarLoginDeUsuarioInexistente() {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        // REG-04 / SEG-06: antes era RecursoNaoEncontrado (404).
        assertThatThrownBy(() -> usuarioService.autenticar(99L))
                .isInstanceOf(NaoAutenticadoException.class)
                .hasMessage(NaoAutenticadoException.MENSAGEM_PADRAO);
    }

    @Test
    void deveRestringirAListagemAdministrativaAoAdmin() {
        when(usuarioRepository.findAllByOrderByNomeAsc()).thenReturn(List.of(ana, bruno, carla));

        assertThat(usuarioService.listar(carla)).hasSize(3);
        assertThatThrownBy(() -> usuarioService.listar(bruno)).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> usuarioService.listar(ana)).isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    @DisplayName("o e-mail e normalizado para minusculas na criacao")
    void deveCriarUsuarioNormalizandoOEmail() {
        when(usuarioRepository.existsByEmailIgnoreCase("Novo@Empresa.com".toLowerCase())).thenReturn(false);
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        usuarioService.criar(new DadosUsuario("  Novo Usuario  ", "  Novo@Empresa.com  ",
                Perfil.ATENDENTE), carla);

        ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(capturado.capture());
        assertThat(capturado.getValue().getEmail()).isEqualTo("novo@empresa.com");
        assertThat(capturado.getValue().getNome()).isEqualTo("Novo Usuario");
        assertThat(capturado.getValue().isAtivo()).isTrue();
    }

    @Test
    void deveRejeitarEmailDuplicadoNaCriacao() {
        when(usuarioRepository.existsByEmailIgnoreCase("ana@empresa.com")).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.criar(
                new DadosUsuario("Ana", "ana@empresa.com", Perfil.SOLICITANTE), carla))
                .isInstanceOf(RegraNegocioException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void deveAtualizarNomeEmailEPerfil() {
        Usuario existente = UsuarioBuilder.solicitante().comId(5L).build();
        when(usuarioRepository.findComTravaById(5L)).thenReturn(Optional.of(existente));
        when(usuarioRepository.existsByEmailIgnoreCaseAndIdNot("novo@empresa.com", 5L)).thenReturn(false);
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioResponse resposta = usuarioService.atualizar(5L,
                new DadosUsuario("Novo Nome", "novo@empresa.com", Perfil.ATENDENTE), carla);

        assertThat(resposta.nome()).isEqualTo("Novo Nome");
        assertThat(resposta.email()).isEqualTo("novo@empresa.com");
        assertThat(resposta.perfil()).isEqualTo(Perfil.ATENDENTE);
    }

    /**
     * N-01: as guardas de REG-01/REG-02 so valem sob concorrencia com as travas, e as travas so
     * nao dao deadlock se toda transacao as pedir na mesma ordem: admins ativos, depois o alvo,
     * e so entao as contagens que decidem.
     */
    @Test
    @DisplayName("atualizar trava os admins ativos e depois o alvo, antes de contar admins e chamados")
    void deveTravarAdminsDepoisOAlvoAntesDasGuardas() {
        Usuario alvo = UsuarioBuilder.admin().comId(9L).build();
        when(usuarioRepository.findComTravaById(9L)).thenReturn(Optional.of(alvo));
        when(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(2L);
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        usuarioService.atualizar(9L, new DadosUsuario("Outra", "outra@empresa.com", Perfil.SOLICITANTE), carla);

        InOrder ordem = inOrder(usuarioRepository, chamadoRepository);
        ordem.verify(usuarioRepository).findComTravaByPerfilAndAtivoTrueOrderByIdAsc(Perfil.ADMIN);
        ordem.verify(usuarioRepository).findComTravaById(9L);
        ordem.verify(usuarioRepository).countByPerfilAndAtivoTrue(Perfil.ADMIN);
        ordem.verify(chamadoRepository).existsByAtendenteIdAndStatusIn(eq(9L), anyCollection());
        verify(usuarioRepository, never()).findById(anyLong());
    }

    @Test
    void deveRejeitarEmailJaUsadoPorOutroUsuario() {
        when(usuarioRepository.findComTravaById(5L))
                .thenReturn(Optional.of(UsuarioBuilder.solicitante().comId(5L).build()));
        when(usuarioRepository.existsByEmailIgnoreCaseAndIdNot("ana@empresa.com", 5L)).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.atualizar(5L,
                new DadosUsuario("Ana", "ana@empresa.com", Perfil.SOLICITANTE), carla))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void deveDesativarEmVezDeExcluir() {
        Usuario alvo = UsuarioBuilder.solicitante().comId(5L).build();
        when(usuarioRepository.findComTravaById(5L)).thenReturn(Optional.of(alvo));

        usuarioService.desativar(5L, carla);

        assertThat(alvo.isAtivo()).isFalse();
        verify(usuarioRepository).save(alvo);
        verify(usuarioRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("o admin nao pode desativar a si mesmo -- ficaria sem conseguir entrar de volta")
    void deveImpedirQueOAdminDesativeOProprioUsuario() {
        assertThatThrownBy(() -> usuarioService.desativar(3L, carla))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("proprio");
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void deveDevolver404AoDesativarUsuarioInexistente() {
        when(usuarioRepository.findComTravaById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.desativar(99L, carla))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void deveNegarGestaoDeUsuariosParaQuemNaoEAdmin() {
        DadosUsuario requisicao = new DadosUsuario("X", "x@empresa.com", Perfil.SOLICITANTE);

        assertThatThrownBy(() -> usuarioService.criar(requisicao, bruno))
                .isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> usuarioService.atualizar(1L, requisicao, ana))
                .isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> usuarioService.desativar(1L, bruno))
                .isInstanceOf(AcessoNegadoException.class);
    }

    // ================================================================== invariantes (Fase 2)

    @Nested
    @DisplayName("REG-01: sempre existe ao menos um admin ativo")
    class AoMenosUmAdmin {

        @Test
        @DisplayName("o unico admin ativo nao pode ser rebaixado, nem por ele mesmo")
        void deveImpedirRebaixarOUnicoAdmin() {
            when(usuarioRepository.findComTravaById(3L)).thenReturn(Optional.of(carla));
            when(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(1L);

            assertThatThrownBy(() -> usuarioService.atualizar(3L,
                    new DadosUsuario(carla.getNome(), carla.getEmail(), Perfil.SOLICITANTE), carla))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("ao menos um administrador");
            assertThat(carla.getPerfil()).isEqualTo(Perfil.ADMIN);
            verify(usuarioRepository, never()).save(any());
        }

        @Test
        @DisplayName("com outro admin ativo, rebaixar um admin e permitido")
        void devePermitirRebaixarQuandoHaOutroAdmin() {
            Usuario outraAdmin = UsuarioBuilder.admin().comId(9L).comEmail("dora@empresa.com").build();
            when(usuarioRepository.findComTravaById(9L)).thenReturn(Optional.of(outraAdmin));
            when(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(2L);
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

            UsuarioResponse resposta = usuarioService.atualizar(9L,
                    new DadosUsuario("Dora", "dora@empresa.com", Perfil.ATENDENTE), carla);

            assertThat(resposta.perfil()).isEqualTo(Perfil.ATENDENTE);
        }

        @Test
        @DisplayName("admin inativo nao conta: pode ser rebaixado mesmo sendo o unico ADMIN cadastrado")
        void deveIgnorarAdminInativo() {
            Usuario inativa = UsuarioBuilder.admin().comId(9L).comEmail("dora@empresa.com").inativo().build();
            when(usuarioRepository.findComTravaById(9L)).thenReturn(Optional.of(inativa));
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

            usuarioService.atualizar(9L, new DadosUsuario("Dora", "dora@empresa.com", Perfil.SOLICITANTE), carla);

            assertThat(inativa.getPerfil()).isEqualTo(Perfil.SOLICITANTE);
            verify(usuarioRepository, never()).countByPerfilAndAtivoTrue(any());
        }

        @Test
        @DisplayName("editar nome e e-mail do unico admin, sem trocar o perfil, nao passa pela regra")
        void naoDeveAplicarARegraSemTrocaDePerfil() {
            when(usuarioRepository.findComTravaById(3L)).thenReturn(Optional.of(carla));
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

            usuarioService.atualizar(3L, new DadosUsuario("Carla Souza", "carla@empresa.com", Perfil.ADMIN), carla);

            assertThat(carla.getNome()).isEqualTo("Carla Souza");
            verify(usuarioRepository, never()).countByPerfilAndAtivoTrue(any());
        }

        @Test
        @DisplayName("desativar o unico admin ativo tambem e barrado (defesa da invariante)")
        void deveImpedirDesativarOUnicoAdmin() {
            Usuario unicaAdmin = UsuarioBuilder.admin().comId(9L).build();
            when(usuarioRepository.findComTravaById(9L)).thenReturn(Optional.of(unicaAdmin));
            when(usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(1L);

            assertThatThrownBy(() -> usuarioService.desativar(9L, carla))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("ao menos um administrador");
            assertThat(unicaAdmin.isAtivo()).isTrue();
        }
    }

    @Nested
    @DisplayName("REG-02 / I3: atendente com chamado em andamento continua podendo atender")
    class AtendenteComChamados {

        private final Usuario atendente = UsuarioBuilder.atendente().comId(2L).comEmail("bruno@empresa.com").build();

        private void comChamadosEmAndamento(boolean possui) {
            when(chamadoRepository.existsByAtendenteIdAndStatusIn(eq(2L), anyCollection())).thenReturn(possui);
        }

        @Test
        @DisplayName("a consulta cobre EM_ATENDIMENTO e RESOLVIDO (reabrivel), nao ABERTO nem FECHADO")
        void deveConsiderarEmAtendimentoEResolvido() {
            when(usuarioRepository.findComTravaById(2L)).thenReturn(Optional.of(atendente));
            when(chamadoRepository.existsByAtendenteIdAndStatusIn(2L,
                    EnumSet.of(StatusChamado.EM_ATENDIMENTO, StatusChamado.RESOLVIDO))).thenReturn(true);

            assertThatThrownBy(() -> usuarioService.desativar(2L, carla))
                    .isInstanceOf(RegraNegocioException.class);
        }

        @Test
        void deveImpedirRebaixarParaSolicitante() {
            when(usuarioRepository.findComTravaById(2L)).thenReturn(Optional.of(atendente));
            comChamadosEmAndamento(true);

            assertThatThrownBy(() -> usuarioService.atualizar(2L,
                    new DadosUsuario(atendente.getNome(), atendente.getEmail(), Perfil.SOLICITANTE), carla))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("rebaixa-lo para solicitante");
            assertThat(atendente.getPerfil()).isEqualTo(Perfil.ATENDENTE);
            verify(usuarioRepository, never()).save(any());
        }

        @Test
        void deveImpedirDesativar() {
            when(usuarioRepository.findComTravaById(2L)).thenReturn(Optional.of(atendente));
            comChamadosEmAndamento(true);

            assertThatThrownBy(() -> usuarioService.desativar(2L, carla))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("desativa-lo");
            assertThat(atendente.isAtivo()).isTrue();
            verify(usuarioRepository, never()).save(any());
        }

        @Test
        @DisplayName("sem chamados em andamento, rebaixar e desativar seguem permitidos")
        void devePermitirSemChamadosEmAndamento() {
            when(usuarioRepository.findComTravaById(2L)).thenReturn(Optional.of(atendente));
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
            comChamadosEmAndamento(false);

            usuarioService.atualizar(2L,
                    new DadosUsuario(atendente.getNome(), atendente.getEmail(), Perfil.SOLICITANTE), carla);
            usuarioService.desativar(2L, carla);

            assertThat(atendente.getPerfil()).isEqualTo(Perfil.SOLICITANTE);
            assertThat(atendente.isAtivo()).isFalse();
        }

        @Test
        @DisplayName("promover a ADMIN nao passa pela regra: admin tambem atende")
        void devePermitirPromoverComChamados() {
            when(usuarioRepository.findComTravaById(2L)).thenReturn(Optional.of(atendente));
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

            usuarioService.atualizar(2L,
                    new DadosUsuario(atendente.getNome(), atendente.getEmail(), Perfil.ADMIN), carla);

            assertThat(atendente.getPerfil()).isEqualTo(Perfil.ADMIN);
            verify(chamadoRepository, never()).existsByAtendenteIdAndStatusIn(anyLong(), anyCollection());
        }
    }
}
