package br.com.helpdesk.domain.sla;

import br.com.helpdesk.domain.model.Chamado;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Regras de SLA da secao 8.3 do documento.
 *
 * <pre>
 * prazo      = criadoEm + categoria.slaHoras
 * foraDoSla  = (status em andamento E agora &gt; prazo) OU (resolvidoEm &gt; prazo)
 * tempoMedio = media de (resolvidoEm - criadoEm) dos chamados ja resolvidos
 * </pre>
 *
 * <p>Classe pura: recebe o {@link Clock} por construtor e nao acessa banco. Instanciavel
 * num teste com {@code new SlaCalculator(Clock.fixed(...))}, o que torna cada cenario
 * deterministico (ADR-013).</p>
 */
@RequiredArgsConstructor
public class SlaCalculator {

    private final Clock clock;

    /** Instante limite para atender o chamado. */
    public Instant prazo(Chamado chamado) {
        return chamado.getCriadoEm().plus(Duration.ofHours(chamado.getCategoria().getSlaHoras()));
    }

    /**
     * Um chamado esta fora do SLA se ainda esta em andamento e o prazo ja passou, ou se
     * foi resolvido depois do prazo.
     *
     * <p>Note que um chamado resolvido <b>dentro</b> do prazo nunca fica fora do SLA, por
     * mais antigo que seja: a segunda clausula olha {@code resolvidoEm}, nao "agora".</p>
     */
    public boolean foraDoSla(Chamado chamado) {
        return situacao(chamado).foraDoSla();
    }

    /**
     * Quanto o chamado passou do prazo. {@link Duration#ZERO} quando esta dentro do SLA.
     * Serve para ordenar a lista do dashboard do mais atrasado para o menos.
     */
    public Duration atraso(Chamado chamado) {
        return situacao(chamado).atraso();
    }

    /**
     * Prazo, "fora do SLA" e atraso numa avaliacao so (ARQ-04). E a unica implementacao da
     * regra: {@link #foraDoSla} e {@link #atraso} delegam para ca. Le o relogio uma vez, entao as
     * tres respostas se referem ao mesmo instante.
     *
     * <p>Quem precisa de mais de uma resposta para o mesmo chamado -- o mapper e o dashboard --
     * chama este metodo uma vez em vez de reavaliar a regra a cada pergunta.</p>
     */
    public SituacaoSla situacao(Chamado chamado) {
        Instant prazo = prazo(chamado);
        Instant resolvidoEm = chamado.getResolvidoEm();
        Instant agora = Instant.now(clock);

        boolean foraDoSla = resolvidoEm != null
                ? resolvidoEm.isAfter(prazo)
                : chamado.getStatus().emAndamento() && agora.isAfter(prazo);
        Duration atraso = foraDoSla
                ? Duration.between(prazo, resolvidoEm != null ? resolvidoEm : agora)
                : Duration.ZERO;

        return new SituacaoSla(prazo, foraDoSla, atraso);
    }

    /**
     * Media de {@code resolvidoEm - criadoEm}, em horas, com duas casas.
     *
     * <p>Chamados sem {@code resolvidoEm} ficam de fora. Sem nenhum chamado resolvido o
     * resultado e zero -- nunca nulo, nunca divisao por zero.</p>
     */
    public double tempoMedioResolucaoHoras(Collection<Chamado> chamados) {
        double mediaSegundos = chamados.stream()
                .filter(c -> c.getResolvidoEm() != null)
                .mapToLong(c -> Duration.between(c.getCriadoEm(), c.getResolvidoEm()).toSeconds())
                .average()
                .orElse(0d);

        return Math.round(mediaSegundos / 3600d * 100d) / 100d;
    }
}
