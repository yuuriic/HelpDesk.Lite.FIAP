package br.com.helpdesk.service;

import br.com.helpdesk.domain.enums.Prioridade;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.NaoAutenticadoException;
import br.com.helpdesk.domain.exception.RecursoNaoEncontradoException;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Chamado;
import br.com.helpdesk.domain.model.Comentario;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.permissao.PermissaoService;
import br.com.helpdesk.domain.state.TransicaoStatus;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.ChamadoSpecification;
import br.com.helpdesk.repository.ComentarioRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.dto.ChamadoResponse;
import br.com.helpdesk.service.dto.ChamadoResumoResponse;
import br.com.helpdesk.service.dto.ComentarioResponse;
import br.com.helpdesk.service.dto.DadosNovoChamado;
import br.com.helpdesk.service.mapper.ChamadoMapper;
import br.com.helpdesk.service.mapper.ComentarioMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Casos de uso do chamado.
 *
 * <p>Toda operacao segue a mesma sequencia, nesta ordem (secao 4.6 do documento):</p>
 * <ol>
 *   <li>busca o chamado -- 404 se nao existe;</li>
 *   <li>{@link PermissaoService} -- 403 se o usuario nao pode;</li>
 *   <li>o proprio {@link Chamado} ({@code assumir}/{@code alterarStatus}) valida a transicao na
 *       {@link TransicaoStatus} -- 422 se nao vale agora -- e aplica os carimbos de tempo;</li>
 *   <li>persiste, registra metrica e escreve a linha de auditoria.</li>
 * </ol>
 *
 * <p>O service so orquestra: nao altera estado do chamado diretamente (DOM-01). Todo instante
 * vem do {@link Clock} injetado e e entregue ao agregado.</p>
 *
 * <p>Inverter os passos 2 e 3 vazaria o estado do chamado para quem nao pode ve-lo.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChamadoService {

    private final ChamadoRepository chamadoRepository;
    private final ComentarioRepository comentarioRepository;
    private final CategoriaRepository categoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final PermissaoService permissaoService;
    private final TransicaoStatus transicaoStatus;
    private final ChamadoMapper chamadoMapper;
    private final ComentarioMapper comentarioMapper;
    private final MetricasNegocio metricas;
    private final Clock clock;

    // ------------------------------------------------------------------ leitura

    /**
     * Lista paginada. A visibilidade do solicitante entra na {@code Specification}, ou seja,
     * no WHERE da query -- nem a pagina nem o count vazam chamados de terceiros.
     */
    @Transactional(readOnly = true)
    public Page<ChamadoResumoResponse> listar(Usuario usuarioAtual,
                                              StatusChamado status,
                                              Prioridade prioridade,
                                              Long categoriaId,
                                              boolean meus,
                                              Pageable paginacao) {
        return chamadoRepository
                .findAll(ChamadoSpecification.comFiltros(usuarioAtual.getPerfil(), usuarioAtual.getId(),
                        status, prioridade, categoriaId, meus), paginacao)
                .map(chamadoMapper::paraResumo);
    }

    @Transactional(readOnly = true)
    public ChamadoResponse buscarDetalhe(Long id, Usuario usuarioAtual) {
        Chamado chamado = buscarComPermissaoDeLeitura(id, usuarioAtual);
        return chamadoMapper.paraResponse(chamado, comentariosDe(id));
    }

    @Transactional(readOnly = true)
    public List<ComentarioResponse> listarComentarios(Long chamadoId, Usuario usuarioAtual) {
        buscarComPermissaoDeLeitura(chamadoId, usuarioAtual);
        return comentariosDe(chamadoId).stream().map(comentarioMapper::paraResponse).toList();
    }

    // ------------------------------------------------------------------ escrita

    @Transactional
    public ChamadoResponse abrir(DadosNovoChamado dados, Usuario usuarioAtual) {
        permissaoService.verificarAbertura(usuarioAtual);

        Categoria categoria = categoriaRepository.findById(dados.categoriaId())
                .orElseThrow(() -> RecursoNaoEncontradoException.de("Categoria", dados.categoriaId()));

        // Categoria inativa (I8) e recusada pelo proprio agregado.
        Chamado chamado = Chamado.abrir(dados.titulo().trim(), dados.descricao().trim(),
                dados.prioridade(), categoria, usuarioAtual, Instant.now(clock));

        Chamado salvo = chamadoRepository.save(chamado);

        metricas.chamadoAberto(salvo);
        auditar("ABRIR_CHAMADO", usuarioAtual, salvo.getId(), null, StatusChamado.ABERTO);

        return chamadoMapper.paraResponse(salvo, List.of());
    }

    /**
     * Assumir: o atendente vira responsavel e o chamado vai para EM_ATENDIMENTO.
     *
     * <p>Assumir um chamado ja assumido cai em 422: ele nao esta mais em ABERTO, e
     * {@code EM_ATENDIMENTO -> EM_ATENDIMENTO} nao existe na maquina de estados.</p>
     *
     * <p><b>Quem vira atendente e o usuario gravado, com a linha travada (N-01).</b> O
     * {@code usuarioAtual} foi lido pelo filtro antes desta transacao; se um admin o rebaixou ou
     * desativou nesse meio-tempo, assumir com ele deixaria o chamado com um atendente que ja nao
     * atende (I3), passando por cima da guarda do UsuarioService (REG-02). A trava e a mesma que
     * o UsuarioService pede ao alterar o usuario: as duas operacoes passam uma de cada vez, e a
     * segunda decide sobre o que a primeira confirmou. Ela vem antes da leitura do chamado para
     * o perfil e o status ativo lidos aqui serem os gravados.</p>
     */
    @Transactional
    public ChamadoResponse assumir(Long id, Usuario usuarioAtual) {
        Usuario atendente = usuarioRepository.findComTravaById(usuarioAtual.getId())
                .filter(Usuario::isAtivo)
                .orElseThrow(NaoAutenticadoException::new);
        Chamado chamado = buscar(id);

        permissaoService.verificarAssuncao(atendente);
        StatusChamado statusAnterior = chamado.getStatus();
        chamado.assumir(atendente, transicaoStatus, Instant.now(clock));

        Chamado salvo = chamadoRepository.save(chamado);

        metricas.transicaoRegistrada(statusAnterior, StatusChamado.EM_ATENDIMENTO, atendente.getPerfil());
        auditar("ASSUMIR_CHAMADO", atendente, id, statusAnterior, StatusChamado.EM_ATENDIMENTO);

        return chamadoMapper.paraResponse(salvo, comentariosDe(id));
    }

    @Transactional
    public ChamadoResponse alterarStatus(Long id, StatusChamado novoStatus, Usuario usuarioAtual) {
        Chamado chamado = buscar(id);

        // Ordem obrigatoria: quem pode (403) antes de pode agora (422).
        permissaoService.verificarAlteracaoStatus(usuarioAtual, chamado, novoStatus);
        StatusChamado statusAnterior = chamado.getStatus();
        chamado.alterarStatus(novoStatus, usuarioAtual.getPerfil(), transicaoStatus, Instant.now(clock));

        Chamado salvo = chamadoRepository.save(chamado);

        if (novoStatus == StatusChamado.RESOLVIDO) {
            metricas.tempoDeResolucao(chamado, chamado.tempoAteResolucao());
        }
        metricas.transicaoRegistrada(statusAnterior, novoStatus, usuarioAtual.getPerfil());
        auditar("ALTERAR_STATUS", usuarioAtual, id, statusAnterior, novoStatus);

        return chamadoMapper.paraResponse(salvo, comentariosDe(id));
    }

    @Transactional
    public ComentarioResponse comentar(Long chamadoId, String texto, Usuario usuarioAtual) {
        Chamado chamado = buscar(chamadoId);
        permissaoService.verificarComentario(usuarioAtual, chamado);

        Comentario salvo = comentarioRepository.save(
                Comentario.registrar(chamado, usuarioAtual, texto.trim(), Instant.now(clock)));
        Long comentarioId = salvo.getId();
        AposCommit.executar(() -> log.info("acao=COMENTAR chamadoId={} comentarioId={} perfil={}",
                chamadoId, comentarioId, usuarioAtual.getPerfil()));

        return comentarioMapper.paraResponse(salvo);
    }

    // ------------------------------------------------------------------ apoio

    private Chamado buscar(Long id) {
        return chamadoRepository.findWithRelacionamentosById(id)
                .orElseThrow(() -> RecursoNaoEncontradoException.de("Chamado", id));
    }

    private Chamado buscarComPermissaoDeLeitura(Long id, Usuario usuarioAtual) {
        Chamado chamado = buscar(id);
        permissaoService.verificarVisualizacao(usuarioAtual, chamado);
        return chamado;
    }

    private List<Comentario> comentariosDe(Long chamadoId) {
        return comentarioRepository.findAllByChamadoIdOrderByCriadoEmAsc(chamadoId);
    }

    /**
     * Linha de auditoria da secao 4.11. {@code requestId} e {@code usuarioId} nao entram na
     * mensagem: ja vem do MDC no pattern do Logback. Nada de dado pessoal alem do id.
     */
    private void auditar(String acao, Usuario usuario, Long chamadoId,
                         StatusChamado de, StatusChamado para) {
        AposCommit.executar(() -> log.info("acao={} chamadoId={} perfil={} statusDe={} statusPara={}",
                acao, chamadoId, usuario.getPerfil(), de, para));
    }
}
