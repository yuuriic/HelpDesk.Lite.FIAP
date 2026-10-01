package br.com.helpdesk.service.dto;

import br.com.helpdesk.domain.enums.Perfil;

/** Entrada da criacao e da atualizacao de usuario (ARQ-01; ver {@link DadosNovoChamado}). */
public record DadosUsuario(String nome, String email, Perfil perfil) {
}
