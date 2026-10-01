package br.com.helpdesk.service.dto;

/** Entrada da criacao e da atualizacao de categoria (ARQ-01; ver {@link DadosNovoChamado}). */
public record DadosCategoria(String nome, Integer slaHoras) {
}
