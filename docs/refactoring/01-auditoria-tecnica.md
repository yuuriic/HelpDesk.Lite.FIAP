# HelpDesk Lite — Auditoria Técnica (pré-refatoração)

| Item | Valor |
|---|---|
| Branch analisada | `refactoring` (idêntica à `main`) |
| Commit baseline | `c5f4335d472d3ebf4b4e812ed5c68bdeebc02da4` |
| Data | 30/09/2026 |
| Escopo | `backend/` (Spring Boot 3.3.5 / Java 21), `frontend/` (Vue 3 + Quasar), `docs/`, `.ai/` |
| Natureza | Somente diagnóstico. **Nenhum arquivo de código foi alterado.** |

---

## 1. Sumário executivo

O HelpDesk Lite é um MVP **bem estruturado para o seu tamanho**. Ele tem:

- camadas nítidas;
- a máquina de estados isolada e testável;
- o `Clock` injetado;
- a visibilidade do solicitante aplicada no `WHERE`;
- um handler de erros central;
- boa cobertura de testes (151 testes no backend e 49 no frontend, todos passando; 95% de linhas em `domain` + `service`).

Nenhum método do backend passa de complexidade ciclomática 10.

Os problemas mais relevantes não são de "código feio". Eles aparecem em **quatro frentes**:

1. **Tratamento de erros HTTP incorreto.** Rota inexistente, método não suportado, `Content-Type` errado e `sort` inválido devolvem **500** em vez de 404/405/415/400 (confirmado em execução).
2. **Regras de negócio com lacunas que corrompem o estado.** O único admin consegue rebaixar o próprio perfil e deixar o sistema **sem nenhum administrador, de forma irrecuperável**. Um atendente com chamados em andamento pode virar solicitante e deixar esses chamados órfãos (confirmado em execução).
3. **Modelo de domínio anêmico.** `Chamado` expõe setters públicos para `status` e carimbos de tempo. As regras de transição vivem no service e estão espalhadas por várias classes, e o frontend as reimplementa (*shotgun surgery*).
4. **Segurança e configuração.** O perfil padrão é `dev`. Um `java -jar` sem perfil expõe `/actuator/env` e `/actuator/loggers` sem autenticação. Um endpoint público lista todos os usuários com e-mail. A autenticação simulada (`X-User-Id`) segue ativa também no perfil `prod`.

Há também uma **violação da regra de dependência documentada**: 17 imports de `web.dto` dentro de `service`. As ADRs estão divididas entre dois documentos, e há divergências pontuais entre documentação e código (seção 13).

### Contagem por severidade

| Severidade | Qtde | Critério |
|---|:---:|---|
| **Alta** | 9 | Corrompe estado, expõe dados/controle, ou devolve resposta incorreta em fluxo comum |
| **Média** | 16 | Dívida estrutural que encarece mudanças ou tende a gerar defeito |
| **Baixa** | 21 | Higiene, duplicação local, inconsistência menor |

Total: 46 achados, mais 10 divergências de documentação (seção 13). Os 9 achados de severidade Alta, em ordem sugerida de ataque:

1. [EXC-01](#exc-01) — Erros de framework viram 500
2. [REG-01](#reg-01) — Sistema pode ficar sem nenhum admin
3. [REG-02](#reg-02) — Invariante I3 violável, com chamados órfãos
4. [CON-01](#con-01) — Sem lock otimista no chamado
5. [SEG-02](#seg-02) — Perfil padrão `dev` expõe Actuator
6. [DOM-01](#dom-01) — Modelo anêmico com setters públicos
7. [SEG-01](#seg-01) — Personificação trivial, inclusive no perfil `prod`
8. [CON-03](#con-03) — N+1 na listagem paginada
9. [SEG-03](#seg-03) — Endpoint público lista usuários com e-mail

---

## 2. Metodologia

| Técnica | Ferramenta / forma |
|---|---|
| Leitura integral do código | Todo o `backend/src/main`, `frontend/src` e os testes de ambos |
| Leitura da documentação | `docs/ARQUITETURA.md`, `docs/MVP.md`, `.ai/architecture.md`, `.ai/business-rules.md`, `.ai/standards.md` |
| Complexidade ciclomática | [lizard](https://github.com/terryyin/lizard) 1.24.0 sobre Java, JS e Vue (testes excluídos). Instalado em diretório temporário, fora do repositório |
| Baseline de testes | `./mvnw verify` (JUnit + JaCoCo) e `npx vitest run` |
| Verificação empírica | O jar já empacotado foi executado localmente (H2 em memória, descartado ao final) e sondado com `curl`. Todo achado marcado com **[confirmado]** foi reproduzido dessa forma. Os marcados **[por inspeção]** vêm da leitura do código |
| Busca de código morto | `grep` de cada símbolo público em `src/main` e `src/test` |

---

## 3. Baseline medido

### Testes

| Suíte | Resultado |
|---|---|
| Backend (`mvnw verify`) | **151 testes, 0 falhas.** Gate JaCoCo (≥ 80% em domain+service) aprovado |
| Frontend (`vitest run`) | **49 testes em 4 arquivos, 0 falhas** |

### Cobertura de linhas no backend (JaCoCo)

| Pacote | Cobertura |
|---|:---:|
| **Total** | **92,1%** |
| domain + service (gate) | 95,0% |
| `service` | 100% |
| `domain.state`, `repository`, `config` | 100% |
| `web.filter` | 92,3% |
| `web.exception` | 80,6% |
| `web.controller` | **50,0%** |
| `domain.model` | 17,4% (o grosso é código gerado pelo Lombok e `equals`) |

### Complexidade ciclomática (CCN)

| Métrica | Valor |
|---|---|
| Funções analisadas | 247 |
| CCN médio | 1,7 |
| **Métodos com CCN > 10** | **1**: frontend, [`ChamadoAcoes.vue` — computed `acoes`](../../frontend/src/components/ChamadoAcoes.vue#L54-L87), **CCN 12** |
| No limite (CCN = 10) | [`services/chamados.js` — `listar`](../../frontend/src/services/chamados.js#L5-L17) (a complexidade vem só de `\|\|` e `??` de valores padrão; não preocupa) |
| **Backend** | **Nenhum método com CCN > 10.** O maior é 6 |

Os maiores valores do backend, todos aceitáveis:

| CCN | Método |
|:---:|---|
| 6 | `ChamadoSpecification.comFiltros` |
| 6 | `PermissaoService.verificarAlteracaoStatus` |
| 5 | `ChamadoService.aplicarEfeitosDaTransicao` |
| 5 | `UsuarioAtualFilter.doFilterInternal` |

---

## 4. Pontos fortes a preservar

A refatoração não deve regredir nestes pontos:

- **Ordem 403 → 422** (permissão antes da transição), documentada e testada (ADR-015).
- **Visibilidade do solicitante no `WHERE`** via `Specification`, sem vazamento no `count`.
- **`Clock` injetado** em service e SLA. Os testes são determinísticos.
- **`TransicaoStatus` como tabela** (`de → para → perfis`), sem Spring, instanciável com `new`.
- **Formato único de erro** e o `X-Request-Id` ponta a ponta (frontend → MDC → header).
- **Builders de teste** em vez de reaproveitar o `data.sql`.
- `open-in-view: false`, índices nas colunas de filtro, `equals`/`hashCode` por id nas entidades.

---

## 5. Arquitetura e camadas

### ARQ-01 — A camada de serviço depende da camada web · **Média**

- **Onde:** 8 classes em `service/` com 17 imports de `br.com.helpdesk.web.dto.*`. Exemplos:
  - [ChamadoService.java:18-22](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L18-L22)
  - os quatro mappers em [service/mapper/](../../backend/src/main/java/br/com/helpdesk/service/mapper/)
  - [CategoriaService.java:10-11](../../backend/src/main/java/br/com/helpdesk/service/CategoriaService.java#L10-L11)
  - [UsuarioService.java:8-9](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L8-L9)
- **Evidência:** os services recebem `NovoChamadoRequest`, `CategoriaRequest` e `UsuarioRequest`, e devolvem `*Response`. A documentação afirma `web → service → domain` com dependência unidirecional ([ARQUITETURA.md §4.1](../ARQUITETURA.md)). O Javadoc de [UsuarioAtual.java:13-15](../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtual.java#L13-L15) diz que a camada de serviço "não precisa conhecer nenhum tipo de `web`".
- **Impacto:** o ciclo `web ↔ service` impede reusar os casos de uso por outro adaptador (fila, CLI, job). Qualquer mudança no contrato HTTP (renomear campo, versionar a API) obriga a mexer no service e nos testes dele.
- **Direção:** criar comandos e resultados na camada de aplicação (`service.dto` ou records como `AbrirChamadoCommand`). Os controllers passam a fazer a tradução Request→Command e Resultado→Response, e os mappers para Response vão para `web`. Outra opção é manter os DTOs e movê-los para um pacote neutro, `api.dto`, que ambas as camadas conheçam.

### ARQ-02 — Política inconsistente para as regras "puras" de domínio · **Média**

- **Onde:**
  - [SlaCalculator.java:25-30](../../backend/src/main/java/br/com/helpdesk/service/SlaCalculator.java#L25-L30) e [PermissaoService.java:25-27](../../backend/src/main/java/br/com/helpdesk/service/PermissaoService.java#L25-L27): `@Service` no pacote `service`.
  - [TransicaoStatus.java](../../backend/src/main/java/br/com/helpdesk/domain/state/TransicaoStatus.java) com [DominioConfig.java](../../backend/src/main/java/br/com/helpdesk/config/DominioConfig.java): sem Spring, no pacote `domain`.
- **Evidência:** as três classes são descritas como "classe pura de domínio" (Javadoc e [ARQUITETURA.md §4.12](../ARQUITETURA.md)). Só uma delas segue a regra "o domínio não conhece Spring". Prazo de SLA e permissão são regras de negócio, mas moram na camada de aplicação.
- **Impacto:** a fronteira do domínio fica ambígua. Quem for adicionar uma regra nova não sabe onde colocá-la nem se pode anotá-la.
- **Direção:** mover `SlaCalculator` e `PermissaoService` para `domain` (por exemplo, `domain.sla` e `domain.permissao`) sem anotações Spring, expostas como beans pelo `DominioConfig`, como já se faz com `TransicaoStatus`.

### ARQ-03 — Regra de visibilidade implementada em três lugares · **Média**

- **Onde:**
  - [ChamadoSpecification.java:44-49](../../backend/src/main/java/br/com/helpdesk/repository/ChamadoSpecification.java#L44-L49) (predicado SQL);
  - [PermissaoService.java:41-45](../../backend/src/main/java/br/com/helpdesk/service/PermissaoService.java#L41-L45) (`verificarVisualizacao`);
  - [PermissaoService.java:83-87](../../backend/src/main/java/br/com/helpdesk/service/PermissaoService.java#L83-L87) (`verificarComentario`, que é a mesma regra duplicada).
- **Evidência:** a regra "o solicitante só vê os próprios chamados" existe em três implementações independentes, uma delas na camada de repositório.
- **Impacto:** se a regra mudar (por exemplo, "o solicitante também vê chamados do seu departamento"), é fácil atualizar só um dos lugares. O resultado seria a lista mostrando o chamado e o detalhe devolvendo 403, ou o contrário.
- **Direção:** criar um único objeto de política (`VisibilidadeChamado`) que forneça tanto o `Specification` quanto a checagem em memória. `verificarComentario` deve delegar a `verificarVisualizacao`. Um teste de contrato garante que as duas formas concordam.

### ARQ-04 — Cálculo de negócio dentro do mapper · **Baixa**

- **Onde:** [ChamadoMapper.java:26,38-39,56-57](../../backend/src/main/java/br/com/helpdesk/service/mapper/ChamadoMapper.java#L26) e [DashboardService.java:48-51](../../backend/src/main/java/br/com/helpdesk/service/DashboardService.java#L48-L51).
- **Evidência:** o mapper depende de `SlaCalculator` e calcula `prazo` e `foraDoSla`. No dashboard, `foraDoSla` é calculado uma vez no filtro, de novo no `atraso()` e de novo no mapper: três avaliações por chamado.
- **Impacto:** mistura conversão de dados com regra de negócio, e o mapper deixa de ser trivial de testar.
- **Direção:** criar um value object `SituacaoSla(prazo, foraDoSla, atraso)`, calculado uma vez no domínio (método em `Chamado` ou no calculador), que o mapper apenas copia.

---

## 6. Modelo de domínio, Orientação a Objetos e SOLID

### DOM-01 — Modelo anêmico: estado do chamado alterado por setters públicos · **Alta**

- **Onde:**
  - [Chamado.java:38-43](../../backend/src/main/java/br/com/helpdesk/domain/model/Chamado.java#L38-L43): `@Getter @Setter @Builder @AllArgsConstructor`.
  - Uso dos setters em [ChamadoService.java:140-142](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L140-L142) e [199-219](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L199-L219).
  - O mesmo padrão em `Usuario`, `Categoria` e `Comentario`. `Comentario` se declara imutável, mas tem `@Setter`.
- **Evidência:** `status`, `atendente`, `resolvidoEm` e `fechadoEm` são alterados de fora (`chamado.setStatus(...)`). Nada impede que qualquer classe chame `setStatus(FECHADO)` sem passar pela `TransicaoStatus`. O `@Builder` permite construir chamados em estados impossíveis, como `FECHADO` sem `fechadoEm`.
- **Impacto:** as invariantes do agregado dependem da disciplina de quem o chama, o que viola encapsulamento e *Tell, Don't Ask*. Cada novo caso de uso que mexe no chamado precisa redescobrir a sequência correta de setters.
- **Direção:** tornar `Chamado` um agregado rico:
  - factory `Chamado.abrir(titulo, descricao, prioridade, categoria, solicitante, agora)`;
  - métodos de intenção: `assumir(atendente, agora)`, `resolver(agora)`, `fechar(agora)`, `reabrir(agora)`, que consultam a `TransicaoStatus` e aplicam os carimbos;
  - remover `@Setter` e `@AllArgsConstructor` públicos; manter um construtor `protected` para o JPA;
  - em `Comentario`, remover os setters para cumprir a regra I4.

### DOM-02 — Violação de OCP: incluir uma transição exige *shotgun surgery* · **Média**

- **Onde:** para incluir um status ou transição, é preciso mexer em:
  - backend: [StatusChamado](../../backend/src/main/java/br/com/helpdesk/domain/enums/StatusChamado.java), a tabela em [TransicaoStatus:37-42](../../backend/src/main/java/br/com/helpdesk/domain/state/TransicaoStatus.java#L37-L42), o `switch` em [ChamadoService:202-215](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L202-L215) e a semântica de `perfisQueLevamA` em `PermissaoService`;
  - frontend: [ChamadoAcoes.vue:54-87](../../frontend/src/components/ChamadoAcoes.vue#L54-L87), [StatusBadge.vue:12](../../frontend/src/components/StatusBadge.vue#L12), [ChamadoFiltros.vue:72](../../frontend/src/components/ChamadoFiltros.vue#L72) e os cards de `DashboardPage.vue`.
- **Evidência:** os efeitos de cada transição (carimbos, métrica) ficam num `switch` separado da tabela que define a transição. O frontend replica a tabela inteira.
- **Impacto:** a ADR-005 diz que a máquina de estados fica "em um lugar só", mas na prática ela está em quatro. Uma mudança parcial gera botões que dão 422, ou transições sem carimbo.
- **Direção:**
  - associar o efeito à própria aresta: `Transicao(de, para, perfis, efeito)`, ou métodos no agregado (DOM-01);
  - expor no `ChamadoResponse` a lista `acoesPermitidas`, calculada por `TransicaoStatus.proximosStatus` + `PermissaoService`, para que o frontend deixe de replicar regras (ver FE-01).

### DOM-03 — *Type code* espalhado: `perfil == Perfil.X` · **Média**

- **Onde:**
  - [PermissaoService.java](../../backend/src/main/java/br/com/helpdesk/service/PermissaoService.java): 7 comparações diretas de perfil;
  - [ChamadoSpecification.java:44](../../backend/src/main/java/br/com/helpdesk/repository/ChamadoSpecification.java#L44);
  - no frontend, `perfil === 'ADMIN'` em [ChamadoAcoes.vue:42-44](../../frontend/src/components/ChamadoAcoes.vue#L42-L44), [auth.js:27-29](../../frontend/src/stores/auth.js#L27-L29) e [ComentarioTimeline.vue:36-43](../../frontend/src/components/ComentarioTimeline.vue#L36-L43).
- **Evidência:** existem `Usuario.isAdmin()`, `isAtendente()` e `isSolicitante()` ([Usuario.java:48-58](../../backend/src/main/java/br/com/helpdesk/domain/model/Usuario.java#L48-L58)), mas eles **não são usados** em nenhum lugar do código de produção. O código mistura dois idiomas para a mesma pergunta.
- **Impacto:** perguntas de capacidade ("vê a base toda?", "gerencia cadastros?") ficam codificadas como comparação de identidade. Um quarto perfil (por exemplo, `SUPERVISOR`) obriga a revisar cada `if`.
- **Direção:** colocar as capacidades no enum: `Perfil.veTodosOsChamados()`, `Perfil.gerenciaCadastros()`, `Perfil.atende()`. Os services e a `Specification` perguntam pela capacidade, não pela identidade. Remover os helpers não usados ou passar a usá-los, escolhendo um único idioma.

### DOM-04 — Generalidade especulativa e parâmetros mortos · **Baixa**

- **Onde:**
  - [PermissaoService.java:32-34](../../backend/src/main/java/br/com/helpdesk/service/PermissaoService.java#L32-L34): `verificarAbertura`, com corpo vazio;
  - [PermissaoService.java:48-52](../../backend/src/main/java/br/com/helpdesk/service/PermissaoService.java#L48-L52): `verificarAssuncao(usuario, chamado)` ignora o `chamado`.
- **Impacto:** a assinatura promete uma regra que não existe e induz o leitor a achar que há validação.
- **Direção:** remover `verificarAbertura`, ou mantê-lo com um teste que documente a decisão. Ajustar a assinatura de `verificarAssuncao`, ou usar o `chamado` se houver regra (por exemplo, impedir assumir o próprio chamado).

### DOM-05 — Código morto · **Baixa**

Símbolos sem nenhuma referência no código de produção:

| Símbolo | Onde | Observação |
|---|---|---|
| `Chamado.pertenceA(Usuario)` | [Chamado.java:89-92](../../backend/src/main/java/br/com/helpdesk/domain/model/Chamado.java#L89-L92) | Duplica `foiSolicitadoPor`. Sem uso nem em teste |
| `StatusChamado.concluido()` | [StatusChamado.java:27-29](../../backend/src/main/java/br/com/helpdesk/domain/enums/StatusChamado.java#L27-L29) | Sem uso nem em teste |
| `Usuario.isAdmin/isAtendente/isSolicitante` | [Usuario.java:48-58](../../backend/src/main/java/br/com/helpdesk/domain/model/Usuario.java#L48-L58) | Ver DOM-03 |
| `UsuarioRepository.findByEmailIgnoreCase` | [UsuarioRepository.java:15](../../backend/src/main/java/br/com/helpdesk/repository/UsuarioRepository.java#L15) | Sem uso |
| `TransicaoStatus.permitida`, `proximosStatus` | [TransicaoStatus.java:63-75](../../backend/src/main/java/br/com/helpdesk/domain/state/TransicaoStatus.java#L63-L75) | Só em testes. `proximosStatus` é justamente o que o frontend reimplementa (FE-01) |
| `TransicaoInvalidaException.getDe/getPara` | [TransicaoInvalidaException.java:20-26](../../backend/src/main/java/br/com/helpdesk/domain/exception/TransicaoInvalidaException.java#L20-L26) | Nunca lidos pelo handler |
| `UsuarioAtual.id()/perfil()/nome()` | [UsuarioAtual.java:20-30](../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtual.java#L20-L30) | Só em testes |
| `app.config.globalProperties.$api` | [boot/axios.js:10-14](../../frontend/src/boot/axios.js#L10-L14) | A ponte para "componentes legados" não tem nenhum consumidor |

- **Direção:** remover os símbolos, ou dar uso a eles quando fizerem parte da solução de outro achado (`proximosStatus` → FE-01; `getDe/getPara` → incluir no corpo do 422).

### DOM-06 — `ChamadoService` acumula responsabilidades (SRP) · **Baixa**

- **Onde:** [ChamadoService.java:53-61](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L53-L61), com 9 dependências injetadas.
- **Evidência:** a classe orquestra os casos de uso do chamado e dos comentários, aplica carimbos de tempo, emite métricas e formata as linhas de auditoria.
- **Impacto:** o número de mocks por teste cresce e qualquer mudança transversal (auditoria, métrica) toca na classe. Hoje ainda é administrável.
- **Direção:** extrair `ComentarioService`, mover os efeitos para o agregado (DOM-01) e criar um componente `Auditoria` compartilhado (OBS-03). Métricas e auditoria podem virar *listeners* de eventos de domínio (`ChamadoAssumido`, `ChamadoResolvido`).

---

## 7. Regras de negócio: lacunas e camada inadequada

### REG-01 — O único admin pode se rebaixar e deixar o sistema sem administrador · **Alta** · [confirmado]

- **Onde:** [UsuarioService.java:77-94](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L77-L94) (`atualizar`).
- **Evidência:** `PUT /api/usuarios/3` com `perfil=SOLICITANTE`, enviado pelo próprio admin 3, devolve **200**. Em seguida, `GET /api/usuarios` devolve **403**. Nenhum usuário consegue mais gerenciar usuários. A regra I10 só impede *desativar* o próprio usuário; *rebaixar* não é bloqueado.
- **Impacto:** perda permanente da administração. Não existe caminho de recuperação no produto.
- **Direção:** criar uma regra de domínio "sempre existe ao menos um ADMIN ativo", verificada no rebaixamento e na desativação. Bloquear também o auto-rebaixamento, por simetria com a I10. Adicionar os testes correspondentes.

### REG-02 — A invariante I3 pode ser violada, gerando chamados órfãos · **Alta** · [confirmado]

- **Onde:** [UsuarioService.java:77-94](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L77-L94) (`atualizar`) e [UsuarioService.java:101-113](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L101-L113) (`desativar`).
- **Evidência:** o atendente 2 é atendente do chamado 4 (`EM_ATENDIMENTO`). `PUT /api/usuarios/2` com `perfil=SOLICITANTE` devolve **200**, e o chamado 4 continua com `atendente` = um SOLICITANTE. A regra I3 ([business-rules.md](../../.ai/business-rules.md)) diz que só ATENDENTE ou ADMIN podem ser atendentes. O mesmo acontece ao desativar um atendente.
- **Impacto:**
  - o ex-atendente não consegue mais agir no chamado, porque `verificarAlteracaoStatus` exige o perfil ATENDENTE;
  - não existe caso de uso de reatribuição, porque `assumir` só parte de `ABERTO`;
  - o chamado fica preso até um admin intervir, e o SLA estoura sem que ninguém da fila consiga pegá-lo.
- **Direção:** bloquear a mudança de perfil e a desativação enquanto houver chamados `EM_ATENDIMENTO` do usuário, ou devolver esses chamados para `ABERTO` numa operação explícita. Criar o caso de uso `reatribuir(chamado, novoAtendente)` para o ADMIN.

### REG-03 — A reativação de categoria é prometida, mas não existe · **Média**

- **Onde:**
  - [CategoriaService.java:38](../../backend/src/main/java/br/com/helpdesk/service/CategoriaService.java#L38): "a tela de administração precisa vê-las e poder reativá-las";
  - [CategoriaRequest.java](../../backend/src/main/java/br/com/helpdesk/web/dto/request/CategoriaRequest.java) não tem o campo `ativa`, e `atualizar` não o altera;
  - [CategoriasPage.vue:39](../../frontend/src/pages/CategoriasPage.vue#L39) só oferece o botão de desativar.
- **Impacto:** uma desativação por engano é irreversível pela aplicação e só se corrige direto no banco. O mesmo vale para usuários.
- **Direção:** criar `PATCH /api/categorias/{id}/reativar` e `PATCH /api/usuarios/{id}/reativar` (Admin), com o botão correspondente na UI.

### REG-04 — A regra I9 é aplicada de forma inconsistente entre o login e o filter · **Média**

- **Onde:** [UsuarioService.java:38-45](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L38-L45) comparado com [UsuarioAtualFilter.java:82-92](../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtualFilter.java#L82-L92).
- **Evidência:** para a mesma condição, as respostas diferem:

  | Situação | `POST /auth/login` | Demais rotas (filter) |
  |---|---|---|
  | Usuário inativo | **422** `REGRA_NEGOCIO` | **401** `NAO_AUTENTICADO` |
  | Usuário inexistente | **404** [confirmado] | **401** |

  A regra I9 manda devolver 401.
- **Impacto:** o contrato fica incoerente, o frontend precisa tratar três códigos para "não pode entrar", e as respostas diferentes permitem descobrir quais ids existem.
- **Direção:** criar uma exceção `NaoAutenticadoException` → 401 com mensagem genérica ("credenciais inválidas"), usada pelos dois caminhos.

### REG-05 — Consulta executada só para alimentar o log · **Baixa**

- **Onde:** [CategoriaService.java:96-98](../../backend/src/main/java/br/com/helpdesk/service/CategoriaService.java#L96-L98).
- **Evidência:** `existsByCategoriaId` roda a cada desativação apenas para preencher `possuiChamados` no log.
- **Direção:** remover a consulta, ou usar o resultado para algo com valor de negócio (por exemplo, avisar na resposta).

### REG-06 — Normalização e unicidade duplicadas nos services · **Baixa**

- **Onde:** [CategoriaService.java:51-54 e 71-74](../../backend/src/main/java/br/com/helpdesk/service/CategoriaService.java#L51-L54); [UsuarioService.java:59-62 e 81-84](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L59-L62).
- **Evidência:** `trim()`/`toLowerCase()` e a sequência "existe? então lança" estão copiados entre `criar` e `atualizar`. Os repositórios têm pares quase iguais (`existsByNomeIgnoreCase` e `existsByNomeIgnoreCaseAndIdNot`).
- **Direção:** criar value objects `Email` e `NomeCategoria` com a normalização dentro, e um único `garantirNomeUnico(nome, idIgnorado)`. Ver também CON-02.

---

## 8. Tratamento de exceções

### EXC-01 — Erros de cliente do framework viram **500** · **Alta** · [confirmado]

- **Onde:** [GlobalExceptionHandler.java:96-102](../../backend/src/main/java/br/com/helpdesk/web/exception/GlobalExceptionHandler.java#L96-L102) (`@ExceptionHandler(Exception.class)`).
- **Evidência:** reproduzido contra o jar em execução:

  | Requisição | Esperado | Obtido |
  |---|:---:|:---:|
  | `GET /api/nao-existe` | 404 | **500** |
  | `DELETE /api/chamados/1` (método não mapeado) | 405 | **500** |
  | `POST /api/chamados` com `Content-Type: text/plain` | 415 | **500** |
  | `GET /api/chamados?sort=naoExiste,asc` | 400 | **500** |

  As quatro requisições geram linhas **ERROR com stack trace**. O handler para `NoHandlerFoundException` ([linha 71](../../backend/src/main/java/br/com/helpdesk/web/exception/GlobalExceptionHandler.java#L71)) nunca é acionado: no Spring Boot 3.2+, rota inexistente lança `NoResourceFoundException`.
- **Impacto:**
  - o cliente recebe "erro interno" por um erro dele;
  - os alertas de 5xx disparam sem defeito real;
  - viola a regra documentada "4xx em WARN sem stack trace" (§4.11);
  - qualquer varredura de URLs inunda o log de ERROR.
- **Direção:** fazer o handler estender `ResponseEntityExceptionHandler`, ou tratar explicitamente:
  - `NoResourceFoundException` → 404;
  - `HttpRequestMethodNotSupportedException` → 405;
  - `HttpMediaTypeNotSupportedException` → 415;
  - `PropertyReferenceException` → 400;
  - `DataIntegrityViolationException` e `ObjectOptimisticLockingFailureException` → 409.

  Criar testes `@WebMvcTest` para cada um desses casos.

### EXC-02 — Exceções de domínio sem hierarquia comum · **Média**

- **Onde:** [domain/exception/](../../backend/src/main/java/br/com/helpdesk/domain/exception/) (4 classes independentes) e [GlobalExceptionHandler.java:63-93](../../backend/src/main/java/br/com/helpdesk/web/exception/GlobalExceptionHandler.java#L63-L93) (um método quase idêntico por exceção).
- **Evidência:** o par código/status (`ACESSO_NEGADO`/403 e assim por diante) aparece duplicado no Javadoc de cada exceção e no handler.
- **Impacto:** cada nova exceção exige um novo método no handler. Esquecer esse método cai no EXC-01 e devolve 500.
- **Direção:** criar a classe abstrata `DominioException` com `codigo()`. O mapeamento código→status fica num único método do handler (ou num `Map`), sem que o domínio conheça HTTP.

### EXC-03 — Frontend: política de erro inconsistente · **Média**

- **Onde:**
  - 10 ocorrências de `.catch(() => {})`: [ChamadosPage.vue:144-153](../../frontend/src/pages/ChamadosPage.vue#L144-L153), [LoginPage.vue:95](../../frontend/src/pages/LoginPage.vue#L95), [NovoChamadoPage.vue:141](../../frontend/src/pages/NovoChamadoPage.vue#L141) e outras;
  - chamadas sem nenhum `catch`: [ChamadoDetalhePage.vue:157,162](../../frontend/src/pages/ChamadoDetalhePage.vue#L157), [LoginPage.vue:99](../../frontend/src/pages/LoginPage.vue#L99), [CategoriasPage.vue:145](../../frontend/src/pages/CategoriasPage.vue#L145), [UsuariosPage.vue:183](../../frontend/src/pages/UsuariosPage.vue#L183);
  - o interceptor em [api.js:73-93](../../frontend/src/services/api.js#L73-L93).
- **Evidência:**
  - parte dos erros é engolida em silêncio e parte vira *unhandled promise rejection* no console;
  - o interceptor sempre exibe o notify, sem que o chamador possa tratar o erro localmente;
  - um **401 não encerra a sessão**: um usuário desativado continua "logado", recebendo notificações de erro até clicar em sair.
- **Impacto:** o comportamento de erro depende de cada página, a experiência degrada e os logs do navegador ficam poluídos.
- **Direção:**
  - no interceptor, tratar 401 com `auth.sair()` e redirecionar para `/login`;
  - criar um composable `useAcao()` que concentre `loading`, `try/catch` e o notify de sucesso;
  - permitir `config.silencioso` para quem quiser tratar o erro localmente;
  - eliminar os `catch(() => {})`.

### EXC-04 — `ErroResponse` ignora o `Clock` injetado · **Baixa**

- **Onde:** [ErroResponse.java:27,31](../../backend/src/main/java/br/com/helpdesk/web/exception/ErroResponse.java#L27).
- **Evidência:** usa `Instant.now()`, contrariando a ADR-013 ("nenhuma classe chama `Instant.now()`").
- **Direção:** fazer o handler e o filter receberem o `Clock` e passá-lo à factory.

---

## 9. Segurança

> O MVP assume conscientemente a autenticação simulada (ADR-003). Os itens abaixo separam o que é **escopo aceito** do que é **risco independente dessa decisão**.

### SEG-01 — Personificação trivial via `X-User-Id`, inclusive no perfil `prod` · **Alta** (aceito no MVP) · [confirmado]

- **Onde:** [UsuarioAtualFilter.java](../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtualFilter.java) e [application.yml:77-114](../../backend/src/main/resources/application.yml#L77-L114).
- **Evidência:** `curl -H "X-User-Id: 3" /api/usuarios` devolve 200 com a lista completa: qualquer pessoa vira admin. O perfil `prod` desliga H2 e Swagger, mas **mantém o mesmo mecanismo**, e nada impede o deploy nesse estado.
- **Impacto:** controle total do sistema por qualquer cliente de rede.
- **Direção:**
  - curto prazo: fazer o perfil `prod` falhar na inicialização se a autenticação simulada estiver ativa (por exemplo, `helpdesk.auth.simulada=true` proibido em `prod`);
  - médio prazo: Spring Security com JWT/OIDC. A ADR-003 já prevê que só o filter mude.

### SEG-02 — O perfil padrão é `dev`: Actuator sensível aberto sem autenticação · **Alta** · [confirmado]

- **Onde:** [application.yml:4-5](../../backend/src/main/resources/application.yml#L4-L5) (`profiles.default: dev`) e [application.yml:56-63](../../backend/src/main/resources/application.yml#L56-L63).
- **Evidência:** `java -jar helpdesk-lite.jar` sem `--spring.profiles.active` expõe:
  - `GET /actuator/env` → 200;
  - `GET /actuator/loggers/...` → 200; o endpoint também aceita **POST para alterar o nível de log em tempo de execução**;
  - health com detalhes, console H2 (usuário `sa` sem senha) e Swagger.
- **Impacto:** um deploy que "esqueça" o perfil sobe com a configuração mais aberta. Alterar o nível de log por POST permite ligar o DEBUG de SQL e inflar os logs.
- **Direção:** tornar o default seguro (sem `profiles.default` ou com default = `prod`) e exigir `dev` explícito no `spring-boot:run`. Tirar `env` e `loggers` da exposição, mesmo em `dev`, ou protegê-los.

### SEG-03 — Endpoint público lista todos os usuários, com e-mail · **Alta** · [confirmado]

- **Onde:** [AuthController.java:31-36](../../backend/src/main/java/br/com/helpdesk/web/controller/AuthController.java#L31-L36), que usa [UsuarioMapper.paraResponse](../../backend/src/main/java/br/com/helpdesk/service/mapper/UsuarioMapper.java#L12-L19).
- **Evidência:** `GET /api/auth/usuarios-disponiveis`, sem nenhum header, devolve id, nome, **e-mail** e perfil de todos os usuários ativos. Existe um `paraResumo` que omite o e-mail ("Não expõe e-mail"), mas ele não é usado aqui.
- **Impacto:** vazamento de dado pessoal e enumeração. Junto com SEG-01, entrega a qualquer um o id exato de cada admin.
- **Direção:** usar `UsuarioResumoResponse` (sem e-mail) nesse endpoint. Com autenticação real, o endpoint deixa de existir.

### SEG-04 — Dado pessoal em log WARN · **Média** · [confirmado]

- **Onde:** [UsuarioService.java:61,83](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L61) (mensagem com o e-mail) e [GlobalExceptionHandler.java:105-108](../../backend/src/main/java/br/com/helpdesk/web/exception/GlobalExceptionHandler.java#L105-L108) (loga `e.getMessage()`).
- **Evidência:** o log registrou `WARN ... Requisicao rejeitada com 422 em POST /api/usuarios: Ja existe um usuario com o e-mail ana@empresa.com`. A regra de §4.11 diz: "Nenhum log em nível INFO ou acima contém dados pessoais além do id do usuário".
- **Direção:** separar a mensagem para o usuário do detalhe para o log (por exemplo, `RegraNegocioException(codigo, mensagemUsuario)`, com o handler logando só o código), ou mascarar e-mails na mensagem.

### SEG-05 — `X-Request-Id` do cliente aceito sem validação · **Baixa** · [confirmado]

- **Onde:** [RequestIdFilter.java:37-43](../../backend/src/main/java/br/com/helpdesk/web/filter/RequestIdFilter.java#L37-L43).
- **Evidência:** qualquer string é aceita e gravada no MDC e no header de resposta. O próprio log dos testes mostra `req=id-vindo-do-frontend`. Não há limite de tamanho nem de formato.
- **Impacto:** poluição e forja de log, e colisão proposital de ids para confundir a correlação.
- **Direção:** aceitar o valor apenas se casar com `^[A-Za-z0-9-]{1,64}$`; caso contrário, gerar um UUID.

### SEG-06 — Mensagens de 401 revelam existência e estado do usuário · **Baixa** · [confirmado]

- **Onde:** [UsuarioAtualFilter.java:84,90](../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtualFilter.java#L84).
- **Evidência:** "Usuário 7 está inativo" e "Usuário 404 não encontrado" voltam no corpo da resposta.
- **Direção:** devolver uma mensagem genérica no corpo e manter o detalhe só no log WARN. Combina com REG-04.

### SEG-07 — Parâmetro `sort` sem lista de campos permitidos · **Baixa** · [confirmado]

- **Onde:** [ChamadoController.java:60](../../backend/src/main/java/br/com/helpdesk/web/controller/ChamadoController.java#L60).
- **Evidência:** aceita qualquer caminho de propriedade, inclusive de associações (`solicitante.email`). Um campo inválido devolve 500 (EXC-01).
- **Direção:** validar o `sort` contra uma lista branca (`criadoEm`, `prioridade`, `status`, `titulo`) antes de chamar o repositório.

### SEG-08 — Perfil desatualizado no `localStorage` · **Baixa**

- **Onde:** [auth.js:15-42](../../frontend/src/stores/auth.js#L15-L42) e [router/index.js:24-37](../../frontend/src/router/index.js#L24-L37).
- **Evidência:** a sessão e o perfil ficam salvos no navegador. Se o admin mudar o perfil de alguém, o menu e o guard desse usuário continuam mostrando as telas antigas. O backend barra as ações, então é problema de UX e não de autorização.
- **Direção:** revalidar a sessão (`GET /auth/me`) ao iniciar a aplicação e tratar 401/403 no interceptor (EXC-03).

---

## 10. Concorrência e desempenho

### CON-01 — Sem lock otimista no `Chamado` · **Alta** · [por inspeção]

- **Onde:** [Chamado.java](../../backend/src/main/java/br/com/helpdesk/domain/model/Chamado.java): não há `@Version` em nenhuma entidade.
- **Evidência:** `assumir` lê o status (`ABERTO`), valida e depois grava. Se dois atendentes assumem ao mesmo tempo, as duas transações leem `ABERTO`, as duas passam na `TransicaoStatus` e as duas gravam. Vale a última escrita, **as duas recebem 200** e a métrica e a auditoria registram duas assunções. O mesmo vale para `alterarStatus` concorrente (por exemplo, fechar e reabrir ao mesmo tempo).
- **Impacto:** um atendente acredita que está com o chamado sem estar. O histórico de auditoria fica inconsistente. A regra "assumir um chamado já assumido dá 422" só vale para requisições sequenciais.
- **Direção:** adicionar `@Version Long versao` em `Chamado` e tratar `ObjectOptimisticLockingFailureException` como 409 `CONFLITO`. Para o `assumir`, uma alternativa é o update condicional `UPDATE ... WHERE id=? AND status='ABERTO'`. Criar um teste de concorrência com duas threads.

### CON-02 — Unicidade verificada antes de gravar (*check-then-act*) · **Média** · [por inspeção]

- **Onde:** [CategoriaService.java:52,72](../../backend/src/main/java/br/com/helpdesk/service/CategoriaService.java#L52) e [UsuarioService.java:60,82](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L60).
- **Evidência:** duas criações simultâneas com o mesmo e-mail passam no `existsBy...`. A constraint `unique` do banco barra a segunda, mas a `DataIntegrityViolationException` resultante cai no catch-all e vira **500** (EXC-01).
- **Direção:** manter a checagem como caminho feliz e mapear `DataIntegrityViolationException` para 422/409 no handler.

### CON-03 — N+1 na listagem paginada · **Alta** · [confirmado]

- **Onde:** [ChamadoService.java:76-79](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L76-L79). O `findAll(spec, pageable)` herdado não tem `@EntityGraph` ([ChamadoRepository.java](../../backend/src/main/java/br/com/helpdesk/repository/ChamadoRepository.java)).
- **Evidência:** `GET /api/chamados?size=20` sobre o seed (8 chamados) emitiu **9 SELECTs**: 1 da página e o restante em carregamentos lazy de categoria, solicitante e atendente feitos pelo mapper. O custo cresce com o número de entidades relacionadas distintas por página, chegando a **1 + 3×20 = 61 queries** por página no pior caso.
- **Impacto:** a latência da tela mais usada cresce linearmente com a base. A §4.10 afirma "`@EntityGraph` nas consultas de detalhe", mas a listagem ficou de fora.
- **Direção:** sobrescrever `findAll(Specification, Pageable)` no repositório com `@EntityGraph(attributePaths = {"categoria","solicitante","atendente"})`, ou usar uma projeção. Criar um teste que conte as queries (por exemplo, com `Statistics` do Hibernate).

### CON-04 — O dashboard carrega a base inteira em memória a cada leitura · **Média** · [por inspeção]

- **Onde:** [ChamadoRepository.java:22-24](../../backend/src/main/java/br/com/helpdesk/repository/ChamadoRepository.java#L22-L24) (`select c from Chamado c` com 3 joins) e [DashboardService.java:42-65](../../backend/src/main/java/br/com/helpdesk/service/DashboardService.java#L42-L65).
- **Evidência:** as contagens por status e prioridade são feitas em Java sobre todos os registros, sem limite, e a lista "fora do SLA" também não tem limite.
- **Impacto:** memória e tempo O(n) por requisição. Somado ao OBS-01, um dashboard aberto com atualização frequente vira carga contínua no banco.
- **Direção:**
  - fazer as contagens com `GROUP BY status` e `GROUP BY prioridade`;
  - filtrar "fora do SLA" por query (`criado_em + sla_horas < :agora` / `resolvido_em > prazo`);
  - aplicar `LIMIT` (top N) na lista;
  - calcular o tempo médio com `AVG` no banco.

### CON-05 — Seed com valores de `RESTART WITH` fixos · **Baixa**

- **Onde:** [data.sql:111-114](../../backend/src/main/resources/data.sql#L111-L114).
- **Evidência:** os ids iniciais (4, 5, 9, 7) precisam ser atualizados à mão a cada linha incluída no seed. Um esquecimento causa violação de PK no primeiro INSERT feito pela UI.
- **Direção:** não informar ids explícitos no seed (deixar o IDENTITY gerar e usar subselects por nome/e-mail nas FKs), ou calcular com `SELECT MAX(id)+1`.

---

## 11. Observabilidade

### OBS-01 — Os gauges de negócio só são atualizados quando alguém abre o dashboard · **Média**

- **Onde:** [MetricasNegocio.java:100-103](../../backend/src/main/java/br/com/helpdesk/service/MetricasNegocio.java#L100-L103), chamado por [DashboardService.java:53](../../backend/src/main/java/br/com/helpdesk/service/DashboardService.java#L53) (decisão registrada na ADR-018).
- **Evidência:** após um restart, `helpdesk_chamados_por_status` e `helpdesk_chamados_fora_sla` ficam em **0** até a primeira leitura do dashboard. Durante a noite, os valores congelam no último acesso.
- **Impacto:** um alerta do Prometheus baseado em `fora_sla` não é confiável: ele reflete o uso da tela, não o estado do sistema.
- **Direção:** atualizar os gauges por `@Scheduled` com um intervalo de 1 a 5 minutos, usando as queries agregadas do CON-04, ou registrar gauges com *supplier* que consulte uma contagem em cache.

### OBS-02 — O timer de resolução é registrado antes do commit e duplica em reaberturas · **Baixa**

- **Onde:** [ChamadoService.java:203-206](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L203-L206) e [MetricasNegocio.java:88-94](../../backend/src/main/java/br/com/helpdesk/service/MetricasNegocio.java#L88-L94).
- **Evidência:**
  - o timer é gravado antes do `save`: se a transação sofrer rollback, a métrica fica registrada mesmo assim;
  - um chamado reaberto e resolvido de novo registra duas durações, ambas contadas desde `criadoEm`;
  - a tag `categoria` usa o **nome**, então renomear uma categoria divide a série histórica em duas.
- **Direção:** emitir as métricas após o commit (`@TransactionalEventListener(AFTER_COMMIT)`) e usar um identificador estável na tag.

### OBS-03 — Formato de auditoria inconsistente · **Baixa**

- **Onde:** [ChamadoService.java:240-244](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L240-L244) (`auditar`) comparado com os `log.info` avulsos em [ChamadoService.java:184](../../backend/src/main/java/br/com/helpdesk/service/ChamadoService.java#L184), [CategoriaService.java:62,80,97](../../backend/src/main/java/br/com/helpdesk/service/CategoriaService.java#L62) e [UsuarioService.java:71,91,112](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L71).
- **Evidência:** cada lugar usa chaves diferentes (`chamadoId`, `categoriaId`, `usuarioAlvoId`, `perfilAlvo`), e o formato fica a cargo de cada autor.
- **Direção:** criar um componente `Auditoria` com a API `registrar(acao, alvoTipo, alvoId, detalhes)` e um formato único, preparando a evolução prevista para a tabela `chamado_evento`.

---

## 12. Frontend

### FE-01 — `ChamadoAcoes`: CCN 12 e máquina de estados duplicada · **Média**

- **Onde:** [ChamadoAcoes.vue:54-87](../../frontend/src/components/ChamadoAcoes.vue#L54-L87). **É o único método do projeto com CCN acima de 10.**
- **Evidência:** três `if` compostos com `&&` e `||` replicam, à mão, a tabela da `TransicaoStatus` e as regras de relação do `PermissaoService`. O backend já tem `proximosStatus(de, perfil)`, sem uso em produção.
- **Impacto:** duas fontes da mesma regra. Qualquer mudança no backend exige mudar este componente, e uma divergência produz botões que dão 403/422, ou ações que o usuário poderia executar mas não vê.
- **Direção:** a preferida é o backend incluir `acoesPermitidas: ["ASSUMIR","RESOLVER",...]` no `ChamadoResponse`, e o componente apenas mapear cada ação para rótulo, ícone e cor. A alternativa mínima é uma tabela declarativa no frontend (`[{de, para, perfis, exigeRelacao}]`) avaliada por um único `filter`, o que derruba o CCN para cerca de 3.

### FE-02 — A proteção contra clique duplo não funciona · **Média** · [por inspeção]

- **Onde:** [ChamadoAcoes.vue:89-100](../../frontend/src/components/ChamadoAcoes.vue#L89-L100).
- **Evidência:** `emit()` é síncrono e não aguarda o handler assíncrono do pai. O `finally` zera `processando` no mesmo *tick*, então o `:loading` e o `:disable` nunca ficam ativos durante a requisição.
- **Impacto:** um clique duplo em "Resolver" dispara duas requisições. A segunda volta 422 e mostra um notify de erro depois de uma ação bem-sucedida.
- **Direção:** o pai passa uma prop `processando`, ou o componente recebe as ações como props do tipo função (`onAssumir: () => Promise`) e dá `await` nelas. Criar um teste que simule o clique duplo.

### FE-03 — Constantes e formatação duplicadas · **Baixa**

| Duplicação | Ocorrências |
|---|---|
| `date.formatDate(new Date(x), 'DD/MM/YYYY HH:mm')` | 4: [ComentarioTimeline.vue:33](../../frontend/src/components/ComentarioTimeline.vue#L33), [ChamadoDetalhePage.vue:141](../../frontend/src/pages/ChamadoDetalhePage.vue#L141), [ChamadosPage.vue:136](../../frontend/src/pages/ChamadosPage.vue#L136), [DashboardPage.vue:145](../../frontend/src/pages/DashboardPage.vue#L145) |
| Rótulos e cores de perfil | 4, **com rótulos divergentes** ("Admin" × "Administrador"): [LoginPage.vue:77-87](../../frontend/src/pages/LoginPage.vue#L77), [UsuariosPage.vue:128-135](../../frontend/src/pages/UsuariosPage.vue#L128), [MainLayout.vue:60](../../frontend/src/layouts/MainLayout.vue#L60), [ComentarioTimeline.vue:40-43](../../frontend/src/components/ComentarioTimeline.vue#L40) |
| Opções e rótulos de prioridade | 3: [ChamadoFiltros.vue:79](../../frontend/src/components/ChamadoFiltros.vue#L79), [NovoChamadoPage.vue:114](../../frontend/src/pages/NovoChamadoPage.vue#L114), [PrioridadeBadge.vue:14](../../frontend/src/components/PrioridadeBadge.vue#L14) |
| Rótulos de status | 3: [ChamadoFiltros.vue:72](../../frontend/src/components/ChamadoFiltros.vue#L72), [StatusBadge.vue:12](../../frontend/src/components/StatusBadge.vue#L12), cards do [DashboardPage.vue](../../frontend/src/pages/DashboardPage.vue) |

- **Direção:** criar `src/constants/dominio.js` (perfis, status e prioridades com rótulo, cor e ícone) e `src/utils/formatacao.js` (`formatarDataHora`).

### FE-04 — `catalogoStore` com estado ambíguo · **Baixa**

- **Onde:** [catalogo.js:8-29](../../frontend/src/stores/catalogo.js#L8-L29).
- **Evidência:**
  - `categorias` contém "só ativas" ou "todas", dependendo de quem carregou por último (`NovoChamadoPage`/`ChamadosPage` × `CategoriasPage`);
  - um único `carregando` é compartilhado entre categorias e usuários;
  - `salvar*` e `desativar*` não sinalizam carregamento.
- **Direção:** separar em `categoriasAtivas` e `todasCategorias`, com um estado de carregamento por recurso.

### FE-05 — "Fonte única" da URL do backend não é única · **Baixa**

- **Onde:** [quasar.config.js:19-22](../../frontend/quasar.config.js#L19-L22) ("Único lugar que define a URL") e [api.js:16](../../frontend/src/services/api.js#L16), que repete o fallback `http://localhost:8080/api`.
- **Direção:** manter o fallback em um só lugar.

### FE-06 — `aplicarAtualizacao` mistura formatos na lista · **Baixa**

- **Onde:** [chamados.js:110-116](../../frontend/src/stores/chamados.js#L110-L116).
- **Evidência:** o `ChamadoResponse` completo (com `descricao` e `comentarios`) é mesclado sobre os itens da lista (`ChamadoResumoResponse`).
- **Direção:** copiar só os campos do resumo, ou recarregar a página atual.

### FE-07 — O toggle "Somente os meus" não tem efeito para o solicitante · **Baixa**

- **Onde:** [ChamadoFiltros.vue:94-96](../../frontend/src/components/ChamadoFiltros.vue#L94-L96).
- **Evidência:** o backend ignora `meus` quando o perfil é SOLICITANTE ([ChamadoSpecification.java:44-49](../../backend/src/main/java/br/com/helpdesk/repository/ChamadoSpecification.java#L44-L49)). O próprio comentário do componente reconhece que "não muda nada".
- **Direção:** esconder o toggle para o solicitante.

---

## 13. Divergências entre documentação e implementação

| ID | Documento | O que diz | O que o código faz |
|---|---|---|---|
| DOC-01 | [ARQUITETURA.md §4.1](../ARQUITETURA.md); Javadoc de `UsuarioAtual` | Dependência unidirecional `web → service → domain` | `service` importa `web.dto` 17 vezes (ARQ-01) |
| DOC-02 | [ARQUITETURA.md §4.7](../ARQUITETURA.md) | ADRs 001–013 | O código cita as ADRs **014–018**, que só existem em [.ai/architecture.md](../../.ai/architecture.md). Há duas fontes de verdade |
| DOC-03 | §4.8, §4.9, ADR-008 | `config/` contém `MetricasConfig`; existe um `application-prod.yml` | `MetricasConfig` não existe; `DominioConfig` e `WebMvcConfig` existem e não são citados; os perfis estão num único `application.yml` |
| DOC-04 | §4.10 e [CorsConfig.java:13-14](../../backend/src/main/java/br/com/helpdesk/config/CorsConfig.java#L13-L14) | CORS "apenas no perfil dev" e "em produção este bean deixa de ter efeito" | O bean não tem `@Profile` e fica ativo em todos os perfis |
| DOC-05 | §4.10 (Performance) | `@EntityGraph` nas consultas | Vale só para detalhe e dashboard; a listagem tem N+1 (CON-03) |
| DOC-06 | §4.11, tabela de métricas | `fora_sla` "sobre chamados abertos/em atendimento"; métricas "registradas no `ChamadoService`"; `api.js` "loga erros com o id" | O gauge inclui chamados resolvidos fora do prazo (coerente com §8.3, mas não com §4.11); os gauges são atualizados pelo `DashboardService`; `api.js` só exibe o id no notify, não o loga |
| DOC-07 | §4.11, Regras | "4xx em WARN sem stack trace"; "nenhum log INFO+ com dado pessoal" | Quatro classes de 4xx viram 500 com stack trace em ERROR (EXC-01); e-mail aparece em log WARN (SEG-04) |
| DOC-08 | §6, Endpoints | `GET /categorias` "lista categorias ativas"; códigos: 200, 201, 204, 400, 403, 404, 422 | O endpoint aceita `incluirInativas`; 401 existe (corrigido só em `.ai` pela ADR-017); 405 e 415 respondem 500 |
| DOC-09 | [business-rules.md](../../.ai/business-rules.md) I3, I9 | I3: o atendente é sempre ATENDENTE ou ADMIN; I9: usuário inativo recebe 401 | I3 é violável (REG-02); o login devolve 422 para inativo e 404 para inexistente (REG-04) |
| DOC-10 | §4.4, diagrama de componentes | `ChamadoService → SlaCalculator` | A dependência real passa pelo `ChamadoMapper` e pelo `DashboardService` (ARQ-04) |

**Direção geral:** consolidar as ADRs em um único documento (por exemplo, `docs/adr/NNN-titulo.md`, um arquivo por decisão) e fazer `docs/ARQUITETURA.md` e `.ai/architecture.md` apontarem para ele. Atualizar §4.8, §4.10, §4.11 e §6 depois da refatoração.

---

## 14. Testes

### TST-01 — Lacunas na camada web e nos cenários de risco · **Média**

- **Evidência:**
  - só o `ChamadoController` tem `@WebMvcTest`; `Categoria`, `Usuario`, `Dashboard` e `Auth` não têm, e o pacote `web.controller` está com 50% de cobertura;
  - não há teste para as exceções do framework (o EXC-01 teria sido pego), para concorrência (CON-01), para REG-01 e REG-02, nem para contagem de queries (CON-03);
  - o gate do JaCoCo cobre só `domain` e `service`.
- **Direção:** escrever testes de caracterização **antes** de cada refatoração. Cada achado Alto deste relatório deve começar como um teste vermelho, que então é corrigido.

### TST-02 — `@WebMvcTest` roda no perfil `dev` · **Baixa**

- **Onde:** [ChamadoControllerTest.java:46](../../backend/src/test/java/br/com/helpdesk/web/ChamadoControllerTest.java#L46). O log mostra `falling back to 1 default profile: "dev"`.
- **Evidência:** as demais suítes usam `@ActiveProfiles("test")`. Consequência direta do SEG-02.
- **Direção:** padronizar `@ActiveProfiles("test")`, ou remover o default `dev`.

### TST-03 — Frontend sem testes de páginas, interceptor e parte das stores · **Baixa**

- **Evidência:** há 4 arquivos de teste (`authStore`, `chamadosStore`, `ChamadoAcoes`, `SeletorTema`). Não são testados `api.js` (interceptor), `catalogoStore`, `dashboardStore` e nenhuma página. Não há métrica de cobertura.
- **Direção:** adicionar `@vitest/coverage-v8`, testar o interceptor (401 → logout, montagem da mensagem de erro) e o `catalogoStore`.

---

## 15. Roteiro sugerido para a modernização

A ordem abaixo minimiza o risco: primeiro a rede de testes, depois as correções de comportamento, e por último a estrutura.

| Fase | Objetivo | Achados | Observação |
|:---:|---|---|---|
| **0** | Rede de segurança | TST-01, TST-02 | Testes de caracterização do comportamento atual, mais testes que falham para os achados Altos |
| **1** | Corrigir comportamento incorreto | EXC-01, REG-01, REG-02, REG-04, CON-01, CON-02, CON-03, FE-02 | Mudanças localizadas e de alto valor; cada uma com teste |
| **2** | Endurecer configuração e segurança | SEG-02, SEG-03, SEG-04, SEG-05, SEG-06, SEG-07, SEG-01 (*fail-fast* em `prod`) | Sem alterar a ADR-003 no MVP |
| **3** | Enriquecer o domínio | DOM-01, DOM-02, DOM-03, ARQ-02, ARQ-04, EXC-02, DOM-06 | Agregado rico, capacidades no `Perfil`, efeitos nas transições |
| **4** | Corrigir a regra de dependência | ARQ-01, ARQ-03 | Comandos e resultados na camada de aplicação; política de visibilidade única |
| **5** | Frontend | FE-01 (com `acoesPermitidas` da fase 3), EXC-03, FE-03…FE-07, TST-03 | |
| **6** | Observabilidade e desempenho | CON-04, OBS-01, OBS-02, OBS-03 | Agregações no banco; gauges agendados |
| **7** | Limpeza e documentação | DOM-04, DOM-05, REG-03, REG-05, REG-06, EXC-04, CON-05, DOC-01…DOC-10 | ADRs consolidadas; documento de arquitetura atualizado |

**Critério de aceite de cada fase:** `./mvnw verify` e `npm test` verdes; nenhuma regressão nos pontos fortes da seção 4; os achados tratados marcados como resolvidos neste documento, com referência ao commit.
