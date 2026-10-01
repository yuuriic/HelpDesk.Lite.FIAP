package br.com.helpdesk.integration;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.Prioridade;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.UsuarioBuilder;
import br.com.helpdesk.web.dto.request.AlterarStatusRequest;
import br.com.helpdesk.web.dto.request.NovoChamadoRequest;
import br.com.helpdesk.web.dto.request.UsuarioRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invariantes que o cadastro de usuarios nao pode quebrar (Fase 2 do plano).
 *
 * <p>Substitui os testes de defeito REG-01 e REG-02 de DefeitosConhecidosIT (removida na Fase 9, com o ultimo defeito tratado):</p>
 * <ul>
 *   <li>REG-01 -- o sistema nunca fica sem nenhum admin ativo;</li>
 *   <li>REG-02 / I3 -- quem e atendente de chamado em atendimento ou resolvido (reabrivel)
 *       nao e rebaixado a solicitante nem desativado. Sem reatribuicao no produto, isso deixaria
 *       o chamado orfao.</li>
 * </ul>
 * <p>Nenhuma funcionalidade nova: as regras so recusam (422) operacoes que corrompiam estado.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Invariantes do cadastro de usuarios (integracao)")
class InvariantesUsuarioIT {

    private static final String HEADER_USUARIO = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private CategoriaRepository categoriaRepository;

    private Usuario ana;
    private Usuario bruno;
    private Usuario carla;
    private Categoria categoria;

    @BeforeEach
    void criarCenario() {
        ana = usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("ana@invariantes.com").buildSemId());
        bruno = usuarioRepository.save(UsuarioBuilder.atendente().comEmail("bruno@invariantes.com").buildSemId());
        carla = usuarioRepository.save(UsuarioBuilder.admin().comEmail("carla@invariantes.com").buildSemId());
        categoria = categoriaRepository.save(CategoriaBuilder.comSla(4).comNome("Invariantes - TI").buildSemId());
    }

    // ------------------------------------------------------------------ apoio

    private String json(Object corpo) throws Exception {
        return objectMapper.writeValueAsString(corpo);
    }

    private ResultActions atualizarPerfil(Usuario alvo, Perfil perfil, Usuario quem) throws Exception {
        return mockMvc.perform(put("/api/usuarios/{id}", alvo.getId()).header(HEADER_USUARIO, quem.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UsuarioRequest(alvo.getNome(), alvo.getEmail(), perfil))));
    }

    private ResultActions desativar(Usuario alvo) throws Exception {
        return mockMvc.perform(delete("/api/usuarios/{id}", alvo.getId()).header(HEADER_USUARIO, carla.getId()));
    }

    private long chamadoAssumidoPor(Usuario atendente) throws Exception {
        String corpo = mockMvc.perform(post("/api/chamados").header(HEADER_USUARIO, ana.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new NovoChamadoRequest("Rede lenta no andar",
                                "A rede do andar esta lenta desde cedo.", categoria.getId(), Prioridade.MEDIA))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(corpo).get("id").asLong();
        mockMvc.perform(patch("/api/chamados/{id}/assumir", id).header(HEADER_USUARIO, atendente.getId()))
                .andExpect(status().isOk());
        return id;
    }

    private ResultActions alterarStatus(long chamadoId, Usuario quem, StatusChamado para) throws Exception {
        return mockMvc.perform(patch("/api/chamados/{id}/status", chamadoId).header(HEADER_USUARIO, quem.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new AlterarStatusRequest(para))));
    }

    // ================================================================== REG-01

    @Nested
    @DisplayName("REG-01: sempre existe ao menos um admin ativo")
    class AoMenosUmAdmin {

        @Test
        @DisplayName("o unico admin nao consegue se rebaixar: 422 e continua administrando")
        void deveImpedirOUnicoAdminDeSeRebaixar() throws Exception {
            atualizarPerfil(carla, Perfil.SOLICITANTE, carla)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"))
                    .andExpect(jsonPath("$.mensagem").value("O sistema precisa de ao menos um administrador ativo"));

            assertThat(usuarioRepository.findById(carla.getId()).orElseThrow().getPerfil()).isEqualTo(Perfil.ADMIN);
            mockMvc.perform(get("/api/usuarios").header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("com um segundo admin ativo, o rebaixamento e permitido")
        void devePermitirRebaixarComOutroAdminAtivo() throws Exception {
            Usuario dora = usuarioRepository.save(UsuarioBuilder.admin().comNome("Dora Admin")
                    .comEmail("dora@invariantes.com").buildSemId());

            atualizarPerfil(carla, Perfil.ATENDENTE, dora)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.perfil").value("ATENDENTE"));
        }

        @Test
        @DisplayName("um admin inativo nao conta como o segundo admin")
        void naoDeveContarAdminInativo() throws Exception {
            usuarioRepository.save(UsuarioBuilder.admin().comEmail("antiga@invariantes.com").inativo().buildSemId());

            atualizarPerfil(carla, Perfil.ATENDENTE, carla)
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    // ================================================================== REG-02

    @Nested
    @DisplayName("REG-02 / I3: atendente com chamado em andamento continua podendo atender")
    class AtendenteComChamadoEmAndamento {

        @Test
        @DisplayName("rebaixar a SOLICITANTE com chamado EM_ATENDIMENTO: 422, e o atendente segue resolvendo")
        void deveImpedirRebaixarComChamadoEmAtendimento() throws Exception {
            long id = chamadoAssumidoPor(bruno);

            atualizarPerfil(bruno, Perfil.SOLICITANTE, carla)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"));

            mockMvc.perform(get("/api/chamados/{id}", id).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(jsonPath("$.atendente.id").value(bruno.getId()))
                    .andExpect(jsonPath("$.atendente.perfil").value("ATENDENTE"));
            alterarStatus(id, bruno, StatusChamado.RESOLVIDO).andExpect(status().isOk());
        }

        @Test
        @DisplayName("desativar com chamado EM_ATENDIMENTO: 422, e o atendente continua autenticando")
        void deveImpedirDesativarComChamadoEmAtendimento() throws Exception {
            chamadoAssumidoPor(bruno);

            desativar(bruno)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"));

            mockMvc.perform(get("/api/chamados").header(HEADER_USUARIO, bruno.getId()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("chamado RESOLVIDO tambem prende o atendente: a reabertura volta para ele")
        void deveConsiderarChamadoResolvidoReabrivel() throws Exception {
            long id = chamadoAssumidoPor(bruno);
            alterarStatus(id, bruno, StatusChamado.RESOLVIDO).andExpect(status().isOk());

            atualizarPerfil(bruno, Perfil.SOLICITANTE, carla).andExpect(status().isUnprocessableEntity());
            desativar(bruno).andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("chamados so FECHADOS nao bloqueiam: o historico fica e o atendente pode sair")
        void devePermitirQuandoTodosOsChamadosEstaoFechados() throws Exception {
            long id = chamadoAssumidoPor(bruno);
            alterarStatus(id, bruno, StatusChamado.RESOLVIDO).andExpect(status().isOk());
            alterarStatus(id, ana, StatusChamado.FECHADO).andExpect(status().isOk());

            atualizarPerfil(bruno, Perfil.SOLICITANTE, carla).andExpect(status().isOk());
            desativar(bruno).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/chamados/{id}", id).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(jsonPath("$.atendente.id").value(bruno.getId()));
        }

        @Test
        @DisplayName("sem reatribuicao: o admin conclui os chamados e ai o rebaixamento passa")
        void deveLiberarDepoisQueOAdminConcluiOsChamados() throws Exception {
            long id = chamadoAssumidoPor(bruno);
            atualizarPerfil(bruno, Perfil.SOLICITANTE, carla).andExpect(status().isUnprocessableEntity());

            // Caminho que ja existe hoje: o admin pode resolver e fechar qualquer chamado.
            alterarStatus(id, carla, StatusChamado.RESOLVIDO).andExpect(status().isOk());
            alterarStatus(id, carla, StatusChamado.FECHADO).andExpect(status().isOk());

            atualizarPerfil(bruno, Perfil.SOLICITANTE, carla).andExpect(status().isOk());
        }

        @Test
        @DisplayName("trocar ATENDENTE por ADMIN com chamado em andamento e permitido: admin tambem atende")
        void devePermitirPromoverComChamadoEmAndamento() throws Exception {
            long id = chamadoAssumidoPor(bruno);

            atualizarPerfil(bruno, Perfil.ADMIN, carla).andExpect(status().isOk());

            alterarStatus(id, bruno, StatusChamado.RESOLVIDO).andExpect(status().isOk());
        }

        @Test
        @DisplayName("editar nome e e-mail do atendente com chamado em andamento continua permitido")
        void devePermitirEditarDadosSemTrocarPerfil() throws Exception {
            chamadoAssumidoPor(bruno);

            mockMvc.perform(put("/api/usuarios/{id}", bruno.getId()).header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UsuarioRequest("Bruno Souza", "bruno.souza@invariantes.com",
                                    Perfil.ATENDENTE))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nome").value("Bruno Souza"));
        }

        @Test
        @DisplayName("um chamado ainda ABERTO (sem atendente) nao prende ninguem")
        void naoDeveConsiderarChamadoAberto() throws Exception {
            mockMvc.perform(post("/api/chamados").header(HEADER_USUARIO, ana.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new NovoChamadoRequest("Mouse com defeito",
                                    "O clique direito parou de funcionar.", categoria.getId(), Prioridade.BAIXA))))
                    .andExpect(status().isCreated());

            desativar(bruno).andExpect(status().isNoContent());
        }
    }
}
