package br.com.helpdesk.domain;

import br.com.helpdesk.domain.enums.Perfil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tabela de capacidades por perfil (DOM-03). E a fonte das perguntas que PermissaoService,
 * ChamadoSpecification e UsuarioService fazem -- mudar uma linha aqui muda a permissao em todos.
 */
@DisplayName("Perfil (capacidades)")
class PerfilTest {

    @ParameterizedTest(name = "{0}: enxerga todos={1}, atende={2}, gerencia cadastros={3}")
    @CsvSource({
            "SOLICITANTE, false, false, false",
            "ATENDENTE,   true,  true,  false",
            "ADMIN,       true,  true,  true"
    })
    void deveDeclararAsCapacidadesDaTabelaDePermissoes(Perfil perfil, boolean enxergaTodos,
                                                        boolean atende, boolean gerenciaCadastros) {
        assertThat(perfil.enxergaTodosOsChamados()).isEqualTo(enxergaTodos);
        assertThat(perfil.atendeChamados()).isEqualTo(atende);
        assertThat(perfil.gerenciaCadastros()).isEqualTo(gerenciaCadastros);
    }
}
