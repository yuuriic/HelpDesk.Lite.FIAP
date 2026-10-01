package br.com.helpdesk.service.dto;

import java.time.Instant;

public record ComentarioResponse(Long id, UsuarioResumoResponse autor, String texto, Instant criadoEm) {
}
