package br.com.helpdesk.integration;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.support.CategoriaBuilder;
import br.com.helpdesk.support.UsuarioBuilder;
import br.com.helpdesk.web.dto.request.CategoriaRequest;
import br.com.helpdesk.web.dto.request.LoginRequest;
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
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Caracterizacao do contrato HTTP de categorias, usuarios, dashboard e login simulado.
 *
 * <p>Ate aqui so o {@code ChamadoController} tinha teste na camada web (auditoria, TST-01).
 * Estes testes fixam status HTTP, codigos de erro e o formato do JSON como estao hoje,
 * para que mover DTOs entre camadas (ARQ-01) ou reorganizar o tratamento de excecoes
 * (EXC-02) nao mude o contrato sem ninguem perceber.</p>
 *
 * <p>So comportamento <b>correto</b> entra aqui. Os defeitos conhecidos destas rotas estao
 * fixados, com o id da auditoria, em DefeitosConhecidosIT (removida na Fase 9, com o ultimo defeito tratado).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Contrato HTTP de cadastros, dashboard e login (caracterizacao)")
class CadastrosApiIT {

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
    private Categoria ativa;
    private Categoria inativa;

    @BeforeEach
    void criarCenario() {
        ana = usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("ana@cad.com").buildSemId());
        bruno = usuarioRepository.save(UsuarioBuilder.atendente().comEmail("bruno@cad.com").buildSemId());
        carla = usuarioRepository.save(UsuarioBuilder.admin().comEmail("carla@cad.com").buildSemId());
        ativa = categoriaRepository.save(CategoriaBuilder.comSla(4).comNome("Cad - Ativa").buildSemId());
        inativa = categoriaRepository.save(
                CategoriaBuilder.comSla(8).comNome("Cad - Inativa").inativa().buildSemId());
    }

    private String json(Object corpo) throws Exception {
        return objectMapper.writeValueAsString(corpo);
    }

    // ================================================================== categorias

    @Nested
    @DisplayName("categorias")
    class Categorias {

        @Test
        @DisplayName("GET sem parametro devolve so as ativas, para qualquer perfil")
        void deveListarSoAsAtivasPorPadrao() throws Exception {
            mockMvc.perform(get("/api/categorias").header(HEADER_USUARIO, ana.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].nome", hasItem("Cad - Ativa")))
                    .andExpect(jsonPath("$[*].nome", not(hasItem("Cad - Inativa"))))
                    .andExpect(jsonPath("$[0].id").exists())
                    .andExpect(jsonPath("$[0].slaHoras").exists())
                    .andExpect(jsonPath("$[0].ativa").value(true));
        }

        @Test
        @DisplayName("incluirInativas=true devolve tambem as inativas, so para o admin")
        void deveIncluirInativasApenasParaOAdmin() throws Exception {
            mockMvc.perform(get("/api/categorias").param("incluirInativas", "true")
                            .header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].nome", hasItem("Cad - Inativa")));

            mockMvc.perform(get("/api/categorias").param("incluirInativas", "true")
                            .header(HEADER_USUARIO, bruno.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.erro").value("ACESSO_NEGADO"));
        }

        @Test
        @DisplayName("POST cria com 201, apara o nome e nasce ativa")
        void deveCriarCategoria() throws Exception {
            mockMvc.perform(post("/api/categorias").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CategoriaRequest("  Cad - Nova  ", 12))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.nome").value("Cad - Nova"))
                    .andExpect(jsonPath("$.slaHoras").value(12))
                    .andExpect(jsonPath("$.ativa").value(true));
        }

        @Test
        void deveNegarEscritaDeCategoriaParaQuemNaoEAdmin() throws Exception {
            for (Usuario naoAdmin : new Usuario[]{ana, bruno}) {
                mockMvc.perform(post("/api/categorias").header(HEADER_USUARIO, naoAdmin.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(new CategoriaRequest("Cad - Proibida", 4))))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.erro").value("ACESSO_NEGADO"));
            }
        }

        @Test
        @DisplayName("nome duplicado, sem diferenciar maiusculas, e 422 REGRA_NEGOCIO")
        void deveRejeitarNomeDuplicado() throws Exception {
            mockMvc.perform(post("/api/categorias").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CategoriaRequest("cad - ativa", 4))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"));
        }

        @Test
        @DisplayName("DTO invalido e 400 VALIDACAO com a lista de campos")
        void deveValidarOCorpo() throws Exception {
            mockMvc.perform(post("/api/categorias").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CategoriaRequest("", 0))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.erro").value("VALIDACAO"))
                    .andExpect(jsonPath("$.campos[*].campo", hasItem("nome")))
                    .andExpect(jsonPath("$.campos[*].campo", hasItem("slaHoras")));
        }

        @Test
        void deveAtualizarNomeESla() throws Exception {
            mockMvc.perform(put("/api/categorias/{id}", ativa.getId()).header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CategoriaRequest("Cad - Renomeada", 6))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ativa.getId()))
                    .andExpect(jsonPath("$.nome").value("Cad - Renomeada"))
                    .andExpect(jsonPath("$.slaHoras").value(6));
        }

        @Test
        void deveDevolver404AoAtualizarCategoriaInexistente() throws Exception {
            mockMvc.perform(put("/api/categorias/{id}", 999_999).header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CategoriaRequest("Cad - Fantasma", 6))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.erro").value("RECURSO_NAO_ENCONTRADO"));
        }

        /**
         * REG-03, reclassificado: era [DEFEITO CONHECIDO] em DefeitosConhecidosIT. Nao existe
         * reativacao no MVP -- criar a rota seria funcionalidade nova (plano, secao 14) --, entao o
         * comportamento correto e o PUT atualizar so nome e SLA. O que estava errado era o Javadoc
         * do CategoriaService prometer a reativacao, e ele foi corrigido.
         */
        @Test
        @DisplayName("PUT atualiza so nome e SLA: nao existe reativacao, ativa=true no corpo e ignorado")
        void naoDeveReativarCategoriaPeloPut() throws Exception {
            mockMvc.perform(put("/api/categorias/{id}", inativa.getId()).header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nome\":\"Cad - Inativa\",\"slaHoras\":10,\"ativa\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slaHoras").value(10))
                    .andExpect(jsonPath("$.ativa").value(false));
        }

        @Test
        @DisplayName("DELETE e soft delete: 204, some das ativas e continua na lista completa")
        void deveDesativarComSoftDelete() throws Exception {
            mockMvc.perform(delete("/api/categorias/{id}", ativa.getId()).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/categorias").header(HEADER_USUARIO, carla.getId()))
                    .andExpect(jsonPath("$[*].nome", not(hasItem("Cad - Ativa"))));
            mockMvc.perform(get("/api/categorias").param("incluirInativas", "true")
                            .header(HEADER_USUARIO, carla.getId()))
                    .andExpect(jsonPath("$[?(@.nome == 'Cad - Ativa')].ativa").value(false));
        }
    }

    // ================================================================== usuarios

    @Nested
    @DisplayName("usuarios")
    class Usuarios {

        @Test
        @DisplayName("GET lista ativos e inativos, com e-mail, so para o admin")
        void deveListarUsuariosSoParaOAdmin() throws Exception {
            usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("inativo@cad.com").inativo().buildSemId());

            mockMvc.perform(get("/api/usuarios").header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].email", hasItem("inativo@cad.com")))
                    .andExpect(jsonPath("$[0].perfil").exists())
                    .andExpect(jsonPath("$[0].ativo").exists());

            mockMvc.perform(get("/api/usuarios").header(HEADER_USUARIO, bruno.getId()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST cria com 201, apara o nome, poe o e-mail em minusculas e nasce ativo")
        void deveCriarUsuario() throws Exception {
            mockMvc.perform(post("/api/usuarios").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UsuarioRequest("  Davi Novo ", "DAVI@Cad.COM", Perfil.ATENDENTE))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.nome").value("Davi Novo"))
                    .andExpect(jsonPath("$.email").value("davi@cad.com"))
                    .andExpect(jsonPath("$.perfil").value("ATENDENTE"))
                    .andExpect(jsonPath("$.ativo").value(true));
        }

        /**
         * O {@code trim()} do e-mail em UsuarioService nunca e alcancado pela API: o
         * {@code @Email} do DTO roda antes e reprova o valor com espacos. Comportamento atual,
         * fixado para que mover a normalizacao (REG-06) seja uma decisao consciente.
         */
        @Test
        @DisplayName("e-mail com espacos nas pontas e reprovado na validacao (400), antes do trim do service")
        void deveReprovarEmailComEspacosAntesDoTrim() throws Exception {
            mockMvc.perform(post("/api/usuarios").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UsuarioRequest("Davi Novo", "  davi@cad.com ", Perfil.ATENDENTE))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos[*].campo", hasItem("email")));
        }

        @Test
        @DisplayName("e-mail repetido, sem diferenciar maiusculas, e 422 REGRA_NEGOCIO")
        void deveRejeitarEmailDuplicado() throws Exception {
            mockMvc.perform(post("/api/usuarios").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UsuarioRequest("Outra Ana", "ANA@cad.com", Perfil.SOLICITANTE))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"));
        }

        @Test
        void deveValidarEmailInvalido() throws Exception {
            mockMvc.perform(post("/api/usuarios").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UsuarioRequest("Davi", "nao-e-email", Perfil.SOLICITANTE))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos[*].campo", hasItem("email")));
        }

        @Test
        void deveAtualizarNomeEmailEPerfil() throws Exception {
            mockMvc.perform(put("/api/usuarios/{id}", ana.getId()).header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UsuarioRequest("Ana Promovida", "ana@cad.com", Perfil.ATENDENTE))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nome").value("Ana Promovida"))
                    .andExpect(jsonPath("$.perfil").value("ATENDENTE"));
        }

        @Test
        @DisplayName("DELETE e soft delete com 204; o desativado deixa de autenticar (401)")
        void deveDesativarUsuario() throws Exception {
            mockMvc.perform(delete("/api/usuarios/{id}", ana.getId()).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/chamados").header(HEADER_USUARIO, ana.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.erro").value("NAO_AUTENTICADO"));
        }

        @Test
        @DisplayName("desativar o proprio usuario e 422 REGRA_NEGOCIO (regra I10)")
        void deveImpedirAutoDesativacao() throws Exception {
            mockMvc.perform(delete("/api/usuarios/{id}", carla.getId()).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"));
        }
    }

    // ================================================================== dashboard

    @Nested
    @DisplayName("dashboard")
    class Dashboard {

        @Test
        @DisplayName("atendente e admin recebem o resumo com todas as chaves de status e prioridade")
        void deveDevolverOResumoParaAtendenteEAdmin() throws Exception {
            for (Usuario autorizado : new Usuario[]{bruno, carla}) {
                mockMvc.perform(get("/api/dashboard/resumo").header(HEADER_USUARIO, autorizado.getId()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalPorStatus.ABERTO").exists())
                        .andExpect(jsonPath("$.totalPorStatus.EM_ATENDIMENTO").exists())
                        .andExpect(jsonPath("$.totalPorStatus.RESOLVIDO").exists())
                        .andExpect(jsonPath("$.totalPorStatus.FECHADO").exists())
                        .andExpect(jsonPath("$.totalPorPrioridade.BAIXA").exists())
                        .andExpect(jsonPath("$.totalPorPrioridade.MEDIA").exists())
                        .andExpect(jsonPath("$.totalPorPrioridade.ALTA").exists())
                        .andExpect(jsonPath("$.totalChamados").isNumber())
                        .andExpect(jsonPath("$.foraDoSla").isNumber())
                        .andExpect(jsonPath("$.tempoMedioResolucaoHoras").isNumber())
                        .andExpect(jsonPath("$.chamadosForaDoSla").isArray());
            }
        }

        @Test
        void deveNegarODashboardParaOSolicitante() throws Exception {
            mockMvc.perform(get("/api/dashboard/resumo").header(HEADER_USUARIO, ana.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.erro").value("ACESSO_NEGADO"));
        }
    }

    // ================================================================== login simulado

    @Nested
    @DisplayName("login simulado")
    class Login {

        @Test
        @DisplayName("usuarios-disponiveis lista so os ativos, sem exigir X-User-Id")
        void deveListarSoUsuariosAtivos() throws Exception {
            Usuario fora = usuarioRepository.save(
                    UsuarioBuilder.solicitante().comEmail("fora@cad.com").inativo().buildSemId());

            // SEG-03: a rota nao traz mais e-mail, entao o inativo e conferido pelo id.
            mockMvc.perform(get("/api/auth/usuarios-disponiveis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id", hasItem(ana.getId().intValue())))
                    .andExpect(jsonPath("$[*].id", not(hasItem(fora.getId().intValue()))));
        }

        @Test
        void deveEntrarComUsuarioAtivo() throws Exception {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginRequest(bruno.getId()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(bruno.getId()))
                    .andExpect(jsonPath("$.perfil").value("ATENDENTE"))
                    // SEG-03: resumo publico, sem e-mail (e sem "ativo": so usuario ativo entra).
                    .andExpect(jsonPath("$.email").doesNotExist());
        }

        @Test
        void deveValidarOCorpoDoLogin() throws Exception {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos[*].campo", containsInAnyOrder("usuarioId")));
        }
    }
}
