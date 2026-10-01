package br.com.helpdesk.integration.configuracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Perfil dev -- o ambiente do MVP, usado pelo {@code ./mvnw spring-boot:run} (SEG-02).
 *
 * <p>Continua com tudo o que a demonstracao usa (Swagger, console H2, health detalhado,
 * metricas, login simulado com o seed), mas sem {@code env} e {@code loggers}: o primeiro
 * lista a configuracao inteira e o segundo aceita POST para trocar o nivel de log, ambos sem
 * autenticacao.</p>
 */
// Sem isto o @SpringBootTest desliga a exportacao de metricas e o /actuator/prometheus some.
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:helpdesk-perfil-dev;DB_CLOSE_DELAY=-1")
@ActiveProfiles("dev")
@DisplayName("Configuracao do perfil dev (servidor real)")
class ConfiguracaoDevIT {

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("env e loggers nao sao expostos, nem para leitura nem para alteracao")
    void naoDeveExporEnvNemLoggers() {
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.getForEntity("/actuator/loggers", String.class).getStatusCode().value()).isEqualTo(404);

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> trocarNivel = http.exchange("/actuator/loggers/br.com.helpdesk", HttpMethod.POST,
                new HttpEntity<>("{\"configuredLevel\":\"TRACE\"}", json), String.class);
        assertThat(trocarNivel.getStatusCode().is2xxSuccessful()).as("POST em loggers").isFalse();
    }

    @Test
    @DisplayName("health detalhado, info, metrics e prometheus continuam disponiveis")
    void deveManterOsEndpointsDaDemonstracao() {
        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("components");

        for (String aberto : List.of("/actuator/info", "/actuator/metrics", "/actuator/prometheus")) {
            assertThat(http.getForEntity(aberto, String.class).getStatusCode().value()).as(aberto).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Swagger e console H2 continuam disponiveis")
    void deveManterSwaggerEConsoleH2() {
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/h2/", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("login simulado funciona com o seed: lista os usuarios e aceita o X-User-Id")
    void deveManterOLoginSimulado() {
        ResponseEntity<String> disponiveis = http.getForEntity("/api/auth/usuarios-disponiveis", String.class);
        assertThat(disponiveis.getStatusCode().value()).isEqualTo(200);
        assertThat(disponiveis.getBody()).contains("Ana Solicitante").doesNotContain("@empresa.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "1");
        assertThat(http.exchange("/api/chamados", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value()).isEqualTo(200);
    }
}
