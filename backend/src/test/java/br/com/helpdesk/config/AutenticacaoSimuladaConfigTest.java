package br.com.helpdesk.config;

import br.com.helpdesk.HelpdeskApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guarda do login simulado (SEG-01): o perfil prod nunca sobe com ele ligado; os demais
 * ambientes continuam podendo usa-lo.
 */
@DisplayName("AutenticacaoSimuladaConfig")
class AutenticacaoSimuladaConfigTest {

    private static MockEnvironment ambiente(String perfil, String simulada) {
        MockEnvironment ambiente = new MockEnvironment();
        if (perfil != null) {
            ambiente.setActiveProfiles(perfil);
        }
        if (simulada != null) {
            ambiente.setProperty(AutenticacaoSimuladaConfig.PROPRIEDADE, simulada);
        }
        return ambiente;
    }

    @Test
    @DisplayName("prod com o login simulado ligado nao sobe")
    void deveRecusarProdComLoginSimulado() {
        assertThatThrownBy(() -> AutenticacaoSimuladaConfig.verificar(ambiente("prod", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod")
                .hasMessageContaining(AutenticacaoSimuladaConfig.PROPRIEDADE);
    }

    @Test
    @DisplayName("prod com o login simulado desligado, ou sem a propriedade, sobe")
    void devePermitirProdSemLoginSimulado() {
        assertThatCode(() -> AutenticacaoSimuladaConfig.verificar(ambiente("prod", "false"))).doesNotThrowAnyException();
        assertThatCode(() -> AutenticacaoSimuladaConfig.verificar(ambiente("prod", null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev e test continuam podendo usar o login simulado")
    void devePreservarOMecanismoNosAmbientesDoMvp() {
        assertThatCode(() -> AutenticacaoSimuladaConfig.verificar(ambiente("dev", "true"))).doesNotThrowAnyException();
        assertThatCode(() -> AutenticacaoSimuladaConfig.verificar(ambiente("test", "true"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sem a propriedade o login simulado fica desligado: o padrao e o seguro")
    void deveFicarDesligadoPorPadrao() {
        assertThat(AutenticacaoSimuladaConfig.habilitada(ambiente(null, null))).isFalse();
        assertThat(AutenticacaoSimuladaConfig.habilitada(ambiente("dev", "true"))).isTrue();
    }

    /**
     * A aplicacao inteira, nao so a classe: prova que a guarda esta registrada
     * ({@code META-INF/spring.factories}) e recusa <b>antes</b> de qualquer bean. Por isso o
     * motivo que chega e o nosso, e nao a validacao de schema do perfil prod sobre um H2 vazio,
     * que nem chega a rodar.
     */
    @Test
    @DisplayName("a aplicacao real nao inicia em prod com helpdesk.auth.simulada=true")
    void deveImpedirAInicializacaoDaAplicacaoEmProd() {
        SpringApplicationBuilder aplicacao = new SpringApplicationBuilder(HelpdeskApplication.class)
                .profiles("prod");
        // Como argumento de linha de comando: precedencia maior que o application.yml do prod.
        String[] argumentos = {"--" + AutenticacaoSimuladaConfig.PROPRIEDADE + "=true", "--server.port=0"};

        // Sem causa encadeada: a recusa e a primeira coisa que acontece, nao embrulha outro erro.
        assertThatThrownBy(() -> aplicacao.run(argumentos))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageContaining("nao pode subir com " + AutenticacaoSimuladaConfig.PROPRIEDADE + "=true");
    }
}
