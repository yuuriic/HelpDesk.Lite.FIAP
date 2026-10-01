package br.com.helpdesk.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Liga e desliga o login simulado por {@code X-User-Id} (ADR-003) e impede que ele chegue
 * a producao (SEG-01).
 *
 * <p>A propriedade {@value #PROPRIEDADE} vale {@code true} nos perfis {@code dev} e
 * {@code test} -- onde o mecanismo faz parte do MVP -- e {@code false} na configuracao base e
 * no {@code prod}. Desligada, o {@code UsuarioAtualFilter} recusa toda rota protegida com 401 e
 * o {@code AuthController} nem e registrado.</p>
 *
 * <p>Com o perfil {@code prod} ativo e a propriedade ligada (por exemplo, por variavel de
 * ambiente), a aplicacao <b>nao sobe</b>: a identidade seria apenas um numero no header, e
 * qualquer cliente viraria admin. Autenticacao real esta fora do escopo do MVP.</p>
 *
 * <p>E um {@link EnvironmentPostProcessor} (registrado em {@code META-INF/spring.factories})
 * para a recusa acontecer assim que a configuracao e lida, antes de qualquer bean -- banco,
 * JPA, servidor. Como {@code @Configuration}, ela so rodaria no meio da criacao dos beans, e
 * outro erro de inicializacao poderia esconder o motivo real.</p>
 */
public class AutenticacaoSimuladaConfig implements EnvironmentPostProcessor, Ordered {

    public static final String PROPRIEDADE = "helpdesk.auth.simulada";
    static final String PERFIL_PRODUCAO = "prod";

    /** O login simulado esta ligado neste ambiente? Desligado quando a propriedade nao existe. */
    public static boolean habilitada(Environment ambiente) {
        return ambiente.getProperty(PROPRIEDADE, Boolean.class, false);
    }

    /** @throws IllegalStateException se o perfil prod estiver ativo com o login simulado ligado */
    static void verificar(Environment ambiente) {
        if (habilitada(ambiente) && ambiente.acceptsProfiles(Profiles.of(PERFIL_PRODUCAO))) {
            throw new IllegalStateException("O perfil '" + PERFIL_PRODUCAO + "' nao pode subir com " + PROPRIEDADE
                    + "=true: o login simulado por X-User-Id nao autentica ninguem de verdade");
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
        verificar(ambiente);
    }

    /** Depois de todos os outros: precisa enxergar o application.yml e os perfis ja carregados. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
