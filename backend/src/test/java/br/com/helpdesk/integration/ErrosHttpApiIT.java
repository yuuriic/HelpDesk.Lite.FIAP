package br.com.helpdesk.integration;

import br.com.helpdesk.domain.exception.NaoAutenticadoException;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.support.UsuarioBuilder;
import br.com.helpdesk.web.dto.request.LoginRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato de erros HTTP pela pilha real (filtros + DispatcherServlet + handler).
 *
 * <p>Substitui os testes de defeito EXC-01, REG-04 e SEG-06 de DefeitosConhecidosIT (removida na Fase 9, com o ultimo defeito tratado)
 * (Fase 1 do plano de modernizacao): erro de cliente nunca vira 500, nunca e logado em
 * ERROR e nunca expoe detalhe interno no corpo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Contrato de erros HTTP (integracao)")
class ErrosHttpApiIT {

    private static final String HEADER_USUARIO = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario carla;
    private Usuario inativo;

    @BeforeEach
    void criarUsuarios() {
        carla = usuarioRepository.save(UsuarioBuilder.admin().comEmail("carla@erros.com").buildSemId());
        inativo = usuarioRepository.save(
                UsuarioBuilder.solicitante().comEmail("inativo@erros.com").inativo().buildSemId());
    }

    /** O corpo de erro tem exatamente os quatro campos padrao e nada de detalhe interno. */
    private void assertCorpoDeErroPadrao(ResultActions resposta) throws Exception {
        MvcResult resultado = resposta.andReturn();
        JsonNode corpo = objectMapper.readTree(resultado.getResponse().getContentAsString());

        List<String> campos = new ArrayList<>();
        corpo.fieldNames().forEachRemaining(campos::add);
        assertThat(campos).containsExactlyInAnyOrder("timestamp", "status", "erro", "mensagem");
        assertThat(corpo.get("status").asInt()).isEqualTo(resultado.getResponse().getStatus());
        assertThat(corpo.get("mensagem").asText())
                .doesNotContain("Exception", "static resource", "br.com.helpdesk", "for type");
    }

    // ================================================================== EXC-01

    @Nested
    @DisplayName("erros de cliente do Spring MVC (EXC-01)")
    class ErrosDeCliente {

        @Test
        @DisplayName("rota inexistente sob /api devolve 404 RECURSO_NAO_ENCONTRADO")
        void deveDevolver404ParaRotaInexistente() throws Exception {
            ResultActions resposta = mockMvc.perform(get("/api/nao-existe").header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.erro").value("RECURSO_NAO_ENCONTRADO"))
                    .andExpect(jsonPath("$.mensagem").value("Recurso nao encontrado"));
            assertCorpoDeErroPadrao(resposta);
        }

        @Test
        @DisplayName("rota inexistente fora de /api tambem devolve 404 no formato padrao")
        void deveDevolver404ForaDaApi() throws Exception {
            assertCorpoDeErroPadrao(mockMvc.perform(get("/nada-aqui"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.erro").value("RECURSO_NAO_ENCONTRADO")));
        }

        @Test
        @DisplayName("sem X-User-Id, rota inexistente sob /api continua 401: autenticacao vem antes")
        void deveAutenticarAntesDeResolverARota() throws Exception {
            mockMvc.perform(get("/api/nao-existe"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.erro").value("NAO_AUTENTICADO"));
        }

        @Test
        @DisplayName("metodo HTTP nao suportado devolve 405 com o header Allow")
        void deveDevolver405ComAllow() throws Exception {
            ResultActions resposta = mockMvc.perform(delete("/api/chamados/{id}", 1).header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string("Allow", containsString("GET")))
                    .andExpect(jsonPath("$.erro").value("METODO_NAO_SUPORTADO"));
            assertCorpoDeErroPadrao(resposta);
        }

        @Test
        @DisplayName("Content-Type nao suportado devolve 415 com o header Accept")
        void deveDevolver415() throws Exception {
            ResultActions resposta = mockMvc.perform(post("/api/chamados").header(HEADER_USUARIO, carla.getId())
                            .contentType(MediaType.TEXT_PLAIN).content("texto"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(header().string("Accept", containsString("application/json")))
                    .andExpect(jsonPath("$.erro").value("MIDIA_NAO_SUPORTADA"));
            assertCorpoDeErroPadrao(resposta);
        }

        @Test
        @DisplayName("ordenacao por campo inexistente devolve 400 VALIDACAO citando o campo")
        void deveDevolver400ParaOrdenacaoInvalida() throws Exception {
            ResultActions resposta = mockMvc.perform(get("/api/chamados").param("sort", "naoExiste,asc")
                            .header(HEADER_USUARIO, carla.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.erro").value("VALIDACAO"))
                    .andExpect(jsonPath("$.mensagem").value("Campo de ordenacao invalido: naoExiste"));
            assertCorpoDeErroPadrao(resposta);
        }

        @Test
        @DisplayName("ordenacoes validas, inclusive por campo aninhado, continuam funcionando")
        void deveManterAsOrdenacoesValidas() throws Exception {
            for (String sort : List.of("criadoEm,desc", "prioridade,asc", "titulo", "categoria.nome,asc")) {
                mockMvc.perform(get("/api/chamados").param("sort", sort).header(HEADER_USUARIO, carla.getId()))
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("erro de cliente e logado em WARN, sem a linha de ERROR do erro interno")
        void deveLogarErroDeClienteEmWarn(CapturedOutput saida) throws Exception {
            mockMvc.perform(get("/api/nao-existe").header(HEADER_USUARIO, carla.getId()));
            mockMvc.perform(delete("/api/chamados/{id}", 1).header(HEADER_USUARIO, carla.getId()));
            mockMvc.perform(get("/api/chamados").param("sort", "naoExiste").header(HEADER_USUARIO, carla.getId()));

            assertThat(saida.getOut())
                    .contains("Requisicao rejeitada com 404 em GET /api/nao-existe")
                    .contains("Requisicao rejeitada com 405 em DELETE /api/chamados/1")
                    .contains("Requisicao rejeitada com 400 em GET /api/chamados")
                    .doesNotContain("Erro interno em");
        }
    }

    // ================================================================== REG-04 / SEG-06

    @Nested
    @DisplayName("nao autenticado: login e filter respondem igual (REG-04, SEG-06)")
    class NaoAutenticado {

        @Test
        @DisplayName("login de usuario inexistente devolve 401 NAO_AUTENTICADO")
        void deveRecusarLoginDeInexistenteCom401() throws Exception {
            assertCorpoDeErroPadrao(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(999_999L))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.erro").value("NAO_AUTENTICADO"))
                    .andExpect(jsonPath("$.mensagem").value(NaoAutenticadoException.MENSAGEM_PADRAO)));
        }

        @Test
        @DisplayName("login de usuario inativo devolve 401 com a mesma mensagem do inexistente (regra I9)")
        void deveRecusarLoginDeInativoCom401() throws Exception {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(inativo.getId()))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.erro").value("NAO_AUTENTICADO"))
                    .andExpect(jsonPath("$.mensagem").value(NaoAutenticadoException.MENSAGEM_PADRAO));
        }

        @Test
        @DisplayName("no filter, inexistente e inativo recebem a mesma mensagem: nao revela quais ids existem")
        void deveResponderIgualParaInexistenteEInativoNoFilter() throws Exception {
            for (String id : List.of("999999", String.valueOf(inativo.getId()))) {
                mockMvc.perform(get("/api/chamados").header(HEADER_USUARIO, id))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.mensagem").value(NaoAutenticadoException.MENSAGEM_PADRAO));
            }
        }

        @Test
        @DisplayName("o motivo real do 401 continua no log WARN, para o suporte")
        void deveManterOMotivoNoLog(CapturedOutput saida) throws Exception {
            mockMvc.perform(get("/api/chamados").header(HEADER_USUARIO, "999999"));
            mockMvc.perform(get("/api/chamados").header(HEADER_USUARIO, inativo.getId()));

            assertThat(saida.getOut())
                    .contains("Requisicao nao autenticada: usuario 999999 nao encontrado")
                    .contains("Requisicao nao autenticada: usuario " + inativo.getId() + " esta inativo");
        }

        @Test
        @DisplayName("login de usuario ativo continua funcionando")
        void deveManterOLoginDeUsuarioAtivo() throws Exception {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(carla.getId()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.perfil").value("ADMIN"));
        }
    }
}
