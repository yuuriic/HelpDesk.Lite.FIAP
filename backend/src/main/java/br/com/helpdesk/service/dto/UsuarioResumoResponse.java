package br.com.helpdesk.service.dto;

import br.com.helpdesk.domain.enums.Perfil;

/** Usuario embutido dentro de outro recurso (solicitante, atendente, autor). */
public record UsuarioResumoResponse(Long id, String nome, Perfil perfil) {
}
