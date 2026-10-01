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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuracao base -- nenhum perfil ativo, como num {@code java -jar} sem parametros (SEG-02).
 *
 * <p>Antes, a ausencia de perfil caia no {@code dev}: Actuator com {@code env} e
 * {@code loggers}, console H2 e Swagger abertos, e o login simulado aceitando qualquer
 * {@code X-User-Id}. Agora a base e a configuracao mais fechada.</p>
 *
 * <p>Servidor real em porta aleatoria: o console H2 e um servlet proprio, fora do alcance do
 * MockMvc. O banco e separado para nao interferir nas outras suites.</p>
 */
// Sem isto o @SpringBootTest desliga a exportacao de metricas e o /actuator/prometheus some.
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:helpdesk-sem-perfil;DB_CLOSE_DELAY=-1")
@DisplayName("Configuracao sem perfil ativo (servidor real)")
class ConfiguracaoSemPerfilIT {

    @Autowired
    private TestRestTemplate http;

    private ResponseEntity<String> getComUsuario(String caminho, String usuarioId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", usuarioId);
        return http.exchange(caminho, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("Actuator expoe so health (sem detalhes) e prometheus")
    void deveExporSoHealthEPrometheus() {
        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("\"status\":\"UP\"").doesNotContain("components");

        assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode().value()).isEqualTo(200);

        for (String fechado : List.of("/actuator/env", "/actuator/loggers", "/actuator/metrics", "/actuator/info")) {
            assertThat(http.getForEntity(fechado, String.class).getStatusCode().value()).as(fechado).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("console H2 e Swagger ficam desligados")
    void deveDesligarConsoleH2ESwagger() {
        for (String fechado : List.of("/h2/", "/swagger-ui.html", "/v3/api-docs")) {
            assertThat(http.getForEntity(fechado, String.class).getStatusCode().value()).as(fechado).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("login simulado desligado: X-User-Id nao autentica e as rotas de auth nao existem")
    void deveDesligarOLoginSimulado() {
        ResponseEntity<String> protegida = getComUsuario("/api/chamados", "1");
        assertThat(protegida.getStatusCode().value()).isEqualTo(401);
        assertThat(protegida.getBody())
                .contains("NAO_AUTENTICADO")
                .contains("Autenticacao nao disponivel neste ambiente");

        assertThat(http.getForEntity("/api/auth/usuarios-disponiveis", String.class).getStatusCode().value())
                .isEqualTo(404);
    }
}
