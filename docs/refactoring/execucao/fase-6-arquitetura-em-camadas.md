# Fase 6 — Arquitetura em camadas (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §10](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `refactor`: **sem mudança de comportamento nem de contrato REST** (OpenAPI e respostas comparadas com a `main`, §5) |
| Tratado | **ARQ-01** (necessário), com o teste arquitetural previsto no plano |
| Revisado e mantido, com justificativa | `web.filter → repository` (§2.3) |
| Não tratado, de propósito | **EXC-02** (hierarquia de exceções, opcional): não envolve a direção das dependências, que é o escopo pedido para esta fase |

---

## 1. Revisão das dependências: antes

Matriz de imports entre pacotes, extraída do código-fonte antes de qualquer mudança:

| De → Para | Classes | Situação |
|---|---|---|
| `service` → `web.dto` | `CategoriaService`, `ChamadoService`, `DashboardService`, `UsuarioService` | **violação** (direção invertida) |
| `service.mapper` → `web.dto` | os 4 mappers | **violação** (direção invertida) |
| `web.filter` → `repository` | `UsuarioAtualFilter` | pula a camada de serviço (ver §2.3) |
| `web.*` → `service`, `domain.*` | controllers, DTOs, handler, filtros | ✓ para baixo |
| `service` → `domain.*`, `repository` | services | ✓ |
| `repository` → `domain.*` | repositórios, `Specification` | ✓ |
| `config` → `domain.*`, `web.filter` | `DominioConfig`, `WebMvcConfig` | ✓ raiz de composição |

**A única violação de direção era a do ARQ-01: 17 imports de `web.dto` em 8 classes de `service`.** O próprio `.ai/standards.md` já proibia isso ("`service` não pode conhecer qualquer tipo de `web`"), então o código original violava o padrão declarado.

---

## 2. Correções e decisões

### 2.1 Saídas: os `*Response` vão para `service.dto` (*Move Class*)

Os 7 records de resposta (`CategoriaResponse`, `ChamadoResponse`, `ChamadoResumoResponse`, `ComentarioResponse`, `DashboardResumoResponse`, `UsuarioResponse`, `UsuarioResumoResponse`) não tinham nenhuma anotação de web, só enums do domínio e tipos do Java. Eles **mudaram só de pacote**, com `git mv` para preservar o histórico. A web passa a importá-los de `service.dto`, que é a direção permitida.

- **Mesmo nome de classe:** os nomes dos schemas no OpenAPI não mudam.
- **Mesmos componentes do record:** o JSON é idêntico.
- **A conversão entidade → resposta continua dentro da transação do service.** Mover a conversão para o controller, com `open-in-view: false`, causaria `LazyInitializationException`, conforme o risco já apontado no plano.

### 2.2 Entradas: os services deixam de receber `*Request` (*Introduce Parameter Object*)

Os 6 `*Request` carregam o que é da interface HTTP: Bean Validation e `@Schema` do Swagger. Por isso eles **ficam em `web.dto.request`**. Os services passam a receber:

| Antes (tipo da web) | Depois (entrada do caso de uso) |
|---|---|
| `ChamadoService.abrir(NovoChamadoRequest, …)` | `abrir(DadosNovoChamado, …)` |
| `ChamadoService.comentar(id, NovoComentarioRequest, …)` | `comentar(id, String texto, …)`: um campo só, sem record para isso |
| `CategoriaService.criar/atualizar(…, CategoriaRequest, …)` | `…(…, DadosCategoria, …)` |
| `UsuarioService.criar/atualizar(…, UsuarioRequest, …)` | `…(…, DadosUsuario, …)` |

**Decisões:**
- Os records `Dados*` (em `service.dto`) têm **os mesmos nomes de componentes** dos requests. Por isso o corpo dos services não mudou; só o tipo e o nome do parâmetro (de `requisicao` para `dados`).
- A conversão fica no próprio request: `requisicao.paraDados()`. É uma linha, na direção `web → service`, e mantém os controllers como estavam: uma chamada por rota, sem nenhuma regra.
- **Nenhuma interface, porta ou camada nova.** São três records de dados e três métodos de conversão.

### 2.3 Revisado e mantido: `UsuarioAtualFilter` → `UsuarioRepository`

O filtro do login simulado carrega o usuário do `X-User-Id` direto do repositório, pulando a camada de serviço. **Não foi alterado** porque esse acesso é parte do desenho documentado:
- o diagrama de sequência da §4.6 tem `FI->>DB: SELECT usuario WHERE id = ?`;
- a ADR-003 faz do filter "o único ponto que conhece o mecanismo", que será trocado por JWT/SSO.

Desviar o acesso por um service não resolveria nenhum achado da auditoria e obrigaria a reescrever os mocks de dois testes de contrato da camada web (`ChamadoControllerTest`, `TratamentoDeErrosWebTest`).

O teste arquitetural **permite só essa exceção, com o nome da classe**, e **proíbe acesso a repositório em qualquer controller** ou outra classe da web. O `.ai/standards.md` passou a registrar a exceção.

---

## 3. Organização resultante

```
web ──────────────► service ──────────────► domain ◄──────────── repository
 │  controller        │  *Service              model, enums, state        *Repository
 │  dto/request ──┐   │  dto  (Dados*, *Response)  permissao, sla       ChamadoSpecification
 │  exception     └──►│  mapper                exception
 │  filter ───────────┼───────────────────────────────────────────────► (só UsuarioAtualFilter)
 └────────────────────┴──► domain (enums, exceções, Usuario)
config: raiz de composição (DominioConfig, WebMvcConfig, ...) — conhece todos
```

### Matriz depois

| De → Para | Classes |
|---|---|
| `service` → `web.*` | **nenhuma** (eram 8) |
| `service.mapper` → `web.*` | **nenhuma** (eram 4) |
| `web.controller` → `service`, `service.dto` | 5 controllers |
| `web.dto` (requests) → `service.dto` | 3 (`paraDados()`) |
| `service.dto` → `domain.enums` | 7 |
| `web.filter` → `repository` | 1 (`UsuarioAtualFilter`, exceção documentada) |
| `domain` → qualquer outra camada ou Spring | **nenhuma** |

---

## 4. Impacto sobre acoplamento

| Indicador | Antes | Depois |
|---|:---:|:---:|
| Imports de `web` em `service`/`domain`/`repository` | 17 (em 8 classes) | **0** |
| Ciclos entre pacotes de camada (`web ⇄ service`) | 1 | **0** |
| Classes de `service` que mudam se o contrato HTTP de entrada mudar (validação, Swagger) | 3 services | **0**: muda só o `*Request` e o `paraDados()` |
| Regras de dependência verificadas no build | 0 | **7** |

**Efeito prático:**
- Mudar validação ou documentação de um campo de entrada não toca mais nenhum service.
- Os casos de uso podem ser chamados sem nenhum tipo HTTP, por exemplo por um job ou por outro adaptador.
- Os testes unitários de service não importam mais nada da web.

As saídas (`*Response`) continuam sendo o contrato devolvido pela API. Elas agora pertencem à camada de aplicação, que é quem as produz.

---

## 5. Contrato REST preservado: comparação com a `main`

O jar da `main` (`c5f4335`) foi construído num `git worktree` temporário, já removido, e comparado com o jar desta branch. Mesmo seed, perfil `dev` e usuário admin.

**OpenAPI (`/v3/api-docs`):**
- **Schemas:** nenhum a mais e nenhum a menos. Os nomes foram preservados.
- **Rotas:** as mesmas.
- **Rotas com contrato diferente:** só `GET /api/auth/usuarios-disponiveis` e `POST /api/auth/login`, que passaram a devolver `UsuarioResumoResponse`. É a correção **deliberada** do SEG-03 na Fase 3, e não desta fase.
- **Schema `Usuario` com propriedades diferentes:** é a **entidade**. O springdoc documenta o parâmetro `UsuarioAtual` dos controllers como se fosse um parâmetro de consulta, e isso já acontecia na `main`, em 16 operações. As propriedades `admin`/`atendente`/`solicitante` sumiram porque a Fase 5 removeu os `isX()` sem uso. **Não é contrato REST:** nenhum cliente envia esse "parâmetro", que é preenchido pelo filter. Fica registrado como defeito de documentação preexistente, para a Fase 9 (`@Parameter(hidden = true)`).

**Respostas JSON** (`chamados?size=20`, `chamados/4`, `chamados/4/comentarios`, `categorias`, `usuarios`, `dashboard/resumo`): comparadas estruturalmente, com os instantes mascarados porque o seed é relativo ao horário de subida. **As seis são idênticas em estrutura, valores e ordem das chaves.** Uma primeira comparação textual acusou diferença na listagem; a comparação estrutural mostrou que era falso positivo da máscara textual.

**Testes de contrato sem edição:** `ChamadoContratoApiIT` (conjunto exato de campos), `DadosPessoaisIT`, `CadastrosApiIT`, `ErrosHttpApiIT`, `CicloDeVidaChamadoIT` e `ChamadoControllerTest`.

---

## 6. Testes

### Teste arquitetural novo: [DependenciaEntreCamadasTest.java](../../../backend/src/test/java/br/com/helpdesk/arquitetura/DependenciaEntreCamadasTest.java) (7)

Lê `src/main/java` (imports e nomes totalmente qualificados no corpo), sem biblioteca nova.

| Regra | Teste |
|---|---|
| O leitor enxerga as camadas e uma dependência real conhecida (sanidade) | `deveEnxergarAsDependenciasReais` |
| `service` não depende de `web` (ARQ-01) | `serviceNaoDependeDeWeb` |
| `service` não depende de `config` | `serviceNaoDependeDeConfig` |
| `domain` não depende de `web`, `service`, `repository` nem `config` | `dominioNaoDependeDeOutrasCamadas` |
| `domain` não importa Spring (ARQ-02, Fase 5) | `dominioNaoConheceSpring` |
| `repository` só conhece o domínio | `repositorioSoConheceODominio` |
| `web` não acessa `repository`, exceto `UsuarioAtualFilter` | `webNaoAcessaRepositorio` |

**Prova de que detecta:** reintroduzi temporariamente `import br.com.helpdesk.web.dto.request.CategoriaRequest;` no `CategoriaService`. O teste falhou com `CategoriaService -> [br.com.helpdesk.web.dto.request]`. O arquivo foi restaurado.

### Testes existentes adaptados (só tipos, imports e pacote; nenhuma asserção mudou)

- `CategoriaServiceTest`, `UsuarioServiceTest`, `ChamadoServiceTest`: passam `DadosCategoria`/`DadosUsuario`/`DadosNovoChamado` (e o texto do comentário como `String`) em vez dos `*Request`;
- `DashboardServiceTest`, `web/ChamadoRespostas`: só o import dos `*Response`.

### Arrumação

Os imports que o movimento deixou fora da ordem alfabética (convenção do projeto) foram reordenados em 15 arquivos. Isso é só reordenação de linhas dentro do mesmo bloco.

### Documentação do padrão (fonte que prevalece)

- [.ai/standards.md](../../../.ai/standards.md): árvore de pacotes (`service/dto`, `domain/permissao`, `domain/sla`, `arquitetura/`), tabela de camadas com a exceção do filter, e regras de DTO (onde fica cada tipo; `paraDados()`). Antes ela mandava pôr as respostas em `web/dto/response`, e seguir isso agora quebraria o build.
- [.ai/architecture.md](../../../.ai/architecture.md): tabela de responsabilidades por camada. A consolidação do `docs/ARQUITETURA.md` fica para a Fase 9.

---

## 7. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 280 ✓ | **287 ✓** (+7 de arquitetura), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 71 ✓ | **71 ✓** |
| Cobertura de linhas, backend | 96,9% | **97,0%** |
| Cobertura de branches, backend | 75,9% | **75,9%** |
| domain + service | 96,8% | **97,0%** |
| Testes `[DEFEITO CONHECIDO]` restantes | 6 | **6** (esta fase não corrige defeitos) |
