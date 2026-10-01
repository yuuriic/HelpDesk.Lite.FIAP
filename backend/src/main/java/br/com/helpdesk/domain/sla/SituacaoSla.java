package br.com.helpdesk.domain.sla;

import java.time.Duration;
import java.time.Instant;

/**
 * A situacao de SLA de um chamado num instante, calculada uma vez por
 * {@link SlaCalculator#situacao} (ARQ-04).
 *
 * @param prazo     instante limite para atender o chamado
 * @param foraDoSla ainda em andamento com o prazo vencido, ou resolvido depois do prazo
 * @param atraso    quanto passou do prazo; {@link Duration#ZERO} quando dentro do SLA
 */
public record SituacaoSla(Instant prazo, boolean foraDoSla, Duration atraso) {
}
