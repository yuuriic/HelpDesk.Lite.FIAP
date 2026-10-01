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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Perfil prod (SEG-01, SEG-02): sem login simulado, sem console H2, sem Swagger e com o
 * Actuator reduzido a health (sem detalhes) e prometheus.
 *
 * <p>{@code ddl-auto} e o banco sao trocados so para o H2 de teste ter schema -- o prod real
 * valida o schema de um banco externo. Que o prod nao sobe com o login simulado ligado e
 * provado em {@code AutenticacaoSimuladaConfigTest}.</p>
 */
// Sem isto o @SpringBootTest desliga a exportacao de metricas e o /actuator/prometheus some.
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:helpdesk-perfil-prod;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("prod")
@DisplayName("Configuracao do perfil prod (servidor real)")
class ConfiguracaoProdIT {

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("X-User-Id nao autentica: toda rota protegida responde 401")
    void naoDeveAceitarOLoginSimulado() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "1");
        ResponseEntity<String> resposta = http.exchange("/api/usuarios", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(401);
        assertThat(resposta.getBody()).contains("NAO_AUTENTICADO");
    }

    @Test
    @DisplayName("as rotas publicas do login simulado nao existem")
    void naoDeveRegistrarAsRotasDoLoginSimulado() {
        assertThat(http.getForEntity("/api/auth/usuarios-disponiveis", String.class).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("Actuator so com health sem detalhes e prometheus; H2 e Swagger desligados")
    void deveManterASuperficieMinima() {
        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).doesNotContain("components");
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode().value()).isEqualTo(200);

        for (String fechado : List.of("/actuator/env", "/actuator/loggers", "/actuator/metrics",
                "/h2/", "/swagger-ui.html", "/v3/api-docs")) {
            assertThat(http.getForEntity(fechado, String.class).getStatusCode().value()).as(fechado).isEqualTo(404);
        }
    }
}
