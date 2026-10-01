package br.com.helpdesk.arquitetura;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direcao das dependencias entre camadas (ARQ-01, Fase 6; ADR-002, secao 4.1 da arquitetura):
 *
 * <pre>
 *   web  ->  service  ->  domain  <-  repository
 *              \-----------------------^
 *   config: raiz de composicao, pode conhecer todos
 * </pre>
 *
 * <p>Le o codigo-fonte de {@code src/main/java} -- imports e nomes totalmente qualificados no
 * corpo -- e falha quando uma camada passa a depender de outra que ela nao pode conhecer. Sem
 * biblioteca nova (ArchUnit): as regras sao poucas e a leitura do fonte basta.</p>
 */
@DisplayName("Dependencias entre camadas (arquitetura)")
class DependenciaEntreCamadasTest {

    private static final Path FONTES = Path.of("src", "main", "java");
    private static final String RAIZ = "br.com.helpdesk.";
    private static final Pattern PACOTE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern REFERENCIA = Pattern.compile("\\b(br\\.com\\.helpdesk\\.[a-z][\\w.]*?)\\.[A-Z]\\w*");
    private static final Pattern SPRING = Pattern.compile("\\bimport (?:static )?org\\.springframework\\.");

    /**
     * Unica excecao a "web nao acessa repository": o filtro do login simulado carrega o usuario
     * do X-User-Id direto do banco, como desenha o diagrama de sequencia da secao 4.6
     * ({@code FI->>DB: SELECT usuario}) e a ADR-003 (o filter e o unico ponto que conhece o
     * mecanismo). Controllers continuam proibidos de acessar repositorio.
     */
    private static final Set<String> FILTRO_DO_LOGIN_SIMULADO = Set.of("br.com.helpdesk.web.filter.UsuarioAtualFilter");

    private record Fonte(String classe, String pacote, Set<String> pacotesUsados, boolean importaSpring) {

        String camada() {
            return camadaDe(pacote);
        }

        boolean usa(String camada) {
            return pacotesUsados.stream().anyMatch(usado -> camadaDe(usado).equals(camada));
        }
    }

    private static List<Fonte> fontes;

    @BeforeAll
    static void lerFontes() throws IOException {
        assertThat(FONTES).as("rodar a partir de backend/ (diretorio do pom)").isDirectory();
        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            fontes = arquivos.filter(arquivo -> arquivo.toString().endsWith(".java"))
                    .map(DependenciaEntreCamadasTest::ler)
                    .toList();
        }
    }

    private static Fonte ler(Path arquivo) {
        try {
            String texto = Files.readString(arquivo);
            Matcher pacote = PACOTE.matcher(texto);
            assertThat(pacote.find()).as("package em %s", arquivo).isTrue();
            String nomePacote = pacote.group(1);

            Set<String> usados = new TreeSet<>();
            Matcher referencia = REFERENCIA.matcher(texto.substring(pacote.end()));
            while (referencia.find()) {
                usados.add(referencia.group(1));
            }
            String classe = nomePacote + "." + arquivo.getFileName().toString().replace(".java", "");
            return new Fonte(classe, nomePacote, usados, SPRING.matcher(texto).find());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code br.com.helpdesk.service.mapper} -> {@code service}; {@code br.com.helpdesk} -> {@code (raiz)}. */
    private static String camadaDe(String pacote) {
        if (!pacote.startsWith(RAIZ)) {
            return "(raiz)";
        }
        return pacote.substring(RAIZ.length()).split("\\.")[0];
    }

    private static List<String> violacoes(String camada, String proibida, Set<String> excecoes) {
        List<String> encontradas = new ArrayList<>();
        for (Fonte fonte : fontes) {
            if (fonte.camada().equals(camada) && fonte.usa(proibida) && !excecoes.contains(fonte.classe())) {
                encontradas.add(fonte.classe() + " -> " + fonte.pacotesUsados().stream()
                        .filter(usado -> camadaDe(usado).equals(proibida)).toList());
            }
        }
        return encontradas;
    }

    // ------------------------------------------------------------------ sanidade do leitor

    @Test
    @DisplayName("o leitor de fontes enxerga as camadas e as dependencias reais")
    void deveEnxergarAsDependenciasReais() {
        assertThat(fontes).extracting(Fonte::camada)
                .contains("web", "service", "domain", "repository", "config");
        Fonte controller = fontes.stream()
                .filter(fonte -> fonte.classe().equals("br.com.helpdesk.web.controller.ChamadoController"))
                .findFirst().orElseThrow();
        assertThat(controller.usa("service")).isTrue();
    }

    // ------------------------------------------------------------------ regras

    @Test
    @DisplayName("service nao depende de web (ARQ-01): DTOs de saida e entradas dos casos de uso vivem em service.dto")
    void serviceNaoDependeDeWeb() {
        assertThat(violacoes("service", "web", Set.of())).isEmpty();
    }

    @Test
    @DisplayName("service nao depende de config")
    void serviceNaoDependeDeConfig() {
        assertThat(violacoes("service", "config", Set.of())).isEmpty();
    }

    @Test
    @DisplayName("domain nao depende de nenhuma outra camada do sistema")
    void dominioNaoDependeDeOutrasCamadas() {
        for (String outra : List.of("web", "service", "repository", "config")) {
            assertThat(violacoes("domain", outra, Set.of())).as("domain -> %s", outra).isEmpty();
        }
    }

    @Test
    @DisplayName("domain nao conhece Spring (ARQ-02)")
    void dominioNaoConheceSpring() {
        assertThat(fontes.stream().filter(fonte -> fonte.camada().equals("domain") && fonte.importaSpring())
                .map(Fonte::classe)).isEmpty();
    }

    @Test
    @DisplayName("repository so conhece o dominio")
    void repositorioSoConheceODominio() {
        for (String outra : List.of("web", "service", "config")) {
            assertThat(violacoes("repository", outra, Set.of())).as("repository -> %s", outra).isEmpty();
        }
    }

    @Test
    @DisplayName("web so acessa repository pelo filtro do login simulado; controllers nunca")
    void webNaoAcessaRepositorio() {
        assertThat(violacoes("web", "repository", FILTRO_DO_LOGIN_SIMULADO)).isEmpty();
    }
}
