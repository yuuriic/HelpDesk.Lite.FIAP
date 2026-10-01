package br.com.helpdesk.service;

import br.com.helpdesk.domain.enums.Prioridade;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.permissao.PermissaoService;
import br.com.helpdesk.domain.sla.SituacaoSla;
import br.com.helpdesk.domain.sla.SlaCalculator;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.service.dto.ChamadoResumoResponse;
import br.com.helpdesk.service.dto.DashboardResumoResponse;
import br.com.helpdesk.service.mapper.ChamadoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Indicadores da secao 8.3 do documento. Visivel apenas para atendente e admin, que
 * enxergam a base inteira -- por isso a agregacao nao filtra por usuario.
 *
 * <p>Tambem e aqui que os gauges de negocio sao atualizados (ADR-018): uma leitura do
 * dashboard republica {@code helpdesk_chamados_por_status} e
 * {@code helpdesk_chamados_fora_sla} no Micrometer.</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ChamadoRepository chamadoRepository;
    private final PermissaoService permissaoService;
    private final SlaCalculator slaCalculator;
    private final ChamadoMapper chamadoMapper;
    private final MetricasNegocio metricas;

    @Transactional(readOnly = true)
    public DashboardResumoResponse resumo(Usuario usuarioAtual) {
        permissaoService.verificarDashboard(usuarioAtual);

        List<Chamado> chamados = chamadoRepository.buscarTodosParaDashboard();

        Map<StatusChamado, Long> porStatus = contarPorStatus(chamados);
        Map<Prioridade, Long> porPrioridade = contarPorPrioridade(chamados);

        // SLA avaliado uma vez por chamado (ARQ-04). Antes, "fora do SLA" era reavaliado no
        // filtro, a cada comparacao da ordenacao e de novo no mapper.
        // Do mais atrasado para o menos: e a ordem util para quem vai agir na fila.
        List<ChamadoAvaliado> foraDoSla = chamados.stream()
                .map(chamado -> new ChamadoAvaliado(chamado, slaCalculator.situacao(chamado)))
                .filter(avaliado -> avaliado.sla().foraDoSla())
                .sorted(Comparator.comparing((ChamadoAvaliado avaliado) -> avaliado.sla().atraso()).reversed())
                .toList();

        metricas.atualizarGauges(porStatus, foraDoSla.size());

        List<ChamadoResumoResponse> resumoForaDoSla = foraDoSla.stream()
                .map(avaliado -> chamadoMapper.paraResumo(avaliado.chamado(), avaliado.sla()))
                .toList();

        return new DashboardResumoResponse(
                porStatus,
                porPrioridade,
                chamados.size(),
                foraDoSla.size(),
                slaCalculator.tempoMedioResolucaoHoras(chamados),
                resumoForaDoSla);
    }

    /** Chamado com a situacao de SLA ja calculada, para nao reavaliar a regra. */
    private record ChamadoAvaliado(Chamado chamado, SituacaoSla sla) {
    }

    /** Todos os status aparecem no mapa, mesmo zerados: o dashboard nao pode ter card faltando. */
    private Map<StatusChamado, Long> contarPorStatus(List<Chamado> chamados) {
        Map<StatusChamado, Long> contagem = new EnumMap<>(StatusChamado.class);
        for (StatusChamado status : StatusChamado.values()) {
            contagem.put(status, 0L);
        }
        chamados.forEach(c -> contagem.merge(c.getStatus(), 1L, Long::sum));
        return contagem;
    }

    private Map<Prioridade, Long> contarPorPrioridade(List<Chamado> chamados) {
        Map<Prioridade, Long> contagem = new EnumMap<>(Prioridade.class);
        for (Prioridade prioridade : Prioridade.values()) {
            contagem.put(prioridade, 0L);
        }
        chamados.forEach(c -> contagem.merge(c.getPrioridade(), 1L, Long::sum));
        return contagem;
    }
}
