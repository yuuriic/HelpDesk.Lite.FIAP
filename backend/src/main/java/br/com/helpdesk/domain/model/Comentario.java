package br.com.helpdesk.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * Comentario na linha do tempo do chamado.
 *
 * <p>Imutavel apos a criacao (regra I4): nao existe endpoint de edicao nem de exclusao, e a
 * entidade nao tem setters. Comentario novo nasce por {@link #registrar}; o {@code @Builder}
 * fica so para reconstituir comentarios existentes (fixtures de teste).</p>
 */
@Entity
@Table(name = "comentario", indexes = {
        @Index(name = "idx_comentario_chamado", columnList = "chamado_id")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Comentario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chamado_id", nullable = false)
    private Chamado chamado;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "autor_id", nullable = false)
    private Usuario autor;

    @Column(nullable = false, length = 2000)
    private String texto;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    public static Comentario registrar(Chamado chamado, Usuario autor, String texto, Instant agora) {
        Comentario comentario = new Comentario();
        comentario.chamado = chamado;
        comentario.autor = autor;
        comentario.texto = texto;
        comentario.criadoEm = agora;
        return comentario;
    }

    @Override
    public boolean equals(Object outro) {
        if (this == outro) {
            return true;
        }
        return outro instanceof Comentario comentario && id != null && Objects.equals(id, comentario.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
