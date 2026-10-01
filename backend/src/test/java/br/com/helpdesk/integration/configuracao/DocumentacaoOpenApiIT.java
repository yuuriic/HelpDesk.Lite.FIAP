package br.com.helpdesk.integration.configuracao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A especificacao OpenAPI descreve o contrato real (Fase 9).
 *
 * <p>Antes, o {@code UsuarioAtual} -- preenchido pelo filtro a partir do {@code X-User-Id} -- saia
 * como parametro de query em 16 operacoes, com a entidade {@code Usuario} como schema. O que o
 * cliente envia de fato, o header, nao aparecia. Rotas e schemas de resposta nao podem mudar: a
 * correcao e so de documentacao.</p>
 *
 * <p>Mesma configuracao do {@link ConfiguracaoDevIT}: reaproveita o contexto (o Swagger so existe
 * no perfil dev).</p>
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:helpdesk-perfil-dev;DB_CLOSE_DELAY=-1")
@ActiveProfiles("dev")
@DisplayName("Documentacao OpenAPI (servidor real)")
class DocumentacaoOpenApiIT {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode especificacao;

    @BeforeEach
    void lerEspecificacao() throws Exception {
        especificacao = objectMapper.readTree(http.getForObject("/v3/api-docs", String.class));
    }

    private List<JsonNode> operacoes() {
        List<JsonNode> todas = new ArrayList<>();
        especificacao.get("paths").forEach(caminho -> caminho.forEach(todas::add));
        return todas;
    }

    private static Set<String> nomes(JsonNode objeto) {
        Set<String> nomes = new TreeSet<>();
        objeto.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }

    @Test
    @DisplayName("nenhuma operacao documenta o UsuarioAtual, e a entidade Usuario nao vira schema")
    void naoDeveDocumentarOParametroInterno() {
        for (JsonNode operacao : operacoes()) {
            operacao.path("parameters").forEach(parametro ->
                    assertThat(parametro.get("name").asText()).as(operacao.path("operationId").asText())
                            .isNotEqualTo("usuarioAtual"));
        }
        assertThat(nomes(especificacao.at("/components/schemas"))).doesNotContain("Usuario", "UsuarioAtual");
    }

    @Test
    @DisplayName("o header X-User-Id e documentado como esquema de autenticacao, exigido fora de /api/auth")
    void deveDocumentarOHeaderDoLoginSimulado() {
        JsonNode esquema = especificacao.at("/components/securitySchemes/loginSimulado");
        assertThat(esquema.get("type").asText()).isEqualTo("apiKey");
        assertThat(esquema.get("in").asText()).isEqualTo("header");
        assertThat(esquema.get("name").asText()).isEqualTo("X-User-Id");
        assertThat(especificacao.at("/security/0").has("loginSimulado")).isTrue();

        for (String publica : List.of("/api/auth/login", "/api/auth/usuarios-disponiveis")) {
            especificacao.at("/paths/" + publica.replace("/", "~1")).forEach(operacao ->
                    assertThat(operacao.get("security")).as("%s e publica", publica).isEmpty());
        }
    }

    @Test
    @DisplayName("rotas e schemas de resposta continuam os mesmos")
    void devePreservarRotasESchemas() {
        assertThat(nomes(especificacao.get("paths"))).containsExactlyInAnyOrder(
                "/api/auth/usuarios-disponiveis", "/api/auth/login",
                "/api/chamados", "/api/chamados/{id}", "/api/chamados/{id}/assumir",
                "/api/chamados/{id}/status", "/api/chamados/{id}/comentarios",
                "/api/categorias", "/api/categorias/{id}",
                "/api/usuarios", "/api/usuarios/{id}",
                "/api/dashboard/resumo");
        assertThat(nomes(especificacao.at("/components/schemas"))).contains(
                "ChamadoResponse", "ChamadoResumoResponse", "ComentarioResponse", "CategoriaResponse",
                "UsuarioResponse", "UsuarioResumoResponse", "DashboardResumoResponse",
                "NovoChamadoRequest", "AlterarStatusRequest", "CategoriaRequest", "UsuarioRequest",
                "NovoComentarioRequest", "LoginRequest");
        // Os parametros de verdade continuam: filtros e paginacao da listagem.
        Set<String> parametrosDaListagem = new TreeSet<>();
        especificacao.at("/paths/~1api~1chamados/get/parameters")
                .forEach(parametro -> parametrosDaListagem.add(parametro.get("name").asText()));
        assertThat(parametrosDaListagem).contains("status", "prioridade", "categoriaId", "meus");
    }
}
