package br.com.helpdesk.web;

import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.ChamadoService;
import br.com.helpdesk.support.UsuarioBuilder;
import br.com.helpdesk.web.controller.ChamadoController;
import br.com.helpdesk.web.dto.request.AlterarStatusRequest;
import br.com.helpdesk.web.filter.UsuarioAtualArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mapeamento de excecoes de persistencia e da rede de seguranca do GlobalExceptionHandler.
 *
 * <p>As excecoes sao lancadas pelo service mockado: violacao de integridade e conflito de
 * versao nao se reproduzem de forma deterministica pela API (CON-01, CON-02), mas o contrato
 * da resposta ja precisa estar garantido para as fases seguintes do plano.</p>
 */
@WebMvcTest(ChamadoController.class)
@ActiveProfiles("test")
@Import(UsuarioAtualArgumentResolver.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("GlobalExceptionHandler -- persistencia e erro interno (fatia web)")
class TratamentoDeErrosWebTest {

    private static final String HEADER_USUARIO = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChamadoService chamadoService;
    @MockBean
    private UsuarioRepository usuarioRepository;

    private final Usuario bruno = UsuarioBuilder.atendente().comId(2L).build();

    @BeforeEach
    void autenticarBruno() {
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(bruno));
    }

    private ResultActions resolverChamado1() throws Exception {
        return mockMvc.perform(patch("/api/chamados/{id}/status", 1).header(HEADER_USUARIO, "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AlterarStatusRequest(StatusChamado.RESOLVIDO))));
    }

    @Test
    @DisplayName("violacao de integridade vira 409 CONFLITO sem repassar a mensagem do banco")
    void deveMapearViolacaoDeIntegridadePara409(CapturedOutput saida) throws Exception {
        when(chamadoService.alterarStatus(eq(1L), any(), any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement; constraint [UK_USUARIO_EMAIL]; valor ana@empresa.com"));

        resolverChamado1()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.erro").value("CONFLITO"))
                .andExpect(jsonPath("$.mensagem", not(containsString("constraint"))))
                .andExpect(jsonPath("$.mensagem", not(containsString("ana@empresa.com"))));

        assertThat(saida.getOut())
                .contains("Requisicao rejeitada com 409")
                .doesNotContain("UK_USUARIO_EMAIL")
                .doesNotContain("ana@empresa.com");
    }

    @Test
    @DisplayName("conflito de versao (lock otimista) vira 409 CONFLITO")
    void deveMapearConflitoDeVersaoPara409() throws Exception {
        when(chamadoService.alterarStatus(eq(1L), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Chamado.class, 1L));

        resolverChamado1()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("CONFLITO"))
                .andExpect(jsonPath("$.mensagem").value(containsString("alterado por outra requisicao")));
    }

    @Test
    @DisplayName("trava de linha nao obtida a tempo (N-01) vira 409 CONFLITO, nao 500")
    void deveMapearTravaNaoObtidaPara409(CapturedOutput saida) throws Exception {
        when(chamadoService.alterarStatus(eq(1L), any(), any()))
                .thenThrow(new CannotAcquireLockException("Timeout trying to lock table \"USUARIO\""));

        resolverChamado1()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("CONFLITO"))
                .andExpect(jsonPath("$.mensagem").value(containsString("sendo alterado por outra requisicao")))
                .andExpect(jsonPath("$.mensagem").value(not(containsString("USUARIO"))));
        assertThat(saida.getOut()).contains("Requisicao rejeitada com 409").doesNotContain("Erro interno");
    }

    @Test
    @DisplayName("excecao inesperada continua 500 ERRO_INTERNO, sem a mensagem original no corpo")
    void deveManterA500ParaErroInesperado(CapturedOutput saida) throws Exception {
        when(chamadoService.alterarStatus(eq(1L), any(), any()))
                .thenThrow(new IllegalStateException("detalhe interno que nao pode vazar"));

        resolverChamado1()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.erro").value("ERRO_INTERNO"))
                .andExpect(jsonPath("$.mensagem", not(containsString("detalhe interno"))));

        assertThat(saida.getOut()).contains("Erro interno em PATCH /api/chamados/1/status");
    }
}
