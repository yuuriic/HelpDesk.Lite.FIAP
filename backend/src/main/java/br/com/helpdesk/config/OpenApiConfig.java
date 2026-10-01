package br.com.helpdesk.config;

import br.com.helpdesk.web.filter.UsuarioAtual;
import br.com.helpdesk.web.filter.UsuarioAtualFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Metadados da especificacao OpenAPI exposta em /swagger-ui.html (perfil dev).
 *
 * <p>O {@link UsuarioAtual} dos controllers e preenchido pelo filtro a partir do header
 * {@code X-User-Id}; o cliente nunca o envia. Sem ser ignorado aqui, o springdoc o documentava
 * como um parametro de query com a entidade {@code Usuario} inteira -- 16 operacoes com um
 * parametro inexistente (achado da Fase 6). O que o cliente envia de fato, o header, vai como
 * esquema de autenticacao: e ele que o "Authorize" do Swagger UI preenche.</p>
 */
@Configuration
public class OpenApiConfig {

    static final String ESQUEMA_LOGIN_SIMULADO = "loginSimulado";

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(UsuarioAtual.class);
    }

    @Bean
    public OpenAPI helpdeskOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HelpDesk Lite API")
                        .version("0.0.1")
                        .description("""
                                API de chamados internos (TI, Facilities, RH).

                                **Autenticacao simulada.** Nao ha login real: envie o header
                                `X-User-Id` com o id do usuario em todas as chamadas sob `/api`,
                                exceto `/api/auth/**` (use o botao *Authorize*). Use
                                `GET /api/auth/usuarios-disponiveis` para descobrir os ids do seed.

                                Trocar o `X-User-Id` e a forma de testar os tres perfis pelo Swagger.

                                Toda resposta traz o header `X-Request-Id`, que aparece nas linhas
                                de log daquela requisicao."""))
                .components(new Components().addSecuritySchemes(ESQUEMA_LOGIN_SIMULADO, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(UsuarioAtualFilter.HEADER_USER_ID)
                        .description("Id do usuario do login simulado (ADR-003). So existe nos perfis dev e test.")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_LOGIN_SIMULADO))
                .servers(List.of(new Server().url("http://localhost:8080").description("Ambiente local")));
    }
}
