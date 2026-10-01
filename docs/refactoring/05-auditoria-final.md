# HelpDesk Lite — Auditoria técnica final (pós-modernização)

| Item | Valor |
|---|---|
| Comparação | **Antes:** `main` (`c5f4335`), o código auditado em [01-auditoria-tecnica.md](01-auditoria-tecnica.md). **Depois:** branch `refactoring`, diretório de trabalho ao fim da Fase 9 (sem commits) |
| Data | 01/10/2026 |
| Natureza | Somente diagnóstico. **Nenhum arquivo de código foi alterado** nesta etapa |
| Atualização | Depois desta auditoria, o **N-01** foi corrigido numa correção pontual ([correcao-n01-concorrencia-usuarios.md](execucao/correcao-n01-concorrencia-usuarios.md)). As classificações de N-01, REG-01 e REG-02 abaixo refletem essa correção; as medições das §2.1–§2.7 são as da data desta auditoria, e as demais dívidas não mudaram |
| Evidência | Só o que foi medido ou reproduzido. Os dois lados foram medidos **hoje, com a mesma ferramenta e o mesmo comando**, salvo onde a tabela indica "auditoria inicial". Não há estimativa de melhoria em percentual |

---

## 1. Método

Os critérios são os da auditoria inicial (§2 do documento 01), repetidos sobre os dois lados.

| Técnica | Como foi feita nesta auditoria |
|---|---|
| Lado "antes" | `git archive main` extraído numa pasta temporária fora do repositório; o jar da `main` foi compilado lá (`mvn -DskipTests package`) e executado. Nenhum *branch*, *worktree* ou índice do repositório foi alterado |
| Complexidade ciclomática | lizard **1.24.0** (o mesmo da auditoria inicial), Java + JS + Vue, testes excluídos, nos dois lados |
| Duplicação | Extensão `-Eduplicate` do mesmo lizard, nos dois lados |
| Dependências entre pacotes | Script que lê os `import br.com.helpdesk.*` de `src/main`, nos dois lados |
| Contagens de *smell* | `grep` por expressão regular (setters, comparações de perfil, `Instant.now()`, `catch` vazio...), nos dois lados |
| Verificação empírica | Os dois jars executados localmente (H2 em memória, descartado) e sondados com `curl`. Consultas contadas pelo log `org.hibernate.SQL` do perfil `dev`, filtrado pelo `X-Request-Id` de cada requisição. Concorrência reproduzida com requisições paralelas |
| Testes e cobertura | `./mvnw verify` (relatórios apagados antes) e `npx vitest run` no lado "depois". Os números da `main` são os da auditoria inicial |
| Código morto | Busca de cada método público de `src/main` sem referência em `src/main` |

**Marcação.** **[confirmado]** = reproduzido em execução nesta auditoria; **[por inspeção]** =
leitura de código.

---

## 2. Comparativo técnico Antes × Depois

### 2.1 Testes e cobertura

| Métrica | Antes | Depois |
|---|---|---|
| Testes do backend | 151, 0 falhas (auditoria inicial) | **291**, 0 falhas, 0 erros, 0 ignorados |
| Testes do frontend | 49 em 4 arquivos (auditoria inicial) | **86** em 9 arquivos, 0 falhas |
| Testes `[DEFEITO CONHECIDO]` / `@Tag("defeito-conhecido")` | — (criados na baseline: 21) | **0**; `-Dgroups=defeito-conhecido` executa 0 testes |
| `quasar build` | — | Sucesso |
| Cobertura de linhas, total do backend | 92,1% | **97,9%** (661/675) |
| Cobertura de *branches*, total do backend | 67,1% (medida na baseline) | **79,7%** (149/187) |
| `domain` + `service` (gate ≥ 80%) | 95,0% | **97,7%** linhas · 74,3% *branches*; gate aprovado |

Cobertura de linhas por pacote:

| Pacote | Antes | Depois |
|---|:---:|:---:|
| `web.controller` | **50,0%** | 100% |
| `web.exception` | 80,6% | 100% |
| `web.filter` | 92,3% | 98,1% |
| `domain.model` | **17,4%** | 82,1% (*branches* 33,3%: `equals` e parte dos `@Builder` gerados) |
| `service` | 100% | 100% (*branches* 97,4%) |
| `domain.state`, `repository`, `config` | 100% | 100% |
| `domain.permissao`, `domain.sla`, `service.dto` (novos) | — | 100% |
| `service.mapper` | (em `service`) | 98,1% |

### 2.2 Complexidade ciclomática (lizard 1.24.0)

| Métrica | Antes | Depois |
|---|:---:|:---:|
| Funções analisadas | 247 | 283 |
| CCN médio | 1,74 | 1,71 |
| Funções com CCN > 10 | **1** (`ChamadoAcoes.vue`, `acoes`, CCN 12) | **0** |
| CCN = 10 | 1 (`services/chamados.js listar`) | 1 (o mesmo, sem alteração) |
| Maior CCN do backend | 6 | 7 |
| Maior CCN em `ChamadoAcoes.vue` | 12 | 3 |

O número de funções subiu (+36), e o CCN médio ficou estável. Funções cujo CCN **aumentou**
(todas ≤ 10):

| Função | Antes | Depois | Motivo |
|---|:---:|:---:|---|
| `api.js`, interceptor de resposta | 5 | 8 | 401 que encerra a sessão (EXC-03) |
| `PermissaoService.verificarAlteracaoStatus` | 6 | 7 | Capacidades do `Perfil` (DOM-03) |
| `UsuarioAtualFilter.doFilterInternal` | 5 | 6 | Login simulado desligável (SEG-01) |
| `GlobalExceptionHandler.erroPorStatus` | — | 6 | Novo: erros do Spring MVC (EXC-01) |
| `UsuarioService.atualizar` | — | 5 | Guardas de REG-01/REG-02 |

### 2.3 Duplicação (lizard `-Eduplicate`)

| Métrica | Antes | Depois |
|---|:---:|:---:|
| Taxa de duplicação total | 9,88% | 6,83% |
| Blocos duplicados | 20 | 14 |

Os blocos restantes são cabeçalhos de `import` e anotações repetidos entre controllers
(`Categoria` × `Usuario`, `Categoria` × `Chamado`) e entre `Categoria` e `Usuario` (estrutura de
entidade). O lizard não acusou duplicação nos `.vue`; a duplicação de constantes do frontend
foi medida por `grep` (§2.5).

### 2.4 Dependências entre camadas (imports de `br.com.helpdesk.*`)

| Origem → destino | Antes | Depois |
|---|:---:|:---:|
| `service → web` | **17** | **0** |
| `web → service` | 5 | 15 (`*Response` e `Dados*` agora vêm de `service.dto`) |
| `web → domain` | 20 | 13 |
| `service → domain` | 36 | 49 |
| `repository → domain` | 8 | 10 |
| `domain →` qualquer camada | 0 | 0 |
| `domain →` Spring | 0 arquivos | 0 arquivos |
| `web.filter → repository` | 1 | 1 (exceção documentada) |
| `config → web` | 1 | 3 |
| **`web → config`** | **0** | **2** (novo; ver N-02) |
| Classes em `src/main` | 58 | 65 |

### 2.5 Contagens de *smell*

| Indicador | Antes | Depois |
|---|:---:|:---:|
| `@Setter` nas entidades | 4 | 2 (`Usuario`, `Categoria`) |
| `chamado.setStatus/Atendente/...` fora do domínio | 9 | 0 |
| Comparações `== Perfil.X` no backend | 11 | 3 (todas em `UsuarioService`, ver N-03) |
| Comparações `=== 'ADMIN'` etc. no frontend | 10 | 4 (`auth.js` ×3, `ChamadoFiltros` ×1) |
| `Instant.now()` em `src/main` | 2 (`ErroResponse`) | 2 (os mesmos; EXC-04) |
| `.catch(() => {})` no frontend | 10 | 12 (padrão adotado na Fase 7) |
| `date.formatDate` no frontend | 4 | 1 (`utils/formatacao.js`) |
| Rótulo `'Admin'` divergente | 2 | 0 |
| *Fallback* `localhost:8080/api` | 2 | 1 |
| Dependências injetadas no `ChamadoService` | 9 | 9 |
| Linhas do `ChamadoService` | 245 | 199 |
| Linhas do `UsuarioService` / `Chamado` / `GlobalExceptionHandler` | 119 / 114 / 109 | 205 / 226 / 215 |
| Métodos públicos sem uso em `src/main` (excluindo *overrides* do framework) | 9 símbolos (DOM-05) | 1: `TransicaoStatus.permitida`, oráculo dos testes, mantido de propósito |

### 2.6 Comportamento HTTP [confirmado]

Requisições idênticas contra os dois jars:

| Requisição | Antes | Depois |
|---|:---:|:---:|
| `GET /api/nao-existe` | 500 | 404 `RECURSO_NAO_ENCONTRADO` |
| `DELETE /api/chamados/1` | 500 | 405 `METODO_NAO_SUPORTADO` |
| `POST /api/chamados` com `text/plain` | 500 | 415 `MIDIA_NAO_SUPORTADA` |
| `GET /api/chamados?sort=naoExiste,asc` | 500 | 400 `VALIDACAO` |
| `POST /api/auth/login` com id inexistente | 404 | 401 "Usuario invalido ou inativo" |
| `X-User-Id: 999` | 401 com mensagem que revela o motivo (auditoria inicial) | 401 "Usuario invalido ou inativo" |
| Linhas `ERROR` geradas pelas sondas acima | 4 | 0 |
| `/actuator/env`, `/actuator/loggers` (`java -jar` sem perfil) | 200, 200 | 404, 404 |
| `/h2`, `/swagger-ui.html` (sem perfil) | 302, 302 | 404, 404 |
| `/api/auth/usuarios-disponiveis` (sem perfil) | 200 com e-mail | 404 (login simulado desligado) |
| `/api/auth/usuarios-disponiveis` (perfil `dev`) | id, nome, **e-mail**, perfil, ativo | id, nome, perfil |
| `prod` com `helpdesk.auth.simulada=true` | Sobe | **Não sobe** (`IllegalStateException`, exit 1) |
| `PUT /api/usuarios/2` → SOLICITANTE (atendente do chamado 4) | 200 (auditoria inicial) | 422 `REGRA_NEGOCIO` |
| `PUT /api/usuarios/3` → SOLICITANTE (único admin) | 200 (auditoria inicial) | 422 `REGRA_NEGOCIO` |
| `PUT /api/categorias/4` com `"ativa": true` (desativada) | — | 200, `ativa: false` (contrato REG-03) |
| E-mail em linhas `INFO`/`WARN`/`ERROR` | Sim (auditoria inicial) | Não (0 ocorrências) |

### 2.7 Consultas por requisição [confirmado]

Mesmo seed, mesmo método de contagem nos dois lados:

| Requisição | Antes | Depois |
|---|:---:|:---:|
| `GET /api/chamados?size=20` (admin) | **9** | **2** |
| `GET /api/chamados` (solicitante) | 7 | 2 |
| `GET /api/chamados?size=3&page=1` (com `count`) | 8 | 3 |
| `GET /api/chamados/4` (detalhe) | 3 | 3 |
| `GET /api/chamados/4/comentarios` | 3 | 3 |
| `GET /api/dashboard/resumo` | 2 | 2 |
| `PATCH /api/chamados/1/assumir` | 4 | 4 |

Na medição de hoje, o detalhe e a lista de comentários têm 3 consultas **nos dois lados**. O
relatório da Fase 4 registrou 4 → 3 nesses dois casos. A diferença vem do cenário (autores que
já estavam no contexto de persistência), e a contagem de hoje não confirma ganho nesses dois.

### 2.8 Concorrência [confirmado]

| Cenário | Antes | Depois |
|---|---|---|
| CON-01: 15 corridas de duas assunções simultâneas do mesmo chamado | 19 respostas 200 e 11 respostas 422. Em **4 corridas os dois atendentes receberam 200** | 15 respostas 200 (uma por corrida); o perdedor recebe 409 (10) ou 422 (5) |
| REG-01 sob concorrência: dois admins rebaixam um ao outro ao mesmo tempo | — | **Os dois `PUT` responderam 200 na 1ª tentativa e o sistema ficou sem nenhum admin** (`GET /api/usuarios` → 403 para ambos). Ver N-01 |
| Mesmo cenário, depois da correção do N-01 (20 tentativas pela API) | — | **Sempre exatamente um 200**: 16 × 200/422, 2 × 422/200, 2 × 200/403; nunca sem admin; 0 linhas ERROR |

---

## 3. Destino dos achados da auditoria inicial

Categorias: **Corrigido** (o problema deixou de existir, com evidência); **Mitigado** (a parte
principal foi tratada e resta uma parte identificada); **Reclassificado** (por decisão
arquitetural registrada); **Dívida técnica** (continua, sem decisão de mantê-lo como está);
**Fora do escopo** (só se resolve com funcionalidade nova, excluída da atividade).

### 3.1 Arquitetura, domínio e SOLID

| ID | Sev. | Destino | Evidência / o que resta |
|---|---|---|---|
| ARQ-01 | Média | **Corrigido** | `service → web`: 17 → 0 imports; `DependenciaEntreCamadasTest` falha o build se voltar |
| ARQ-02 | Média | **Corrigido** | `PermissaoService` e `SlaCalculator` em `domain.permissao`/`domain.sla`, sem Spring (0 arquivos de `domain` importam Spring) |
| ARQ-03 | Média | **Mitigado** | Três implementações viraram duas que fazem a mesma pergunta (`Perfil.enxergaTodosOsChamados()`): o predicado da `ChamadoSpecification` e o `podeVer` do `PermissaoService`; `verificarComentario` reusa `podeVer`. Não existe o objeto de política único sugerido |
| ARQ-04 | Baixa | **Mitigado** | `SituacaoSla` avaliada uma vez por chamado; o mapper copia prazo/fora do SLA, mas ainda depende do `SlaCalculator` para avaliar na listagem e no detalhe |
| DOM-01 | Alta | **Mitigado** | `Chamado` e `Comentario` sem setters, com métodos de intenção; 9 → 0 `set*` de estado fora do domínio. **`Usuario` e `Categoria` continuam com `@Setter` e `@Builder`/`@AllArgsConstructor` públicos**, e os services os alteram por setter (7 chamadas) |
| DOM-02 | Média | **Mitigado** | No backend, tabela e efeitos ficaram no domínio (`TransicaoStatus` + `Chamado.aplicarEfeitosDaTransicao`), mas o efeito ainda é um `switch` separado da aresta. No frontend, `ChamadoAcoes` mantém uma cópia declarada da tabela (FE-01) |
| DOM-03 | Média | **Mitigado** | Capacidades no `Perfil`; comparações de identidade 11 → 3 no backend e 10 → 4 no frontend. As 3 do backend são novas (N-03) |
| DOM-04 | Baixa | **Mitigado** | `verificarAssuncao(usuario, chamado)` perdeu o parâmetro morto. `verificarAbertura` continua com corpo vazio, por decisão registrada na Fase 5 |
| DOM-05 | Baixa | **Corrigido** | 8 de 9 símbolos removidos (Fases 5 e 9); `permitida` mantido como oráculo dos testes |
| DOM-06 | Baixa | **Dívida técnica** | 9 dependências injetadas, como antes (245 → 199 linhas). Extração não feita, com justificativa (Fase 5, §2.8) |

### 3.2 Regras de negócio

| ID | Sev. | Destino | Evidência / o que resta |
|---|---|---|---|
| REG-01 | Alta | **Corrigido** | Sequencial: 422 [confirmado]. Concorrente: violável nesta auditoria (N-01); **corrigido** pela trava dos admins ativos ([correcao-n01-concorrencia-usuarios.md](execucao/correcao-n01-concorrencia-usuarios.md)), com `ConcorrenciaUsuarioIT` e 20 tentativas pela API sem nenhum caso de zero admins [confirmado] |
| REG-02 | Alta | **Corrigido** | Sequencial: 422 [confirmado]. A corrida "rebaixar/desativar × assumir", apontada por inspeção nesta auditoria, foi **comprovada** por testes que falharam no código anterior e **corrigida** pela trava da linha do atendente ([correcao-n01-concorrencia-usuarios.md](execucao/correcao-n01-concorrencia-usuarios.md)). A reatribuição continua fora do escopo (§14 do plano) |
| REG-03 | Média | **Fora do escopo** | O comentário enganoso foi corrigido e o contrato "`PUT` não reativa" é testado [confirmado]. A reativação é funcionalidade nova |
| REG-04 | Média | **Corrigido** | Login e filter: 401 com a mesma mensagem [confirmado] |
| REG-05 | Baixa | **Corrigido** | `DELETE /api/categorias/{id}` sem a consulta só para log (Fase 4) |
| REG-06 | Baixa | **Corrigido** | `nomeDisponivel`/`emailDisponivel`; os pares `existsBy...`/`...AndIdNot` continuam, agora usados por um método só |

### 3.3 Exceções, segurança e concorrência

| ID | Sev. | Destino | Evidência / o que resta |
|---|---|---|---|
| EXC-01 | Alta | **Corrigido** | 4 × 500 → 404/405/415/400, 0 linhas ERROR [confirmado] |
| EXC-02 | Média | **Dívida técnica** | 5 `@ExceptionHandler` de domínio quase idênticos; nenhuma hierarquia comum |
| EXC-03 | Média | **Mitigado** | 401 encerra a sessão e os *unhandled rejections* foram tratados (testes `api.spec`, `sessaoBoot.spec`). O tratamento continua por `.catch(() => {})` em cada página (12), sem o `useAcao`/`silencioso` sugerido |
| EXC-04 | Baixa | **Dívida técnica** | `ErroResponse` ainda usa `Instant.now()` (2 ocorrências) |
| SEG-01 | Alta | **Mitigado** | `prod` não sobe com o login simulado [confirmado]; sem perfil, o login simulado fica desligado [confirmado]. A personificação em `dev`/`test` é o mecanismo aceito do MVP (ADR-003); autenticação real está fora do escopo |
| SEG-02 | Alta | **Corrigido** | `env`/`loggers` 404 sem perfil e em `dev`; console e Swagger 404 sem perfil [confirmado] |
| SEG-03 | Alta | **Corrigido** | Rota pública sem e-mail [confirmado] |
| SEG-04 | Média | **Corrigido** | 0 e-mails em log; mensagem de e-mail duplicado genérica [confirmado] |
| SEG-05 | Baixa | **Dívida técnica** | `X-Request-Id: <script>...` volta igual no header [confirmado] |
| SEG-06 | Baixa | **Corrigido** | Mensagem única no 401 [confirmado] |
| SEG-07 | Baixa | **Dívida técnica** | `sort=solicitante.email` → 200 [confirmado]; campo inexistente já dá 400 |
| SEG-08 | Baixa | **Corrigido** | `boot/sessao.js` revalida a sessão (`sessaoBoot.spec`) |
| CON-01 | Alta | **Corrigido** | Corrida: uma assunção por chamado [confirmado] |
| CON-02 | Média | **Corrigido** | `DataIntegrityViolationException` → 409 sem a mensagem do banco; a verificação prévia continua como caminho feliz, como sugerido |
| CON-03 | Alta | **Corrigido** | Listagem: 9 → 2 consultas [confirmado] |
| CON-04 | Média | **Dívida técnica** | O dashboard continua carregando todos os chamados em memória (2 consultas, sem `GROUP BY`) |
| CON-05 | Baixa | **Corrigido** | `RESTART WITH (SELECT MAX(id)+1 ...)` no seed |

### 3.4 Observabilidade, frontend e testes

| ID | Sev. | Destino | Evidência / o que resta |
|---|---|---|---|
| OBS-01 | Média | **Reclassificado** | ADR-018 revisada na Fase 8: gauges recalculados na leitura do dashboard, comportamento mantido e testado como contrato |
| OBS-02 | Baixa | **Mitigado** | Métricas e auditoria depois do commit (`AposCommit`, `ObservabilidadeIT`). Continuam: nova medição a cada resolução de chamado reaberto e a tag `categoria` pelo nome (decisões da Fase 8, §4) |
| OBS-03 | Baixa | **Dívida técnica** | Chaves de auditoria por tipo de alvo (`chamadoId`, `categoriaId`, `usuarioAlvoId`) |
| FE-01 | Média | **Mitigado** | CCN 12 → 3 [medido]; a tabela do frontend continua espelhando `TransicaoStatus` e `PermissaoService`. `acoesPermitidas` na API está fora do escopo (§14) |
| FE-02 | Média | **Corrigido** | Prop `processando`; clique duplo → 1 PATCH (`ChamadoDetalhePage.spec`) |
| FE-03 | Baixa | **Corrigido** | `constants/dominio.js`, `utils/formatacao.js`; `formatDate` 4 → 1; rótulo divergente 2 → 0 |
| FE-04 | Baixa | **Corrigido** | `categorias` × `todasCategorias`, carregamento por recurso (`catalogoStore.spec`) |
| FE-05 | Baixa | **Corrigido** | *Fallback* 2 → 1; `boot/axios.js` removido |
| FE-06 | Baixa | **Dívida técnica** | `aplicarAtualizacao` ainda mescla o objeto de detalhe inteiro na lista |
| FE-07 | Baixa | **Dívida técnica** | Toggle "Somente os meus" continua visível para o solicitante |
| TST-01 | Média | **Corrigido** | `web.controller` 50% → 100%; testes de erros do framework, concorrência, REG-01/02 e contagem de consultas |
| TST-02 | Baixa | **Corrigido** | `@WebMvcTest` com `@ActiveProfiles("test")`; o default `dev` não existe mais |
| TST-03 | Baixa | **Mitigado** | Testes de `api.js`, `catalogoStore`, boot e uma página (4 → 9 arquivos); continua sem métrica de cobertura no frontend |

### 3.5 Divergências de documentação

| ID | Destino |
|---|---|
| DOC-01 … DOC-10 | **Corrigido** (Fase 9, §5). Conferência de hoje: a documentação descreve as camadas, os pacotes, os perfis, o CORS, o `@EntityGraph`, as métricas, os códigos de erro e o diagrama de componentes como o código está |

Uma divergência nova foi encontrada nesta auditoria (N-07).

### 3.6 Resumo por destino

| Destino | Qtde | IDs |
|---|:---:|---|
| Corrigido | 24 | ARQ-01, ARQ-02, DOM-05, REG-01, REG-02, REG-04, REG-05, REG-06, EXC-01, SEG-02, SEG-03, SEG-04, SEG-06, SEG-08, CON-01, CON-02, CON-03, CON-05, FE-02, FE-03, FE-04, FE-05, TST-01, TST-02 |
| Mitigado | 11 | ARQ-03, ARQ-04, DOM-01, DOM-02, DOM-03, DOM-04, EXC-03, SEG-01, OBS-02, FE-01, TST-03 |
| Reclassificado por decisão arquitetural | 1 | OBS-01 |
| Dívida técnica | 9 | DOM-06, EXC-02, EXC-04, SEG-05, SEG-07, CON-04, OBS-03, FE-06, FE-07 |
| Fora do escopo | 1 | REG-03 |
| **Total** | **46** | |

As 10 divergências DOC-01…10, contadas à parte como na auditoria inicial, estão corrigidas.

**Achados de severidade Alta (9):**
- **Corrigidos (7):** EXC-01, SEG-02, SEG-03, CON-01, CON-03, REG-01 e REG-02 (os dois últimos depois da correção do N-01).
- **Mitigados (2), com resíduo identificado:**
  - DOM-01: `Usuario` e `Categoria` continuam anêmicos;
  - SEG-01: no limite do escopo (ADR-003).

---

## 4. Problemas introduzidos ou revelados pela modernização

| ID | Sev. | Achado | Evidência |
|---|---|---|---|
| **N-01** | **Média — corrigido** | **A guarda do REG-01 é *check-then-act* sem lock.** Dois admins que rebaixam um ao outro ao mesmo tempo passam na contagem (`countByPerfilAndAtivoTrue == 2`) e o sistema fica sem administrador, o mesmo estado irrecuperável do REG-01 original. A guarda do REG-02 tem a mesma forma (rebaixar × assumir em paralelo) | [confirmado] para REG-01: 1ª tentativa, `PUT` 200/200, depois 403 para ambos. [por inspeção] para REG-02. `Usuario` não tem `@Version`. **Corrigido** depois desta auditoria: trava pessimista, em ordem fixa, das linhas de `usuario` envolvidas; o REG-02 foi comprovado e tratado junto ([correcao-n01-concorrencia-usuarios.md](execucao/correcao-n01-concorrencia-usuarios.md)) |
| **N-02** | Baixa | **Ciclo `web ↔ config`.** `AuthController` (`@ConditionalOnProperty(name = AutenticacaoSimuladaConfig.PROPRIEDADE)`) e `UsuarioAtualFilter` (`AutenticacaoSimuladaConfig.habilitada`) importam a raiz de composição, que importa `web`. O `DependenciaEntreCamadasTest` não tem regra para `web → config` | `web → config`: 0 → 2 imports |
| **N-03** | Baixa | **Comparação de identidade nova contra o DOM-03.** `UsuarioService.atualizar` e `garantirOutroAdminAtivo` usam `== Perfil.ADMIN` (3 ocorrências), enquanto o resto do código pergunta pela capacidade (`gerenciaCadastros()`, `atendeChamados()`) | `grep` (§2.5) |
| **N-04** | Baixa | **`@Builder` mantido só para fixtures.** `Chamado` e `Comentario` deixaram de ter setters, mas o `@Builder` público continua e nenhum código de `src/main` o usa. Ele permite montar, fora dos testes, um chamado `FECHADO` sem `fechadoEm`, o estado que o DOM-01 queria impedir | `grep "Chamado.builder()"`: 0 usos em `src/main` [por inspeção] |
| N-05 | Observação | Classes maiores: `UsuarioService` 119 → 205, `Chamado` 114 → 226 e `GlobalExceptionHandler` 109 → 215 linhas (com Javadoc). O crescimento acompanha as responsabilidades novas (guardas, agregado, erros do framework); nenhum método passou de CCN 7 no backend | §2.2, §2.5 |
| N-06 | Baixa (pré-existente) | **Aviso do Spring Data a cada listagem paginada:** "Serializing PageImpl instances as-is is not supported... no guarantee about the stability of the resulting JSON structure". O contrato da listagem depende de um formato que o framework não garante. Presente na `main` e no estado atual; não constava da auditoria inicial | Log dos dois jars [confirmado] |
| N-07 | Baixa | **Divergência nova em `ARQUITETURA.md` §4.12:** "Testes de integração usam perfil `test` com H2 limpo (`@Transactional` faz rollback por teste)". Hoje: `ConcorrenciaChamadoIT` e `ObservabilidadeIT` gravam de verdade (precisam do commit) e limpam à mão; 6 ITs de configuração rodam nos perfis `dev`, `prod` ou sem perfil; `SeedDemonstracaoIT` testa o `data.sql` | Leitura dos 15 ITs |
| N-08 | Processo | O relatório da Fase 9 registrou "lizard: nenhuma função acima de 10" sem a ferramenta ter rodado (o lizard não estava no `PATH` e o erro foi suprimido). A medição desta auditoria confirma o resultado, e o relatório da Fase 9 foi corrigido para citá-la | §2.2 |

**Regressões de comportamento:** nenhuma encontrada. As 377 verificações automatizadas passam
e as sondas da §2.6 só mostram as mudanças planejadas. Os pontos fortes da §4 da auditoria
inicial continuam:
- ordem 403 → 422 (`deveChamarPermissaoEDepoisTransicaoNaOrdem` e o IT pela API);
- visibilidade no `WHERE` (a listagem do solicitante tem 2 consultas, sem vazamento no `count`, `ChamadoSpecificationTest`);
- `Clock` injetado;
- `TransicaoStatus` como tabela;
- formato único de erro com `X-Request-Id`;
- *builders* de teste;
- `open-in-view: false`.

**Abstrações avaliadas e consideradas proporcionais:**

| Abstração | Avaliação |
|---|---|
| `Dados*` (3 records) | Repetem os campos dos `*Request`. É a duplicação estrutural que separa `web` de `service` (ARQ-01), sem lógica |
| `AposCommit` | 1 método estático, usado por 3 services e pelas métricas |
| `SituacaoSla` | Elimina 3 avaliações por chamado |
| `UsuarioAtual` | Reduzido a um record que embrulha `Usuario`. Continua servindo de tipo para o *argument resolver* e para o springdoc ignorar o parâmetro |
| `DependenciaEntreCamadasTest` | Lê o código-fonte em vez de usar ArchUnit (dependência nova). Cobre 7 regras, mas não a do N-02 |

Nenhuma abstração sem uso foi encontrada (§2.5, código morto).

---

## 5. Dívida técnica residual

Ordenada por severidade. "Origem" indica se o item vem da auditoria inicial ou desta.

| # | Item | Sev. | Origem | Direção já registrada |
|---|---|---|---|---|
| ~~1~~ | ~~Invariantes I11 (último admin) e I3 violáveis sob concorrência~~ | — | N-01 | **Corrigido** ([correcao-n01-concorrencia-usuarios.md](execucao/correcao-n01-concorrencia-usuarios.md)). Mantido na lista só para preservar a numeração |
| 2 | `Usuario` e `Categoria` anêmicos (setters e construtores públicos) | Média | DOM-01 (resíduo) | Métodos de intenção (`atualizarDados`, `rebaixarPara`, `desativar`), como no `Chamado` |
| 3 | Exceções de domínio sem hierarquia; 5 handlers quase iguais | Média | EXC-02 | `DominioException(codigo)` e mapeamento código → status num método |
| 4 | Dashboard carrega todos os chamados em memória | Média | CON-04 | `GROUP BY` para as contagens quando houver volume |
| 5 | Regras de transição e de relação espelhadas no frontend | Média | FE-01, DOM-02 (resíduo) | `acoesPermitidas` no `ChamadoResponse` (decisão do grupo, plano §14) |
| 6 | Tratamento de erro do frontend por `.catch(() => {})` em cada página (12) | Média | EXC-03 (resíduo) | Composable `useAcao` ou opção `silencioso` no `api.js` |
| 7 | Regra de visibilidade em duas implementações (SQL e memória) | Média | ARQ-03 (resíduo) | Objeto de política único, ou um teste que garanta que as duas concordam |
| 8 | `ChamadoService` com 9 dependências | Baixa | DOM-06 | Reavaliar se surgirem novos casos de uso de comentário |
| 9 | `ErroResponse` com `Instant.now()` | Baixa | EXC-04 | Passar o `Clock` ao handler e ao filter |
| 10 | `X-Request-Id` do cliente sem validação | Baixa | SEG-05 | Aceitar só `^[A-Za-z0-9-]{1,64}$` |
| 11 | `sort` aceita campos de associação (`solicitante.email`) | Baixa | SEG-07 | Lista branca de campos |
| 12 | Formato de auditoria por tipo de alvo | Baixa | OBS-03 | Componente `Auditoria` com formato único |
| 13 | Timer de resolução conta de novo na reabertura; tag `categoria` pelo nome | Baixa | OBS-02 (resíduo) | Decidir "por resolução" × "por chamado"; tag por id |
| 14 | Ciclo `web ↔ config` | Baixa | N-02 | Mover `PROPRIEDADE`/`habilitada` para fora de `config` (por exemplo, `web.filter`) e acrescentar a regra ao teste de arquitetura |
| 15 | `== Perfil.ADMIN` na guarda do REG-01 | Baixa | N-03 | Perguntar pela capacidade (`gerenciaCadastros()`) |
| 16 | `@Builder` público em `Chamado`/`Comentario` só para testes | Baixa | N-04 | *Builder* de teste em `support/` (já existe `ChamadoBuilder`), ou restringir o acesso |
| 17 | `PageImpl` serializado diretamente | Baixa | N-06 | `PagedModel`/`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`, preservando os campos do contrato |
| 18 | `aplicarAtualizacao` mistura formatos na lista | Baixa | FE-06 | Copiar só os campos do resumo |
| 19 | Toggle "Somente os meus" inútil para o solicitante | Baixa | FE-07 | Esconder para o solicitante (muda a tela) |
| 20 | Frontend sem métrica de cobertura | Baixa | TST-03 (resíduo) | `@vitest/coverage-v8` (dependência nova) |
| 21 | `verificarAbertura` com corpo vazio | Baixa | DOM-04 (resíduo) | Decisão registrada; remover se nunca houver regra |
| 22 | `ARQUITETURA.md` §4.12 desatualizado sobre os ITs | Baixa | N-07 | Descrever os três tipos de IT (transacional, com commit e de configuração) |

**Fora do escopo (decisão do grupo, plano §14):**
- autenticação real (SEG-01);
- reativação de cadastros (REG-03);
- reatribuição de chamado (REG-02);
- `acoesPermitidas` na API (FE-01).

**Mantido por decisão arquitetural:** gauges na leitura do dashboard (OBS-01, ADR-018).
