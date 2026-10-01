package br.com.helpdesk.service.mapper;

import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Comentario;
import br.com.helpdesk.domain.sla.SituacaoSla;
import br.com.helpdesk.domain.sla.SlaCalculator;
import br.com.helpdesk.service.dto.ChamadoResponse;
import br.com.helpdesk.service.dto.ChamadoResumoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entidade -> DTO do chamado.
 *
 * <p>O prazo e o "fora do SLA" nao sao colunas do banco: vem da {@link SituacaoSla} que o
 * {@link SlaCalculator} (dominio) avalia uma vez por chamado. O mapper so copia (ARQ-04).</p>
 */
@Component
@RequiredArgsConstructor
public class ChamadoMapper {

    private final CategoriaMapper categoriaMapper;
    private final UsuarioMapper usuarioMapper;
    private final ComentarioMapper comentarioMapper;
    private final SlaCalculator slaCalculator;

    public ChamadoResumoResponse paraResumo(Chamado chamado) {
        return paraResumo(chamado, slaCalculator.situacao(chamado));
    }

    /** Para quem ja avaliou o SLA do chamado (o dashboard) e nao precisa reavaliar. */
    public ChamadoResumoResponse paraResumo(Chamado chamado, SituacaoSla sla) {
        return new ChamadoResumoResponse(
                chamado.getId(),
                chamado.getTitulo(),
                chamado.getStatus(),
                chamado.getPrioridade(),
                categoriaMapper.paraResponse(chamado.getCategoria()),
                usuarioMapper.paraResumo(chamado.getSolicitante()),
                usuarioMapper.paraResumo(chamado.getAtendente()),
                chamado.getCriadoEm(),
                sla.prazo(),
                sla.foraDoSla());
    }

    public ChamadoResponse paraResponse(Chamado chamado, List<Comentario> comentarios) {
        SituacaoSla sla = slaCalculator.situacao(chamado);
        return new ChamadoResponse(
                chamado.getId(),
                chamado.getTitulo(),
                chamado.getDescricao(),
                chamado.getStatus(),
                chamado.getPrioridade(),
                categoriaMapper.paraResponse(chamado.getCategoria()),
                usuarioMapper.paraResumo(chamado.getSolicitante()),
                usuarioMapper.paraResumo(chamado.getAtendente()),
                chamado.getCriadoEm(),
                chamado.getAtualizadoEm(),
                chamado.getResolvidoEm(),
                chamado.getFechadoEm(),
                sla.prazo(),
                sla.foraDoSla(),
                comentarios.stream().map(comentarioMapper::paraResponse).toList());
    }
}
