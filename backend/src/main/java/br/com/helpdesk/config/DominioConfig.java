package br.com.helpdesk.config;

import br.com.helpdesk.domain.permissao.PermissaoService;
import br.com.helpdesk.domain.sla.SlaCalculator;
import br.com.helpdesk.domain.state.TransicaoStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Expoe as classes puras de dominio como beans.
 *
 * <p>{@link TransicaoStatus}, {@link PermissaoService} e {@link SlaCalculator} nao levam
 * {@code @Component}/{@code @Service} de proposito: o dominio nao conhece Spring. Quem faz a
 * ponte e esta configuracao, que vive na camada de fora (ARQ-02).</p>
 */
@Configuration
public class DominioConfig {

    @Bean
    public TransicaoStatus transicaoStatus() {
        return new TransicaoStatus();
    }

    @Bean
    public PermissaoService permissaoService(TransicaoStatus transicaoStatus) {
        return new PermissaoService(transicaoStatus);
    }

    @Bean
    public SlaCalculator slaCalculator(Clock clock) {
        return new SlaCalculator(clock);
    }
}
