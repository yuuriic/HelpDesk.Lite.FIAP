package br.com.helpdesk.integration;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.support.UsuarioBuilder;
import br.com.helpdesk.web.dto.request.LoginRequest;
import br.com.helpdesk.web.dto.request.UsuarioRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dado pessoal fora das rotas publicas e do log (Fase 3 do plano).
 *
 * <p>Substitui os testes de defeito SEG-03 e SEG-04 de DefeitosConhecidosIT (removida na Fase 9, com o ultimo defeito tratado):</p>
 * <ul>
 *   <li>SEG-03 -- as duas rotas publicas do login simulado devolvem so o resumo do usuario
 *       (id, nome, perfil). O e-mail continua na listagem administrativa, que exige ADMIN;</li>
 *   <li>SEG-04 -- o e-mail duplicado nao volta no corpo do 422 nem vai para o log WARN.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Dados pessoais nas rotas publicas e no log (integracao)")
class DadosPessoaisIT {

    private static final String HEADER_USUARIO = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario ana;
    private Usuario carla;

    @BeforeEach
    void criarUsuarios() {
        ana = usuarioRepository.save(UsuarioBuilder.solicitante().comEmail("ana@pessoais.com").buildSemId());
        carla = usuarioRepository.save(UsuarioBuilder.admin().comEmail("carla@pessoais.com").buildSemId());
    }

    private static List<String> campos(JsonNode no) {
        List<String> nomes = new ArrayList<>();
        no.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }

    private JsonNode corpoDe(MvcResult resultado) throws Exception {
        return objectMapper.readTree(resultado.getResponse().getContentAsString());
    }

    // ================================================================== SEG-03

    @Test
    @DisplayName("usuarios-disponiveis (publica) traz so id, nome e perfil de cada usuario")
    void deveListarSoOResumoNaRotaPublica() throws Exception {
        JsonNode lista = corpoDe(mockMvc.perform(get("/api/auth/usuarios-disponiveis"))
                .andExpect(status().isOk()).andReturn());

        assertThat(lista).isNotEmpty();
        lista.forEach(usuario -> assertThat(campos(usuario)).containsExactlyInAnyOrder("id", "nome", "perfil"));
        assertThat(lista.toString()).doesNotContain("@pessoais.com");
    }

    @Test
    @DisplayName("login (publico) devolve so id, nome e perfil: informar um id nao revela o e-mail do dono")
    void deveDevolverSoOResumoNoLogin() throws Exception {
        JsonNode usuario = corpoDe(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(carla.getId()))))
                .andExpect(status().isOk()).andReturn());

        assertThat(campos(usuario)).containsExactlyInAnyOrder("id", "nome", "perfil");
        assertThat(usuario.get("perfil").asText()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("a listagem administrativa (so ADMIN) continua com e-mail")
    void deveManterOEmailNaListagemAdministrativa() throws Exception {
        mockMvc.perform(get("/api/usuarios").header(HEADER_USUARIO, carla.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + ana.getId() + ")].email").value("ana@pessoais.com"));
    }

    // ================================================================== SEG-04

    @Test
    @DisplayName("e-mail duplicado na criacao: 422 sem o e-mail no corpo nem no log")
    void naoDeveExporOEmailDuplicadoNaCriacao(CapturedOutput saida) throws Exception {
        mockMvc.perform(post("/api/usuarios").header(HEADER_USUARIO, carla.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UsuarioRequest("Outra Ana", "ana@pessoais.com", Perfil.SOLICITANTE))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"))
                .andExpect(jsonPath("$.mensagem").value("Ja existe um usuario com este e-mail"));

        assertThat(saida.getOut())
                .contains("Requisicao rejeitada com 422 em POST /api/usuarios")
                .doesNotContain("ana@pessoais.com");
    }

    @Test
    @DisplayName("e-mail duplicado na atualizacao: 422 sem o e-mail no corpo nem no log")
    void naoDeveExporOEmailDuplicadoNaAtualizacao(CapturedOutput saida) throws Exception {
        mockMvc.perform(put("/api/usuarios/{id}", carla.getId()).header(HEADER_USUARIO, carla.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UsuarioRequest(carla.getNome(), "ana@pessoais.com", Perfil.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensagem").value("Ja existe um usuario com este e-mail"));

        assertThat(saida.getOut()).doesNotContain("ana@pessoais.com");
    }
}
