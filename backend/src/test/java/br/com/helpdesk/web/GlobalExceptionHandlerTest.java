package br.com.helpdesk.web;

import br.com.helpdesk.web.exception.ErroResponse;
import br.com.helpdesk.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ramos do GlobalExceptionHandler que a API do HelpDesk nao consegue provocar por HTTP:
 * 5xx produzido pela propria classe base do Spring e status 4xx sem codigo dedicado.
 *
 * <p>O contrato que importa aqui e o mesmo de todo erro: o {@code status} do corpo e sempre
 * igual ao status HTTP, e 5xx nunca expoe detalhe.</p>
 */
@DisplayName("GlobalExceptionHandler -- ramos da classe base (unitario)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest requisicao(String metodo, String uri) {
        return new ServletWebRequest(new MockHttpServletRequest(metodo, uri));
    }

    private static ErroResponse corpo(ResponseEntity<Object> resposta) {
        return (ErroResponse) resposta.getBody();
    }

    @Test
    @DisplayName("5xx da classe base (503) vira ERRO_INTERNO com o status do corpo igual ao HTTP")
    void deveManterOStatusDoCorpoIgualAoHttpNo5xx() throws Exception {
        ResponseEntity<Object> resposta = handler.handleException(
                new AsyncRequestTimeoutException(), requisicao("GET", "/api/chamados"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(corpo(resposta).status()).isEqualTo(503);
        assertThat(corpo(resposta).erro()).isEqualTo("ERRO_INTERNO");
        assertThat(corpo(resposta).mensagem()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("4xx sem codigo dedicado (406) vira REQUISICAO_INVALIDA com o status preservado")
    void deveUsarCodigoGenericoParaOutros4xx() throws Exception {
        ResponseEntity<Object> resposta = handler.handleException(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                requisicao("GET", "/api/chamados"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(406);
        assertThat(corpo(resposta).status()).isEqualTo(406);
        assertThat(corpo(resposta).erro()).isEqualTo("REQUISICAO_INVALIDA");
    }

    @Test
    @DisplayName("requisicao que nao e servlet nao quebra o handler: usa a descricao do WebRequest")
    void deveTratarWebRequestQueNaoEServlet() throws Exception {
        WebRequest naoServlet = mock(WebRequest.class);
        when(naoServlet.getDescription(false)).thenReturn("uri=/api/chamados");

        ResponseEntity<Object> resposta = handler.handleException(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), naoServlet);

        assertThat(resposta.getStatusCode().value()).isEqualTo(406);
    }
}
