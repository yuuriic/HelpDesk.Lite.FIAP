package br.com.helpdesk.service;

import br.com.helpdesk.domain.exception.RecursoNaoEncontradoException;
import br.com.helpdesk.domain.exception.RegraNegocioException;
import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.domain.model.Usuario;
import br.com.helpdesk.domain.permissao.PermissaoService;
import br.com.helpdesk.repository.CategoriaRepository;
import br.com.helpdesk.service.dto.CategoriaResponse;
import br.com.helpdesk.service.dto.DadosCategoria;
import br.com.helpdesk.service.mapper.CategoriaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** CRUD de categorias. A escrita e exclusiva do Admin; a leitura e aberta a todos. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoriaService {

    private final CategoriaRepository categoriaRepository;
    private final PermissaoService permissaoService;
    private final CategoriaMapper categoriaMapper;

    /** Somente as ativas: e a lista que alimenta o formulario de novo chamado. */
    @Transactional(readOnly = true)
    public List<CategoriaResponse> listarAtivas() {
        return categoriaRepository.findAllByAtivaTrueOrderByNomeAsc().stream()
                .map(categoriaMapper::paraResponse)
                .toList();
    }

    /**
     * Inclui as inativas: a tela de administracao mostra o que ja foi desativado.
     *
     * <p>Nao existe reativacao no MVP (REG-03): o {@code PUT} atualiza so nome e SLA, e uma
     * categoria desativada continua desativada. Reativar seria funcionalidade nova, fora do escopo
     * da modernizacao (plano, secao 14).</p>
     */
    @Transactional(readOnly = true)
    public List<CategoriaResponse> listarTodas(Usuario usuarioAtual) {
        permissaoService.verificarGestaoCategorias(usuarioAtual);
        return categoriaRepository.findAllByOrderByNomeAsc().stream()
                .map(categoriaMapper::paraResponse)
                .toList();
    }

    @Transactional
    public CategoriaResponse criar(DadosCategoria dados, Usuario usuarioAtual) {
        permissaoService.verificarGestaoCategorias(usuarioAtual);

        String nome = nomeDisponivel(dados.nome(), null);

        Categoria salva = categoriaRepository.save(Categoria.builder()
                .nome(nome)
                .slaHoras(dados.slaHoras())
                .ativa(true)
                .build());

        AposCommit.executar(() -> log.info("acao=CRIAR_CATEGORIA categoriaId={} perfil={}", salva.getId(), usuarioAtual.getPerfil()));
        return categoriaMapper.paraResponse(salva);
    }

    @Transactional
    public CategoriaResponse atualizar(Long id, DadosCategoria dados, Usuario usuarioAtual) {
        permissaoService.verificarGestaoCategorias(usuarioAtual);

        Categoria categoria = buscar(id);
        String nome = nomeDisponivel(dados.nome(), id);

        categoria.setNome(nome);
        categoria.setSlaHoras(dados.slaHoras());
        Categoria salva = categoriaRepository.save(categoria);

        AposCommit.executar(() -> log.info("acao=ATUALIZAR_CATEGORIA categoriaId={} perfil={}", id, usuarioAtual.getPerfil()));
        return categoriaMapper.paraResponse(salva);
    }

    /**
     * Soft delete (ADR-009): a categoria e desativada, nunca removida. Chamados historicos
     * precisam continuar apontando para ela, entao o hard delete nem chega a ser oferecido.
     */
    @Transactional
    public void desativar(Long id, Usuario usuarioAtual) {
        permissaoService.verificarGestaoCategorias(usuarioAtual);

        Categoria categoria = buscar(id);
        categoria.setAtiva(false);
        categoriaRepository.save(categoria);

        // Sem consultar os chamados da categoria (REG-05): o soft delete nao depende disso, e a
        // consulta existia so para enfeitar esta linha de log.
        AposCommit.executar(() -> log.info("acao=DESATIVAR_CATEGORIA categoriaId={} perfil={}", id, usuarioAtual.getPerfil()));
    }

    /**
     * Nome aparado e ainda nao usado por outra categoria (regra I7). {@code idIgnorado} e a
     * propria categoria numa atualizacao; nulo na criacao (REG-06).
     */
    private String nomeDisponivel(String nomeInformado, Long idIgnorado) {
        String nome = nomeInformado.trim();
        boolean emUso = idIgnorado == null
                ? categoriaRepository.existsByNomeIgnoreCase(nome)
                : categoriaRepository.existsByNomeIgnoreCaseAndIdNot(nome, idIgnorado);
        if (emUso) {
            throw new RegraNegocioException("Ja existe uma categoria chamada " + nome);
        }
        return nome;
    }

    private Categoria buscar(Long id) {
        return categoriaRepository.findById(id)
                .orElseThrow(() -> RecursoNaoEncontradoException.de("Categoria", id));
    }
}
