package br.com.helpdesk.integration.configuracao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nomes e tipos das metricas de negocio como o Prometheus as ve (Fase 8).
 *
 * <p>O nome que importa e o do {@code /actuator/prometheus}, nao o do Micrometer: o registro do
 * Prometheus acrescenta a unidade-base aos timers. Com o timer chamado
 * {@code helpdesk_tempo_resolucao_segundos}, a serie saia como
 * {@code helpdesk_tempo_resolucao_segundos_seconds_*}.</p>
 *
 * <p>Mesma configuracao do {@link ConfiguracaoDevIT} de proposito: reaproveita o contexto (perfil
 * dev com o seed, exportacao de metricas ligada). Cada teste cria o proprio chamado, para nao
 * depender da ordem dos testes nem mexer no seed que as outras classes leem.</p>
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:helpdesk-perfil-dev;DB_CLOSE_DELAY=-1")
@ActiveProfiles("dev")
@DisplayName("Metricas de negocio expostas no Prometheus (servidor real)")
class MetricasExpostasIT {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ObjectMapper objectMapper;

    /** O cliente padrao (HttpURLConnection) nao aceita PATCH; o do JDK aceita. */
    @BeforeEach
    void usarClienteHttpDoJdk() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    private String enviar(HttpMethod metodo, String caminho, String usuarioId, String corpo) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", usuarioId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(caminho, metodo, new HttpEntity<>(corpo, headers), String.class).getBody();
    }

    /** Abre, assume e resolve um chamado novo: incrementa os dois contadores e o timer. */
    private void resolverUmChamadoNovo() throws Exception {
        // Seed: Ana (1) solicitante, Bruno (2) atendente, categoria 1 ("TI - Infraestrutura") ativa.
        String criado = enviar(HttpMethod.POST, "/api/chamados", "1", """
                {"titulo":"Teclado falhando","descricao":"Algumas teclas nao respondem.",
                 "categoriaId":1,"prioridade":"BAIXA"}""");
        long id = objectMapper.readTree(criado).get("id").asLong();
        enviar(HttpMethod.PATCH, "/api/chamados/" + id + "/assumir", "2", null);
        enviar(HttpMethod.PATCH, "/api/chamados/" + id + "/status", "2", "{\"status\":\"RESOLVIDO\"}");
    }

    @Test
    @DisplayName("o timer de resolucao sai como helpdesk_tempo_resolucao_seconds, sem a unidade duplicada")
    void deveExporOTimerSemUnidadeDuplicada() throws Exception {
        resolverUmChamadoNovo();

        String prometheus = http.getForObject("/actuator/prometheus", String.class);

        assertThat(prometheus)
                .contains("# TYPE helpdesk_tempo_resolucao_seconds summary")
                .containsPattern("(?m)^helpdesk_tempo_resolucao_seconds_count\\{.*categoria=\"TI - Infraestrutura\".*} \\d+")
                .contains("helpdesk_tempo_resolucao_seconds_sum{")
                .contains("helpdesk_tempo_resolucao_seconds_max{")
                .doesNotContain("_segundos");
    }

    @Test
    @DisplayName("contadores e gauges de negocio continuam com os nomes e tipos documentados")
    void deveManterContadoresEGauges() throws Exception {
        resolverUmChamadoNovo();

        String prometheus = http.getForObject("/actuator/prometheus", String.class);

        assertThat(prometheus)
                .contains("# TYPE helpdesk_chamados_abertos_total counter")
                .contains("# TYPE helpdesk_chamados_transicoes_total counter")
                .contains("# TYPE helpdesk_chamados_por_status gauge")
                .contains("# TYPE helpdesk_chamados_fora_sla gauge")
                .containsPattern("(?m)^helpdesk_chamados_transicoes_total\\{.*de=\"ABERTO\".*para=\"EM_ATENDIMENTO\".*}")
                .containsPattern("(?m)^helpdesk_chamados_por_status\\{.*status=\"ABERTO\".*}")
                .doesNotContain("_total_total");
    }
}
