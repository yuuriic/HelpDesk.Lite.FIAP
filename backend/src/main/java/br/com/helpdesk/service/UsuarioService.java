package br.com.helpdesk.service;

import br.com.helpdesk.domain.enums.Perfil;
import br.com.helpdesk.domain.enums.StatusChamado;
import br.com.helpdesk.domain.exception.NaoAutenticadoException;
import br.com.helpdesk.domain.exception.RecursoNaoEncontradoException;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.permissao.PermissaoService;
import br.com.helpdesk.repository.ChamadoRepository;
import br.com.helpdesk.repository.UsuarioRepository;
import br.com.helpdesk.service.dto.DadosUsuario;
import br.com.helpdesk.service.dto.UsuarioResponse;
import br.com.helpdesk.service.dto.UsuarioResumoResponse;
import br.com.helpdesk.service.mapper.UsuarioMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CRUD de usuarios (Admin) e a lista publica que alimenta o login simulado.
 *
 * <p>Alem da validacao de cadastro, protege duas invariantes que a edicao de usuarios
 * conseguia quebrar:</p>
 * <ul>
 *   <li><b>REG-01</b> -- sempre existe ao menos um ADMIN ativo; sem ele ninguem mais consegue
 *       gerenciar usuarios e nao ha caminho de recuperacao no produto;</li>
 *   <li><b>REG-02 / I3</b> -- quem e atendente de chamado em andamento nao deixa de poder
 *       atender (rebaixado a SOLICITANTE ou desativado). Senao o chamado fica orfao: nao existe
 *       reatribuicao.</li>
 * </ul>
 *
 * <p><b>As duas guardas valem sob concorrencia (N-01).</b> Sozinhas, elas sao "conta e depois
 * grava" sobre linhas diferentes: dois admins que rebaixam um ao outro ao mesmo tempo contam
 * dois admins cada um, e os dois rebaixamentos passam. Por isso quem altera ou desativa um
 * usuario trava, nesta ordem, os admins ativos e o proprio usuario ({@link #buscarComTrava}). A
 * requisicao concorrente espera o commit da primeira e decide sobre o estado ja gravado. A
 * assuncao de chamado trava a mesma linha do atendente (ChamadoService.assumir).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsuarioService {

    /**
     * Status em que o chamado ainda depende do seu atendente: em atendimento, ou resolvido e
     * ainda reabrivel pelo solicitante -- a reabertura devolve o chamado para o mesmo atendente.
     */
    private static final Set<StatusChamado> STATUS_QUE_PRENDEM_O_ATENDENTE =
            EnumSet.of(StatusChamado.EM_ATENDIMENTO, StatusChamado.RESOLVIDO);

    /**
     * Sem o e-mail na mensagem (SEG-04): ela volta no corpo do 422 e o GlobalExceptionHandler a
     * registra no log WARN, que nao pode ter dado pessoal alem do id (secao 4.11).
     */
    private static final String EMAIL_JA_CADASTRADO = "Ja existe um usuario com este e-mail";

    private final UsuarioRepository usuarioRepository;
    private final ChamadoRepository chamadoRepository;
    private final PermissaoService permissaoService;
    private final UsuarioMapper usuarioMapper;

    /** Publico: monta a tela de login simulado. So usuarios ativos, e sem e-mail (SEG-03). */
    @Transactional(readOnly = true)
    public List<UsuarioResumoResponse> listarDisponiveisParaLogin() {
        return usuarioRepository.findAllByAtivoTrueOrderByNomeAsc().stream()
                .map(usuarioMapper::paraResumo)
                .toList();
    }

    /**
     * Login simulado: nao ha senha, so a confirmacao de que o usuario existe e esta ativo.
     *
     * <p>A rota e publica: qualquer um informa um id. Por isso a resposta e o resumo, sem
     * e-mail (SEG-03).</p>
     *
     * <p>Inexistente e inativo recebem a mesma resposta -- 401, igual ao UsuarioAtualFilter
     * (regra I9) -- para o login nao revelar quais ids existem.</p>
     */
    @Transactional(readOnly = true)
    public UsuarioResumoResponse autenticar(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .orElseThrow(NaoAutenticadoException::new);
        return usuarioMapper.paraResumo(usuario);
    }

    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar(Usuario usuarioAtual) {
        permissaoService.verificarGestaoUsuarios(usuarioAtual);
        return usuarioRepository.findAllByOrderByNomeAsc().stream()
                .map(usuarioMapper::paraResponse)
                .toList();
    }

    @Transactional
    public UsuarioResponse criar(DadosUsuario dados, Usuario usuarioAtual) {
        permissaoService.verificarGestaoUsuarios(usuarioAtual);

        String email = emailDisponivel(dados.email(), null);

        Usuario salvo = usuarioRepository.save(Usuario.builder()
                .nome(dados.nome().trim())
                .email(email)
                .perfil(dados.perfil())
                .ativo(true)
                .build());

        AposCommit.executar(() -> log.info("acao=CRIAR_USUARIO usuarioAlvoId={} perfilAlvo={} perfil={}",
                salvo.getId(), salvo.getPerfil(), usuarioAtual.getPerfil()));
        return usuarioMapper.paraResponse(salvo);
    }

    @Transactional
    public UsuarioResponse atualizar(Long id, DadosUsuario dados, Usuario usuarioAtual) {
        permissaoService.verificarGestaoUsuarios(usuarioAtual);

        Usuario usuario = buscarComTrava(id);
        String email = emailDisponivel(dados.email(), id);

        if (usuario.getPerfil() == Perfil.ADMIN && dados.perfil() != Perfil.ADMIN) {
            garantirOutroAdminAtivo(usuario);
        }
        if (usuario.getPerfil().atendeChamados() && !dados.perfil().atendeChamados()) {
            garantirSemChamadosEmAndamento(usuario, "rebaixa-lo para solicitante");
        }

        usuario.setNome(dados.nome().trim());
        usuario.setEmail(email);
        usuario.setPerfil(dados.perfil());
        Usuario salvo = usuarioRepository.save(usuario);

        AposCommit.executar(() -> log.info("acao=ATUALIZAR_USUARIO usuarioAlvoId={} perfilAlvo={} perfil={}",
                id, salvo.getPerfil(), usuarioAtual.getPerfil()));
        return usuarioMapper.paraResponse(salvo);
    }

    /**
     * Soft delete (ADR-009). Desativar a si mesmo e bloqueado: o admin ficaria sem conseguir
     * entrar de volta, e no MVP nao existe outro caminho de recuperacao.
     */
    @Transactional
    public void desativar(Long id, Usuario usuarioAtual) {
        permissaoService.verificarGestaoUsuarios(usuarioAtual);

        if (Objects.equals(id, usuarioAtual.getId())) {
            throw new RegraNegocioException("Voce nao pode desativar o proprio usuario");
        }

        Usuario usuario = buscarComTrava(id);
        garantirOutroAdminAtivo(usuario);
        garantirSemChamadosEmAndamento(usuario, "desativa-lo");

        usuario.setAtivo(false);
        usuarioRepository.save(usuario);

        AposCommit.executar(() -> log.info("acao=DESATIVAR_USUARIO usuarioAlvoId={} perfil={}", id, usuarioAtual.getPerfil()));
    }

    /**
     * REG-01: rebaixar ou desativar um admin ativo nao pode zerar os admins ativos.
     *
     * <p>Hoje o proprio admin logado conta como o "outro" admin ao desativar alguem (a auto
     * desativacao ja e barrada pela I10), entao o caso real e o auto rebaixamento do unico
     * admin. A checagem vale para os dois caminhos para a invariante nao depender dessa
     * coincidencia.</p>
     */
    private void garantirOutroAdminAtivo(Usuario usuario) {
        if (usuario.getPerfil() == Perfil.ADMIN && usuario.isAtivo()
                && usuarioRepository.countByPerfilAndAtivoTrue(Perfil.ADMIN) <= 1) {
            throw new RegraNegocioException("O sistema precisa de ao menos um administrador ativo");
        }
    }

    /** REG-02 / I3: o chamado nunca pode ficar com um atendente que nao consegue mais atende-lo. */
    private void garantirSemChamadosEmAndamento(Usuario usuario, String acao) {
        if (chamadoRepository.existsByAtendenteIdAndStatusIn(usuario.getId(), STATUS_QUE_PRENDEM_O_ATENDENTE)) {
            throw new RegraNegocioException("O usuario e atendente de chamados em atendimento ou aguardando "
                    + "confirmacao; nao e possivel " + acao + " antes de concluir esses chamados");
        }
    }

    /**
     * E-mail normalizado (aparado, minusculo) e ainda nao usado por outro usuario (regra I7).
     * {@code idIgnorado} e o proprio usuario numa atualizacao; nulo na criacao (REG-06).
     *
     * <p>Pela API, e-mail com espacos nas pontas ja e recusado pelo {@code @Email} do request
     * (REG-07); o {@code trim} fica porque o caso de uso tambem recebe {@code DadosUsuario} sem
     * passar pela validacao da web.</p>
     */
    private String emailDisponivel(String emailInformado, Long idIgnorado) {
        String email = emailInformado.trim().toLowerCase();
        boolean emUso = idIgnorado == null
                ? usuarioRepository.existsByEmailIgnoreCase(email)
                : usuarioRepository.existsByEmailIgnoreCaseAndIdNot(email, idIgnorado);
        if (emUso) {
            throw new RegraNegocioException(EMAIL_JA_CADASTRADO);
        }
        return email;
    }

    /**
     * O usuario a alterar, com as travas que tornam REG-01 e REG-02 seguras sob concorrencia.
     *
     * <p>Ordem fixa, igual para toda transacao que as pede: primeiro os admins ativos (por id),
     * depois o alvo. Assim duas alteracoes concorrentes nunca travam as mesmas linhas em ordem
     * inversa (sem deadlock), e a contagem de admins e a busca de chamados do atendente, feitas
     * depois, ja enxergam o que a outra confirmou.</p>
     */
    private Usuario buscarComTrava(Long id) {
        usuarioRepository.findComTravaByPerfilAndAtivoTrueOrderByIdAsc(Perfil.ADMIN);
        return usuarioRepository.findComTravaById(id)
                .orElseThrow(() -> RecursoNaoEncontradoException.de("Usuario", id));
    }
}
