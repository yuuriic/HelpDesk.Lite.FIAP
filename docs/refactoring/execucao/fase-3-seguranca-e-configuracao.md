# Fase 3 — Segurança e configuração (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §7](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix`: muda comportamento; os testes de defeito foram substituídos por testes de contrato |
| Tratados | SEG-01 (parte mínima), SEG-02, SEG-03, SEG-04, TST-02, que são os **necessários** da fase |
| Não tratados, de propósito | SEG-05 e SEG-07, classificados como **opcionais** no plano (ver §5) |

Não entraram Spring Security, JWT nem nenhum mecanismo novo de autenticação.

---

## 1. Problemas corrigidos

| ID | Antes | Depois |
|---|---|---|
| **SEG-02** | `spring.profiles.default: dev`: um `java -jar` sem perfil subia **aberto**, com `/actuator/env`, `/actuator/loggers` (aceita POST para trocar o nível de log), console H2, Swagger e `X-User-Id` aceito | A configuração **sem perfil é a mais fechada**: Actuator só com `health` (sem detalhes) e `prometheus`, H2 e Swagger desligados, login simulado desligado. O `dev` fica explícito (o `./mvnw spring-boot:run` o ativa) e **perdeu `env` e `loggers`** |
| **SEG-01** | O perfil `prod` desligava H2 e Swagger, mas continuava aceitando qualquer `X-User-Id`: quem soubesse um id virava admin | O `prod` **não sobe** com `helpdesk.auth.simulada=true`, venha a flag do YAML, da linha de comando ou de variável de ambiente. A recusa acontece antes de qualquer bean. A flag de fato desliga o mecanismo: desligada, toda rota protegida responde 401 e `/api/auth/**` não existe. `dev` e `test` mantêm o login simulado do MVP |
| **SEG-03** | `GET /api/auth/usuarios-disponiveis` (público) devolvia o **e-mail** de todos os usuários ativos. `POST /api/auth/login` (público) devolvia o e-mail do dono de qualquer id informado, e o frontend o guardava no `localStorage` | As duas rotas públicas devolvem só **`id`, `nome` e `perfil`**. A listagem administrativa (`GET /api/usuarios`, exige ADMIN) continua com e-mail |
| **SEG-04** | "Ja existe um usuario com o e-mail ana@empresa.com": no corpo do 422 **e** no log WARN, contra a regra da §4.11 | "Ja existe um usuario com este e-mail". Nenhum e-mail no corpo nem no log |
| **TST-02** | Os `@WebMvcTest` subiam no perfil `dev` por causa do default | Declaram `@ActiveProfiles("test")`, como as demais suítes |

---

## 2. Medidas adotadas

### Perfis (`application.yml` e `pom.xml`)

| Perfil | Actuator | H2 / Swagger | Login simulado |
|---|---|---|---|
| nenhum | `health` (sem detalhes), `prometheus` | desligados | **desligado** |
| `dev` | `health` (detalhado), `info`, `metrics`, `prometheus`, **sem `env`/`loggers`** | ligados | ligado |
| `test` | como a base | desligados | ligado |
| `prod` | `health` (sem detalhes), `prometheus` | desligados | **proibido**: não sobe com a flag ligada |

O `spring-boot-maven-plugin` ativa `dev` no `spring-boot:run`, então a forma documentada de rodar o MVP (README e `docs/MVP.md`) continua igual. A verificação mostrou `The following 1 profile is active: "dev"`.

### Guarda do login simulado ([AutenticacaoSimuladaConfig.java](../../../backend/src/main/java/br/com/helpdesk/config/AutenticacaoSimuladaConfig.java))

- É um `EnvironmentPostProcessor`, registrado em [META-INF/spring.factories](../../../backend/src/main/resources/META-INF/spring.factories). Roda assim que a configuração é lida, **antes de banco, JPA e servidor**.
- **Por que não `@Configuration`:** a primeira versão era uma `@Configuration` e só rodava no meio da criação dos beans. No teste, a aplicação caiu antes na validação de schema do Hibernate, e o motivo real ficou escondido. Com o `EnvironmentPostProcessor`, a recusa sai em ~0,5 s, sem nenhuma exceção envolvendo, e nenhum pool de conexões ou Tomcat chega a iniciar (verificado no jar).
- A mesma classe expõe `habilitada(Environment)`, que o `UsuarioAtualFilter` consulta. O `AuthController` usa `@ConditionalOnProperty` com a mesma propriedade.

### Arquivos alterados

| Arquivo | Mudança |
|---|---|
| [application.yml](../../../backend/src/main/resources/application.yml) | Base fechada; `dev` com o que o MVP usa, sem `env`/`loggers`; `helpdesk.auth.simulada` por perfil |
| [pom.xml](../../../backend/pom.xml) | `spring-boot:run` ativa o perfil `dev` |
| [AutenticacaoSimuladaConfig.java](../../../backend/src/main/java/br/com/helpdesk/config/AutenticacaoSimuladaConfig.java) + `spring.factories` | **Novos.** Guarda do SEG-01 e consulta da flag |
| [UsuarioAtualFilter.java](../../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtualFilter.java) | Com a flag desligada, 401 "Autenticacao nao disponivel neste ambiente" sem nem ler o header |
| [AuthController.java](../../../backend/src/main/java/br/com/helpdesk/web/controller/AuthController.java) | Só é registrado com a flag ligada; as duas rotas devolvem `UsuarioResumoResponse` |
| [UsuarioService.java](../../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java) | `listarDisponiveisParaLogin` e `autenticar` devolvem o resumo; mensagem de e-mail duplicado sem o e-mail |
| [LoginPage.vue](../../../frontend/src/pages/LoginPage.vue) | Sai a legenda com o e-mail (a rota não o traz mais; o perfil já aparece no badge) |
| [.ai/architecture.md](../../../.ai/architecture.md) §5.2 | Tabela de perfis atualizada (fonte que prevalece). A linha equivalente do `docs/ARQUITETURA.md` §4.11 fica para a Fase 9 (DOC) |

---

## 3. Testes

### Testes de defeito substituídos (2 removidos de `DefeitosConhecidosIT`)

Os dois foram executados contra o código corrigido e **falharam** antes de serem apagados.

| Removido | Substituído por |
|---|---|
| `rotaPublicaExpoeEmail` (SEG-03) | `DadosPessoaisIT.deveListarSoOResumoNaRotaPublica`, `deveDevolverSoOResumoNoLogin` |
| `emailApareceNoLog` (SEG-04) | `DadosPessoaisIT.naoDeveExporOEmailDuplicadoNaCriacao`, `...NaAtualizacao` |

### Testes novos (20)

| Arquivo | Testes | O que garante |
|---|:---:|---|
| [DadosPessoaisIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/DadosPessoaisIT.java) | 5 | Rotas públicas com **exatamente** `id`, `nome`, `perfil`; listagem administrativa ainda com e-mail; e-mail duplicado fora do corpo e do log, na criação e na atualização |
| [AutenticacaoSimuladaConfigTest.java](../../../backend/src/test/java/br/com/helpdesk/config/AutenticacaoSimuladaConfigTest.java) | 5 | `prod` + flag ligada é recusado; `prod` com a flag desligada ou ausente sobe; `dev`/`test` mantêm o mecanismo; o padrão é desligado; a **aplicação real** não inicia em `prod` com a flag, e a recusa é a primeira coisa que acontece (sem causa encadeada) |
| [configuracao/ConfiguracaoSemPerfilIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/configuracao/ConfiguracaoSemPerfilIT.java) | 3 | Servidor real: Actuator mínimo, H2 e Swagger 404, `X-User-Id` recusado com 401, `/api/auth/**` 404 |
| [configuracao/ConfiguracaoDevIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/configuracao/ConfiguracaoDevIT.java) | 4 | Servidor real: `env` e `loggers` 404, **inclusive o POST em loggers**; health detalhado, info, metrics, prometheus, Swagger, H2 e login simulado com o seed funcionando |
| [configuracao/ConfiguracaoProdIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/configuracao/ConfiguracaoProdIT.java) | 3 | Servidor real: 401 com `X-User-Id`, rotas de auth 404, superfície mínima do Actuator, H2 e Swagger 404 |

Os três testes de configuração usam `@AutoConfigureObservability`. Sem ela, o `@SpringBootTest` desliga a exportação de métricas e o `/actuator/prometheus` some. Isso é artefato do ambiente de teste, não da configuração. Cada teste usa um banco H2 próprio, para não interferir nas outras suítes.

### Testes existentes adaptados (5, todos citando o ID)

| Teste | Por quê |
|---|---|
| `ChamadoControllerTest`, `TratamentoDeErrosWebTest` | TST-02: `@ActiveProfiles("test")`. Sem o default `dev`, o login simulado estaria desligado na fatia web |
| `UsuarioServiceTest.deveListarSomenteUsuariosAtivosNaTelaDeLogin` | SEG-03: o retorno passa a ser `UsuarioResumoResponse` |
| `CadastrosApiIT.Login.deveListarSoUsuariosAtivos` | SEG-03: conferia o inativo por `$[*].email`, que deixou de existir. A asserção ficaria **vazia** e passaria sempre; agora confere pelo id |
| `CadastrosApiIT.Login.deveEntrarComUsuarioAtivo` | SEG-03: não há mais `ativo` na resposta pública; passa a garantir que não há `email` |

---

## 4. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 233 ✓ | **251 ✓** (−2 de defeito, +20), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 71 ✓ | **71 ✓** |
| Cobertura de linhas, backend | 95,6% | **95,7%** |
| Cobertura de branches, backend | 71,8% | **72,6%** |
| `AutenticacaoSimuladaConfig` | — | **100%** de linhas e branches |
| Testes `[DEFEITO CONHECIDO]` restantes | 9 | **7** (backend: REG-03, CON-03, OBS-01; frontend: EXC-03, FE-04, FE-02 ×2) |

### Verificação no jar empacotado

```
1) java -jar (sem perfil)
   /actuator/env 404 · /actuator/loggers 404 · /actuator/health 200
   /h2/ 404 · /v3/api-docs 404 · /api/auth/usuarios-disponiveis 404
   GET /api/usuarios com X-User-Id: 3 -> 401
2) --spring.profiles.active=dev
   /actuator/env 404 · /actuator/loggers 404 · /actuator/prometheus 200
   /h2/ 200 · /v3/api-docs 200 · GET /api/usuarios com X-User-Id: 3 -> 200
   usuarios-disponiveis -> [{"id":1,"nome":"Ana Solicitante","perfil":"SOLICITANTE"}, ...]
   login {"usuarioId":3} -> {"id":3,"nome":"Carla Admin","perfil":"ADMIN"}
   e-mail duplicado no log: 0 ocorrencias
3) --spring.profiles.active=prod --helpdesk.auth.simulada=true
   IllegalStateException: O perfil 'prod' nao pode subir com helpdesk.auth.simulada=true
   pool de conexoes / Tomcat iniciados: 0
4) HELPDESK_AUTH_SIMULADA=true (variavel de ambiente) + prod -> recusado da mesma forma
5) ./mvnw spring-boot:run -> The following 1 profile is active: "dev"; X-User-Id aceito (200)
```

---

## 5. Riscos que permanecem fora do escopo

| Risco | Situação | Por que fica |
|---|---|---|
| **Personificação por `X-User-Id` em `dev`/`test`** | Qualquer cliente que alcance a API em `dev` se passa por qualquer usuário, inclusive admin | É o mecanismo do MVP (ADR-003). Autenticação real é funcionalidade nova, excluída da atividade |
| **`prod` sem autenticação** | Com a flag desligada, o `prod` sobe, mas **toda rota protegida responde 401**. Ele é seguro, mas não é utilizável | Consequência honesta do SEG-01: sem Spring Security/JWT/OIDC não há como autenticar. É o próximo passo previsto na ADR-003 |
| **SEG-05: `X-Request-Id` sem validação** | O valor do cliente vai para o MDC, para o log e para o header de resposta, sem limite de tamanho ou formato (poluição ou forja de log) | Opcional no plano. Correção sugerida: aceitar só `^[A-Za-z0-9-]{1,64}$` |
| **SEG-07: `sort` sem lista branca** | Um campo inexistente já responde 400 (Fase 1), mas campos de associação válidos, como `solicitante.email`, podem ser usados para ordenar | Opcional no plano. Quem ordena já enxerga aqueles chamados; o risco é inferir a ordem alfabética de e-mails |
| **SEG-08: perfil desatualizado no `localStorage`** | Depois de uma troca de perfil, o menu do frontend fica desatualizado até o próximo login. O backend barra as ações | Opcional, previsto para a Fase 7 (frontend) |
| `/actuator/prometheus` sem autenticação na base e em `prod` | As métricas de negócio ficam legíveis por quem alcança a porta | Decisão documentada: "atrás da rede interna" (§4.11). Proteger exigiria Spring Security |
| Console H2 em `dev` com `sa` sem senha | Acessível só em `localhost` (`web-allow-others: false`) | Ambiente de desenvolvimento, banco em memória e descartável |
| `CorsConfig` ativo em todos os perfis | Libera só `http://localhost:9000`, sem credenciais | Sem impacto prático; a divergência com a documentação é o DOC-04, na Fase 9 |
