package br.com.helpdesk.integration;

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
import br.com.helpdesk.web.dto.request.NovoComentarioRequest;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Caracterizacao do contrato HTTP do chamado: formato exato do JSON e regras de atribuicao
 * de atendente. O formato da linha de auditoria esta em {@link ObservabilidadeIT}: desde a Fase 8
 * ela so e escrita depois do commit, e esta classe roda numa transacao de teste que e revertida.
 *
 * <p>O {@code CicloDeVidaChamadoIT} prova que o fluxo funciona, mas confere so alguns campos.
 * A refatoracao vai mover DTOs e mappers de camada (ARQ-01), tirar o calculo de SLA do mapper
 * (ARQ-04) e colocar os efeitos das transicoes dentro da entidade (DOM-01). Estes testes
 * garantem que o JSON que o frontend consome e as regras de "quem e o atendente" continuam
 * identicos.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Contrato HTTP do chamado (caracterizacao)")
class ChamadoContratoApiIT {

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
    private Usuario eva;
    private Usuario carla;
    private Categoria categoria;

    @BeforeEach
    void criarCenario() {
        ana = usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("ana@contrato.com").buildSemId());
        bruno = usuarioRepository.save(UsuarioBuilder.atendente().comEmail("bruno@contrato.com").buildSemId());
        eva = usuarioRepository.save(UsuarioBuilder.atendente().comNome("Eva Atendente")
                .comEmail("eva@contrato.com").buildSemId());
        carla = usuarioRepository.save(UsuarioBuilder.admin().comEmail("carla@contrato.com").buildSemId());
        categoria = categoriaRepository.save(CategoriaBuilder.comSla(4).comNome("Contrato - TI").buildSemId());
    }

    // ------------------------------------------------------------------ apoio

    private String json(Object corpo) throws Exception {
        return objectMapper.writeValueAsString(corpo);
    }

    private JsonNode corpoDe(MvcResult resultado) throws Exception {
        return objectMapper.readTree(resultado.getResponse().getContentAsString());
    }

    private static List<String> campos(JsonNode no) {
        List<String> nomes = new ArrayList<>();
        no.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }

    private long abrirChamado() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/chamados").header(HEADER_USUARIO, ana.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new NovoChamadoRequest("Impressora sem toner",
                                "A impressora do segundo andar parou de imprimir.", categoria.getId(),
                                Prioridade.MEDIA))))
                .andExpect(status().isCreated())
                .andReturn();
        return corpoDe(resultado).get("id").asLong();
    }

    private void assumir(long chamadoId, Usuario quem) throws Exception {
        mockMvc.perform(patch("/api/chamados/{id}/assumir", chamadoId).header(HEADER_USUARIO, quem.getId()))
                .andExpect(status().isOk());
    }

    private MvcResult alterarStatus(long chamadoId, Usuario quem, StatusChamado para) throws Exception {
        return mockMvc.perform(patch("/api/chamados/{id}/status", chamadoId).header(HEADER_USUARIO, quem.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AlterarStatusRequest(para))))
                .andReturn();
    }

    // ================================================================== formato do JSON

    @Nested
    @DisplayName("formato do JSON")
    class FormatoDoJson {

        @Test
        @DisplayName("POST devolve 201 com Location apontando para o novo chamado")
        void deveDevolverLocationNaCriacao() throws Exception {
            MvcResult resultado = mockMvc.perform(post("/api/chamados").header(HEADER_USUARIO, ana.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new NovoChamadoRequest("  Mouse com defeito  ",
                                    "  O clique direito nao responde mais.  ", categoria.getId(), Prioridade.BAIXA))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.titulo").value("Mouse com defeito"))
                    .andExpect(jsonPath("$.descricao").value("O clique direito nao responde mais."))
                    .andExpect(jsonPath("$.comentarios").isEmpty())
                    .andReturn();

            long id = corpoDe(resultado).get("id").asLong();
            assertThat(resultado.getResponse().getHeader("Location")).isEqualTo("/api/chamados/" + id);
        }

        @Test
        @DisplayName("campos nulos sao omitidos: chamado ABERTO nao traz atendente, resolvidoEm, fechadoEm")
        void deveOmitirCamposNulos() throws Exception {
            long id = abrirChamado();

            JsonNode detalhe = corpoDe(mockMvc.perform(get("/api/chamados/{id}", id)
                    .header(HEADER_USUARIO, ana.getId())).andReturn());

            assertThat(campos(detalhe)).containsExactlyInAnyOrder(
                    "id", "titulo", "descricao", "status", "prioridade", "categoria", "solicitante",
                    "criadoEm", "atualizadoEm", "prazoSla", "foraDoSla", "comentarios");
        }

        @Test
        @DisplayName("detalhe de chamado FECHADO traz todos os campos, e os aninhados no formato atual")
        void deveFixarOFormatoCompletoDoDetalhe() throws Exception {
            long id = abrirChamado();
            assumir(id, bruno);
            mockMvc.perform(post("/api/chamados/{id}/comentarios", id).header(HEADER_USUARIO, bruno.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new NovoComentarioRequest("Toner trocado."))))
                    .andExpect(status().isCreated());
            assertThat(alterarStatus(id, bruno, StatusChamado.RESOLVIDO).getResponse().getStatus()).isEqualTo(200);
            assertThat(alterarStatus(id, ana, StatusChamado.FECHADO).getResponse().getStatus()).isEqualTo(200);

            JsonNode detalhe = corpoDe(mockMvc.perform(get("/api/chamados/{id}", id)
                    .header(HEADER_USUARIO, ana.getId())).andReturn());

            assertThat(campos(detalhe)).containsExactlyInAnyOrder(
                    "id", "titulo", "descricao", "status", "prioridade", "categoria", "solicitante",
                    "atendente", "criadoEm", "atualizadoEm", "resolvidoEm", "fechadoEm", "prazoSla",
                    "foraDoSla", "comentarios");
            assertThat(campos(detalhe.get("categoria")))
                    .containsExactlyInAnyOrder("id", "nome", "slaHoras", "ativa");
            // Usuario embutido nunca expoe e-mail.
            assertThat(campos(detalhe.get("solicitante"))).containsExactlyInAnyOrder("id", "nome", "perfil");
            assertThat(campos(detalhe.get("atendente"))).containsExactlyInAnyOrder("id", "nome", "perfil");
            assertThat(campos(detalhe.get("comentarios").get(0)))
                    .containsExactlyInAnyOrder("id", "autor", "texto", "criadoEm");
            assertThat(campos(detalhe.get("comentarios").get(0).get("autor")))
                    .containsExactlyInAnyOrder("id", "nome", "perfil");
            // Instantes em ISO-8601 UTC, nunca epoch numerico.
            assertThat(detalhe.get("criadoEm").asText()).endsWith("Z");
            assertThat(detalhe.get("prazoSla").asText()).endsWith("Z");
        }

        @Test
        @DisplayName("listagem: envelope do Spring Data e item de resumo sem descricao nem comentarios")
        void deveFixarOFormatoDaListagem() throws Exception {
            long id = abrirChamado();
            assumir(id, bruno);

            JsonNode pagina = corpoDe(mockMvc.perform(get("/api/chamados")
                    .header(HEADER_USUARIO, carla.getId())).andReturn());

            // Chaves que o chamadosStore do frontend le.
            assertThat(campos(pagina)).contains("content", "totalElements", "totalPages", "number", "size");
            assertThat(campos(pagina.get("content").get(0))).containsExactlyInAnyOrder(
                    "id", "titulo", "status", "prioridade", "categoria", "solicitante", "atendente",
                    "criadoEm", "prazoSla", "foraDoSla");
        }

        @Test
        @DisplayName("ordem padrao da listagem e criadoEm decrescente, 20 por pagina")
        void deveOrdenarPorCriacaoDecrescentePorPadrao() throws Exception {
            abrirChamado();
            abrirChamado();
            abrirChamado();

            JsonNode pagina = corpoDe(mockMvc.perform(get("/api/chamados")
                    .header(HEADER_USUARIO, carla.getId())).andReturn());

            assertThat(pagina.get("size").asInt()).isEqualTo(20);
            // Compara os instantes, nao os ids: dois chamados podem nascer no mesmo instante.
            List<Instant> criacoes = new ArrayList<>();
            pagina.get("content").forEach(item -> criacoes.add(Instant.parse(item.get("criadoEm").asText())));
            assertThat(criacoes).hasSize(3).isSortedAccordingTo(Comparator.reverseOrder());
        }

        @Test
        @DisplayName("prazoSla e criadoEm + slaHoras da categoria")
        void deveCalcularOPrazoNaResposta() throws Exception {
            long id = abrirChamado();

            JsonNode detalhe = corpoDe(mockMvc.perform(get("/api/chamados/{id}", id)
                    .header(HEADER_USUARIO, ana.getId())).andReturn());

            Instant criado = Instant.parse(detalhe.get("criadoEm").asText());
            Instant prazo = Instant.parse(detalhe.get("prazoSla").asText());
            assertThat(Duration.between(criado, prazo)).hasHours(4);
            assertThat(detalhe.get("foraDoSla").asBoolean()).isFalse();
        }
    }

    // ================================================================== atribuicao

    @Nested
    @DisplayName("atribuicao de atendente")
    class Atribuicao {

        @Test
        @DisplayName("o admin tambem pode assumir, e vira o atendente do chamado")
        void deveTornarOAdminAtendenteAoAssumir() throws Exception {
            long id = abrirChamado();

            mockMvc.perform(patch("/api/chamados/{id}/assumir", id).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.atendente.id").value(carla.getId()))
                    .andExpect(jsonPath("$.atendente.perfil").value("ADMIN"));
        }

        @Test
        @DisplayName("chamado assumido nao troca de dono: outro atendente recebe 422 ao assumir")
        void deveManterOPrimeiroAtendente() throws Exception {
            long id = abrirChamado();
            assumir(id, bruno);

            mockMvc.perform(patch("/api/chamados/{id}/assumir", id).header(HEADER_USUARIO, eva.getId()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").value("TRANSICAO_INVALIDA"));
            mockMvc.perform(get("/api/chamados/{id}", id).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(jsonPath("$.atendente.id").value(bruno.getId()));
        }

        @Test
        @DisplayName("outro atendente ve e comenta o chamado, mas nao resolve (403)")
        void deveRestringirOResolverAoAtendenteResponsavel() throws Exception {
            long id = abrirChamado();
            assumir(id, bruno);

            mockMvc.perform(get("/api/chamados/{id}", id).header(HEADER_USUARIO, eva.getId()))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/chamados/{id}/comentarios", id).header(HEADER_USUARIO, eva.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new NovoComentarioRequest("Posso ajudar se precisar."))))
                    .andExpect(status().isCreated());
            assertThat(alterarStatus(id, eva, StatusChamado.RESOLVIDO).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("depois de reaberto, o chamado volta para o mesmo atendente, que resolve de novo")
        void deveDevolverOChamadoReabertoAoMesmoAtendente() throws Exception {
            long id = abrirChamado();
            assumir(id, bruno);
            alterarStatus(id, bruno, StatusChamado.RESOLVIDO);
            alterarStatus(id, ana, StatusChamado.EM_ATENDIMENTO);

            mockMvc.perform(patch("/api/chamados/{id}/assumir", id).header(HEADER_USUARIO, eva.getId()))
                    .andExpect(status().isUnprocessableEntity());
            assertThat(alterarStatus(id, eva, StatusChamado.RESOLVIDO).getResponse().getStatus()).isEqualTo(403);

            MvcResult resolvidoDeNovo = alterarStatus(id, bruno, StatusChamado.RESOLVIDO);
            assertThat(resolvidoDeNovo.getResponse().getStatus()).isEqualTo(200);
            assertThat(corpoDe(resolvidoDeNovo).get("resolvidoEm").isNull()).isFalse();
        }

        @Test
        @DisplayName("filtro meus=true devolve a fila de quem atende, nao a de quem abriu")
        void deveFiltrarAFilaPessoalDoAtendente() throws Exception {
            long doBruno = abrirChamado();
            long daEva = abrirChamado();
            assumir(doBruno, bruno);
            assumir(daEva, eva);

            mockMvc.perform(get("/api/chamados").param("meus", "true").header(HEADER_USUARIO, bruno.getId()))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(doBruno));
        }
    }
}
