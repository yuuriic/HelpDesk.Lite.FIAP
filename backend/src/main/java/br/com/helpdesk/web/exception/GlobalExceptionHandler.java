package br.com.helpdesk.web.exception;

import br.com.helpdesk.domain.exception.AcessoNegadoException;
import br.com.helpdesk.domain.exception.NaoAutenticadoException;
import br.com.helpdesk.domain.exception.RecursoNaoEncontradoException;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.exception.TransicaoInvalidaException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.List;

/**
 * Traducao unica de excecao para JSON (ADR-010).
 *
 * <p>Regra de log da secao 4.11 do documento: <b>4xx em WARN sem stack trace</b>,
 * <b>5xx em ERROR com stack trace</b>. Este e o unico lugar do sistema que loga excecao.</p>
 *
 * <p>Estende {@link ResponseEntityExceptionHandler} para que as excecoes de cliente do proprio
 * Spring MVC -- rota inexistente, metodo nao suportado, Content-Type invalido, JSON
 * malformado, parametro de tipo errado -- recebam o status HTTP correto (404, 405, 415, 400)
 * em vez de cair no tratamento generico de 500. Todas passam por
 * {@link #handleExceptionInternal}, que troca o corpo padrao do Spring pelo
 * {@link ErroResponse} e aplica a regra de log.</p>
 *
 * <p>O {@code requestId} nao entra na mensagem: ele ja vem do MDC no pattern do Logback.
 * Nenhuma mensagem devolvida ao cliente repete detalhe interno (classe, SQL, constraint).</p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ------------------------------------------------------------------ erros do Spring MVC

    /**
     * Ponto unico por onde passam todas as excecoes tratadas pela classe base. Se o corpo ja
     * veio montado (validacao), so repassa; senao monta o {@link ErroResponse} pelo status.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object corpo, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        Object resposta = corpo instanceof ErroResponse ? corpo : erroPorStatus(e, status, request);
        return super.handleExceptionInternal(e, resposta, headers, status, request);
    }

    /** DTO reprovado na Bean Validation -> 400 com a lista de campos. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErroResponse.CampoInvalido> campos = e.getBindingResult().getFieldErrors().stream()
                .map(erro -> new ErroResponse.CampoInvalido(erro.getField(), erro.getDefaultMessage()))
                .sorted(Comparator.comparing(ErroResponse.CampoInvalido::campo))
                .toList();

        logarClient(descrever(request), 400, "campos invalidos: " + campos.stream()
                .map(ErroResponse.CampoInvalido::campo).toList());

        return handleExceptionInternal(e, ErroResponse.deValidacao("Requisicao invalida", campos),
                headers, status, request);
    }

    /**
     * 405. A classe base tambem escreve um WARN proprio no logger "PageNotFound"; aqui ele e
     * dispensado para cada requisicao rejeitada gerar uma linha so. O header {@code Allow}
     * com os metodos aceitos continua na resposta.
     */
    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                         HttpHeaders headers, HttpStatusCode status,
                                                                         WebRequest request) {
        return handleExceptionInternal(e, null, headers, status, request);
    }

    /** Corpo padrao para as excecoes da classe base, escolhido pelo status. */
    private ErroResponse erroPorStatus(Exception e, HttpStatusCode status, WebRequest request) {
        String requisicao = descrever(request);

        if (status.is5xxServerError()) {
            log.error("Erro interno em {}", requisicao, e);
            return erroInterno(status.value());
        }

        logarClient(requisicao, status.value(), e.getClass().getSimpleName());
        return switch (status.value()) {
            case 400 -> ErroResponse.de(400, "VALIDACAO", "Requisicao invalida ou malformada");
            case 404 -> ErroResponse.de(404, "RECURSO_NAO_ENCONTRADO", "Recurso nao encontrado");
            case 405 -> ErroResponse.de(405, "METODO_NAO_SUPORTADO", "Metodo HTTP nao suportado para este recurso");
            case 415 -> ErroResponse.de(415, "MIDIA_NAO_SUPORTADA", "Content-Type nao suportado. Envie application/json");
            default -> ErroResponse.de(status.value(), "REQUISICAO_INVALIDA", "Requisicao invalida");
        };
    }

    // ------------------------------------------------------------------ erros de dados

    /** Ordenacao por campo inexistente ({@code ?sort=naoExiste}) -> 400. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErroResponse> tratarOrdenacaoInvalida(PropertyReferenceException e,
                                                                HttpServletRequest request) {
        logarClient(descrever(request), 400, "ordenacao por campo inexistente: " + e.getPropertyName());
        return ResponseEntity.badRequest()
                .body(ErroResponse.de(400, "VALIDACAO", "Campo de ordenacao invalido: " + e.getPropertyName()));
    }

    /**
     * Violacao de constraint no banco -- por exemplo, dois cadastros simultaneos com o mesmo
     * e-mail passando juntos pela checagem previa (CON-02) -> 409. A mensagem original do banco
     * nao e repassada nem logada: ela traz nome de constraint e o valor duplicado.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErroResponse> tratarViolacaoDeIntegridade(DataIntegrityViolationException e,
                                                                    HttpServletRequest request) {
        logarClient(descrever(request), 409, e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErroResponse.de(409, "CONFLITO", "A operacao conflita com dados ja existentes"));
    }

    /** Registro alterado por outra requisicao entre a leitura e a gravacao -> 409. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErroResponse> tratarConflitoDeVersao(OptimisticLockingFailureException e,
                                                               HttpServletRequest request) {
        logarClient(descrever(request), 409, e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErroResponse.de(409, "CONFLITO",
                        "O registro foi alterado por outra requisicao. Recarregue e tente novamente"));
    }

    /**
     * Trava de linha nao obtida a tempo (ou deadlock detectado pelo banco) -> 409. As guardas de
     * REG-01/REG-02 travam usuarios (N-01); uma requisicao que espera alem do limite do banco
     * desiste com conflito, nao com 500.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErroResponse> tratarTravaNaoObtida(PessimisticLockingFailureException e,
                                                             HttpServletRequest request) {
        logarClient(descrever(request), 409, e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErroResponse.de(409, "CONFLITO",
                        "O registro esta sendo alterado por outra requisicao. Tente novamente"));
    }

    // ------------------------------------------------------------------ erros de dominio

    @ExceptionHandler(NaoAutenticadoException.class)
    public ResponseEntity<ErroResponse> tratarNaoAutenticado(NaoAutenticadoException e,
                                                             HttpServletRequest request) {
        logarClient(descrever(request), 401, e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErroResponse.de(401, "NAO_AUTENTICADO", e.getMessage()));
    }

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<ErroResponse> tratarAcessoNegado(AcessoNegadoException e,
                                                           HttpServletRequest request) {
        logarClient(descrever(request), 403, e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErroResponse.de(403, "ACESSO_NEGADO", e.getMessage()));
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ErroResponse> tratarNaoEncontrado(RecursoNaoEncontradoException e,
                                                            HttpServletRequest request) {
        logarClient(descrever(request), 404, e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErroResponse.de(404, "RECURSO_NAO_ENCONTRADO", e.getMessage()));
    }

    @ExceptionHandler(TransicaoInvalidaException.class)
    public ResponseEntity<ErroResponse> tratarTransicaoInvalida(TransicaoInvalidaException e,
                                                                HttpServletRequest request) {
        logarClient(descrever(request), 422, e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErroResponse.de(422, "TRANSICAO_INVALIDA", e.getMessage()));
    }

    @ExceptionHandler(RegraNegocioException.class)
    public ResponseEntity<ErroResponse> tratarRegraNegocio(RegraNegocioException e,
                                                           HttpServletRequest request) {
        logarClient(descrever(request), 422, e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErroResponse.de(422, "REGRA_NEGOCIO", e.getMessage()));
    }

    // ------------------------------------------------------------------ rede de seguranca

    /** Qualquer outra excecao e defeito do servidor: 500, sem stack trace no corpo. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErroResponse> tratarErroInterno(Exception e, HttpServletRequest request) {
        log.error("Erro interno em {}", descrever(request), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(erroInterno(500));
    }

    // ------------------------------------------------------------------ apoio

    /** O {@code status} do corpo acompanha o HTTP: a classe base tambem produz 503. */
    private static ErroResponse erroInterno(int status) {
        return ErroResponse.de(status, "ERRO_INTERNO", "Erro interno do servidor. Informe o X-Request-Id ao suporte.");
    }

    /** 4xx: WARN e sem stack trace -- erro de cliente nao e defeito do servidor. */
    private void logarClient(String requisicao, int status, Object detalhe) {
        log.warn("Requisicao rejeitada com {} em {}: {}", status, requisicao, detalhe);
    }

    private static String descrever(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static String descrever(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? descrever(servlet.getRequest())
                : request.getDescription(false);
    }
}
