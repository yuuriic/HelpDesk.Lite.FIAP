package br.com.helpdesk.integration.configuracao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed de demonstracao ({@code data.sql}) e as sequencias de id (CON-05, Fase 4).
 *
 * <p>O seed insere ids explicitos, o que nao avanca as colunas IDENTITY; o script reposiciona
 * cada sequencia em {@code MAX(id) + 1}. O risco era a sequencia ficar atras do seed e o
 * primeiro cadastro feito pela interface colidir com uma chave existente.</p>
 *
 * <p>Mesma configuracao do {@link ConfiguracaoDevIT} de proposito: reaproveita o contexto que
 * ja sobe com o seed.</p>
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:helpdesk-perfil-dev;DB_CLOSE_DELAY=-1")
@ActiveProfiles("dev")
@DisplayName("Seed de demonstracao e sequencias de id (servidor real)")
class SeedDemonstracaoIT {

    /** Maiores ids inseridos pelo data.sql. */
    private static final long ULTIMO_USUARIO = 3;
    private static final long ULTIMA_CATEGORIA = 4;
    private static final long ULTIMO_CHAMADO = 8;
    private static final long ULTIMO_COMENTARIO = 6;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode criar(String caminho, String usuarioId, String corpo) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", usuarioId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resposta = http.postForEntity(caminho, new HttpEntity<>(corpo, headers), String.class);

        assertThat(resposta.getStatusCode().value()).as("POST %s: %s", caminho, resposta.getBody()).isEqualTo(201);
        return objectMapper.readTree(resposta.getBody());
    }

    @Test
    @DisplayName("cadastros feitos depois do seed recebem ids novos, sem colidir com os do data.sql")
    void deveContinuarAsSequenciasDepoisDoSeed() throws Exception {
        JsonNode usuario = criar("/api/usuarios", "3",
                "{\"nome\":\"Davi Novo\",\"email\":\"davi@seed.com\",\"perfil\":\"SOLICITANTE\"}");
        JsonNode categoria = criar("/api/categorias", "3", "{\"nome\":\"Seed - Nova\",\"slaHoras\":12}");
        JsonNode chamado = criar("/api/chamados", "1", "{\"titulo\":\"Teclado falhando\","
                + "\"descricao\":\"Algumas teclas nao respondem.\",\"categoriaId\":1,\"prioridade\":\"BAIXA\"}");
        JsonNode comentario = criar("/api/chamados/" + chamado.get("id").asLong() + "/comentarios", "1",
                "{\"texto\":\"Acontece desde ontem.\"}");

        assertThat(usuario.get("id").asLong()).isGreaterThan(ULTIMO_USUARIO);
        assertThat(categoria.get("id").asLong()).isGreaterThan(ULTIMA_CATEGORIA);
        assertThat(chamado.get("id").asLong()).isGreaterThan(ULTIMO_CHAMADO);
        assertThat(comentario.get("id").asLong()).isGreaterThan(ULTIMO_COMENTARIO);
    }
}
