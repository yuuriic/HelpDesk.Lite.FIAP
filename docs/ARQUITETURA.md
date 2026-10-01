# HelpDesk Lite — Arquitetura do Sistema

**Disciplina:** AI Driven Development — Aula 1 (Prática: Criando a arquitetura de um sistema)
**Data:** 21/09/2026
**Autor:** _(preencher)_

---

## 1. Visão geral

Sistema web para abertura, triagem e acompanhamento de chamados internos (TI, Facilities, RH). Usuários abrem chamados, atendentes assumem e resolvem, administradores gerenciam categorias e usuários e acompanham indicadores.

**Escopo do MVP (próxima aula):** dados mockados em banco em memória, execução 100% local, sem autenticação real (login simulado por seleção de usuário).

---

## 2. Funcionalidades principais

| # | Funcionalidade | Descrição |
|---|---|---|
| F1 | Abrir chamado | Solicitante informa título, descrição, categoria e prioridade |
| F2 | Listar e filtrar chamados | Por status, prioridade, categoria e "meus chamados" |
| F3 | Assumir chamado | Atendente se torna responsável; status vai para `EM_ATENDIMENTO` |
| F4 | Alterar status | Transições controladas: `ABERTO → EM_ATENDIMENTO → RESOLVIDO → FECHADO` |
| F5 | Comentar no chamado | Linha do tempo de interações entre solicitante e atendente |
| F6 | Gerenciar categorias | CRUD de categorias com SLA em horas |
| F7 | Gerenciar usuários | CRUD de usuários e perfis |
| F8 | Dashboard | Totais por status, chamados fora do SLA, tempo médio de resolução |

---

## 3. Tipos de usuário e permissões

| Ação | Solicitante | Atendente | Admin |
|---|:---:|:---:|:---:|
| Abrir chamado | ✅ | ✅ | ✅ |
| Ver próprios chamados | ✅ | ✅ | ✅ |
| Ver todos os chamados | ❌ | ✅ | ✅ |
| Assumir chamado | ❌ | ✅ | ✅ |
| Alterar status (em atendimento / resolvido) | ❌ | ✅ (só os seus) | ✅ |
| Fechar chamado (confirmar resolução) | ✅ (só os seus) | ❌ | ✅ |
| Comentar | ✅ (só os seus) | ✅ | ✅ |
| Gerenciar categorias | ❌ | ❌ | ✅ |
| Gerenciar usuários | ❌ | ❌ | ✅ |
| Ver dashboard | ❌ | ✅ | ✅ |

---

## 4. Arquitetura

A arquitetura é apresentada em quatro níveis de zoom (modelo C4): contexto, containers, componentes e código. Depois, o caminho de uma requisição pelas camadas, as decisões arquiteturais com alternativas descartadas, a visão de implantação e os aspectos transversais.

### 4.1 Estilo arquitetural

**Monolito modular em camadas**, com dependência unidirecional: `web → service → domain ← repository`. O domínio não conhece a camada web nem a de persistência (as entidades carregam anotações JPA, mas nenhuma regra de negócio depende do Spring). Frontend e backend são desacoplados por contrato REST/JSON e vivem em processos separados.

O pacote `config` é a raiz de composição: é o único que pode depender de todas as camadas (por exemplo, `DominioConfig` instancia `TransicaoStatus`, `PermissaoService` e `SlaCalculator`, que não têm anotação do Spring). O `service` recebe e devolve só tipos de `service.dto` (`Dados*` na entrada, `*Response` na saída) e não importa nada de `web` (ADR-023). A regra é verificada no build por `arquitetura/DependenciaEntreCamadasTest`.

| Nível | O que mostra | Diagrama |
|---|---|---|
| 1 — Contexto | Quem usa o sistema e com o que ele se integra | 4.2 |
| 2 — Containers | Processos executáveis e armazenamentos | 4.3 |
| 3 — Componentes | Módulos internos do backend e do frontend | 4.4 e 4.5 |
| 4 — Código | Estrutura de pacotes e pastas | 4.8 |

### 4.2 Nível 1 — Contexto

```mermaid
flowchart TB
    SOL([Solicitante<br/>abre e acompanha chamados])
    ATE([Atendente<br/>assume e resolve chamados])
    ADM([Administrador<br/>gerencia cadastros e monitora])

    SYS["<b>HelpDesk Lite</b><br/>Sistema web de chamados internos"]

    EMAIL["Servidor de e-mail<br/><i>(fora do escopo do MVP)</i>"]
    SSO["Provedor de identidade / SSO<br/><i>(fora do escopo do MVP)</i>"]

    SOL --> SYS
    ATE --> SYS
    ADM --> SYS
    SYS -. notificações futuras .-> EMAIL
    SYS -. autenticação futura .-> SSO
```

No MVP o sistema é autocontido: não há integrações externas. As linhas tracejadas marcam onde elas entrariam sem mudar o desenho interno.

### 4.3 Nível 2 — Containers

```mermaid
flowchart LR
    subgraph Browser["Navegador do usuário"]
        SPA["<b>SPA</b><br/>Vue 3 + Quasar<br/>servida pelo Vite em :9000"]
    end

    subgraph JVM["Processo Java (Spring Boot) — :8080"]
        API["<b>API REST</b><br/>/api/**"]
        SWG["Swagger UI<br/>/swagger-ui.html"]
        H2C["Console H2<br/>/h2"]
    end

    DB[("<b>Banco relacional</b><br/>H2 em memória<br/>(mesmo processo da API no MVP)")]

    SPA -- "HTTP/JSON<br/>header X-User-Id" --> API
    API -- "JDBC / JPA" --> DB
    SWG -. documenta .-> API
    H2C -. inspeciona .-> DB
```

| Container | Tecnologia | Responsabilidade | Porta |
|---|---|---|---|
| SPA | Vue 3, Quasar, Pinia, Axios | Interface, navegação, estado de sessão, esconder ações sem permissão (só UX — a regra vale no backend) | 9000 |
| API REST | Spring Boot 3, Spring Web, Spring Data JPA | Regras de negócio, autorização, persistência, contrato da API | 8080 |
| Banco | H2 em memória (MVP) → PostgreSQL/SQL Server | Persistência relacional; seed via `data.sql` | embutido |
| Swagger UI | springdoc-openapi | Documentação viva e execução manual dos endpoints | 8080 |

### 4.4 Nível 3 — Componentes do backend

```mermaid
flowchart TB
    subgraph WEB["Camada Web (br.com.helpdesk.web)"]
        CORS[CorsConfig]
        RID["RequestIdFilter<br/>X-Request-Id → MDC"]
        FILT["UsuarioAtualFilter<br/>lê X-User-Id → UsuarioAtual"]
        CTRL["Controllers<br/>Auth · Chamado · Categoria<br/>Usuario · Dashboard"]
        VAL["Bean Validation<br/>nos DTOs de entrada"]
        ADV["GlobalExceptionHandler<br/>@RestControllerAdvice"]
    end

    subgraph SVC["Camada de Aplicação (service)"]
        CS[ChamadoService]
        CATS[CategoriaService]
        USS[UsuarioService]
        DSS[DashboardService]
        MAP["Mappers<br/>Entidade → *Response"]
        METR["MetricasNegocio<br/>counters/gauges/timer Micrometer"]
        APC["AposCommit<br/>métricas e auditoria<br/>só do que foi confirmado"]
    end

    subgraph DOM["Camada de Domínio (domain)"]
        ENT["Entidades com comportamento<br/>Usuario · Categoria<br/>Chamado (agregado) · Comentario"]
        ENUM["Enums<br/>Perfil (capacidades) · StatusChamado · Prioridade"]
        TRANS["TransicaoStatus<br/>máquina de estados"]
        PERM["PermissaoService<br/>quem pode fazer o quê"]
        SLA["SlaCalculator<br/>prazo e situação do SLA"]
        DEXC["Exceções de domínio<br/>TransicaoInvalida · AcessoNegado<br/>RecursoNaoEncontrado · RegraNegocio<br/>NaoAutenticado"]
    end

    subgraph INFRA["Camada de Infraestrutura (repository)"]
        REPO["Repositories<br/>Spring Data JPA"]
        SPEC["ChamadoSpecification<br/>filtros dinâmicos + visibilidade"]
        SEED["data.sql<br/>seed do MVP"]
    end

    DB[("H2")]

    RID --> FILT --> CTRL --> VAL
    CTRL --> CS & CATS & USS & DSS
    CS --> PERM & MAP & METR & APC
    CS --> ENT
    ENT -. valida a aresta em .-> TRANS
    MAP --> SLA
    DSS --> SLA & METR
    CS & CATS & USS & DSS --> REPO
    REPO --> SPEC
    REPO --> ENT
    ENT --> ENUM
    CS -. lança .-> DEXC
    DEXC -. capturadas por .-> ADV
    REPO --> DB
    SEED --> DB
```

| Componente | Responsabilidade | Não faz |
|---|---|---|
| `RequestIdFilter` | Gera ou reaproveita `X-Request-Id`, coloca no MDC e devolve no header da resposta | Não sabe quem é o usuário |
| `UsuarioAtualFilter` | Lê `X-User-Id`, carrega o usuário, coloca `UsuarioAtual` no request e `usuarioId` no MDC; 401 se ausente/inválido/inativo | Não decide permissão |
| Controllers | Rotas, status HTTP, DTO de entrada/saída, delega para service | Nenhuma regra de negócio |
| `PermissaoService` | Traduz a tabela de permissões (seção 3) em código: perfil + relação com o chamado (é o solicitante? é o atendente?) | Não acessa banco |
| `TransicaoStatus` | Tabela de transições válidas e quem executa cada uma; único lugar que conhece a máquina de estados | Não sabe de HTTP |
| `ChamadoService` | Orquestra: busca → permissão → agregado (`Chamado.alterarStatus`, que valida a aresta na `TransicaoStatus`) → persiste → mapeia | Não formata resposta HTTP; não altera campo do chamado direto (a entidade não tem setters) |
| `ChamadoSpecification` | Monta a query dinâmica dos filtros e **injeta `solicitanteId = usuarioAtual` quando o perfil é SOLICITANTE** | — |
| `SlaCalculator` | Prazo, fora do SLA, tempo médio de resolução (regras da seção 8.3); recebe `Clock` injetado. Usado pelo `ChamadoMapper` (campos de SLA da resposta) e pelo `DashboardService` | Não acessa banco |
| `MetricasNegocio` | Encapsula os counters/gauges/timers da seção 4.11; único ponto que fala com o `MeterRegistry` | Não contém regra de negócio |
| `AposCommit` | Adia para depois do commit os incrementos de métricas e as linhas de auditoria (ADR-024) | Não decide o que medir |
| `GlobalExceptionHandler` | Converte exceções em JSON padrão (tabela completa na seção 6), inclusive os erros do próprio Spring MVC (404/405/415/400) e os conflitos de concorrência (409) | Nunca devolve stack trace |

### 4.5 Nível 3 — Componentes do frontend

```mermaid
flowchart TB
    subgraph FE["SPA — Vue 3 + Quasar"]
        ROUTER["Vue Router<br/>guard: exige login e checa<br/><code>meta.perfis</code> da rota"]
        LAYOUT["MainLayout<br/>menu lateral filtrado por perfil"]

        subgraph PAGES["Pages"]
            P1[LoginPage]
            P2[ChamadosPage]
            P3[ChamadoDetalhePage]
            P4[NovoChamadoPage]
            P5[CategoriasPage]
            P6[UsuariosPage]
            P7[DashboardPage]
        end

        subgraph COMP["Components reutilizáveis"]
            C1[StatusBadge / PrioridadeBadge]
            C2["ChamadoAcoes<br/>botões conforme perfil + status"]
            C3[ComentarioTimeline]
            C4[ChamadoFiltros]
        end

        subgraph STORE["Pinia stores"]
            S1["authStore<br/>usuário logado, helpers<br/>isAdmin / isAtendente,<br/>revalidarSessao"]
            S2["chamadosStore<br/>lista, filtros, paginação, detalhe"]
            S3["catalogoStore<br/>categorias ativas, todas as categorias<br/>e usuários (carregamentos separados)"]
            S4["dashboardStore<br/>resumo"]
        end

        subgraph SRV["Services"]
            API["api.js<br/>Axios: baseURL, interceptor de<br/>X-User-Id, tratamento de erro,<br/>aviso de sessão perdida"]
            SV["chamadosService · categoriasService<br/>usuariosService · dashboardService"]
        end

        BOOT["boot/sessao.js<br/>revalida a sessão salva ao iniciar<br/>e liga o api.js ao logout"]
        CONST["constants/dominio.js · utils/formatacao.js<br/>rótulos, cores e datas"]
    end

    BE[("API REST :8080")]

    ROUTER --> LAYOUT --> PAGES
    ROUTER --> S1
    PAGES --> COMP
    PAGES --> STORE
    PAGES & COMP -.-> CONST
    BOOT --> S1
    BOOT --> API
    STORE --> SV --> API --> BE
```

Regras de organização do frontend:

- **Pages** só montam tela e disparam ações da store; não chamam Axios direto.
- **Stores** guardam estado e chamam services; é onde fica a lógica de "recarregar lista após ação".
- **`api.js`** é o único ponto que conhece a URL do backend e o header `X-User-Id`; um interceptor de resposta converte o JSON de erro em `q-notify`. Num 401, avisa a quem se registrou em `definirAoPerderASessao` (o `boot/sessao.js` liga o logout do `authStore`).
- **Rótulos, cores e ícones** de perfil, status e prioridade ficam em `src/constants/dominio.js`, e a formatação de data em `src/utils/formatacao.js`; nenhuma tela mantém cópia própria.
- **`ChamadoAcoes`** decide os botões por uma tabela declarativa que espelha a `TransicaoStatus` do backend, e desabilita todos enquanto uma ação está em andamento (prop `processando`).
- **Router guard** redireciona para login se `authStore` está vazio e para `/chamados` se o perfil não está em `meta.perfis`. Isso é UX: o backend continua validando tudo.

### 4.6 Caminho de uma requisição pelas camadas

Exemplo: atendente marca chamado como `RESOLVIDO`.

```mermaid
sequenceDiagram
    participant UI as ChamadoDetalhePage
    participant ST as chamadosStore
    participant AX as api.js
    participant FI as UsuarioAtualFilter
    participant CT as ChamadoController
    participant VA as Bean Validation
    participant SV as ChamadoService
    participant PE as PermissaoService
    participant TR as TransicaoStatus
    participant RP as ChamadoRepository
    participant DB as H2
    participant EH as GlobalExceptionHandler

    UI->>ST: alterarStatus(123, RESOLVIDO)
    ST->>AX: PATCH /api/chamados/123/status
    Note over AX: interceptor injeta X-User-Id
    AX->>FI: request
    FI->>DB: SELECT usuario WHERE id = ?
    FI->>CT: request + UsuarioAtual (ou 401)
    CT->>VA: valida AlterarStatusRequest
    VA-->>CT: ok (ou MethodArgumentNotValid → 400)
    CT->>SV: alterarStatus(123, RESOLVIDO, usuarioAtual)
    SV->>RP: findById(123)
    RP->>DB: SELECT chamado
    RP-->>SV: Chamado (ou RecursoNaoEncontrado → 404)
    SV->>PE: verificarAlteracaoStatus(usuario, chamado, RESOLVIDO)
    PE-->>SV: ok (ou AcessoNegado → 403)
    SV->>SV: chamado.alterarStatus(RESOLVIDO, perfil, transicoes, agora)
    SV->>TR: (dentro do agregado) validar(EM_ATENDIMENTO → RESOLVIDO)
    TR-->>SV: ok (ou TransicaoInvalida → 422)
    SV->>RP: save
    RP->>DB: UPDATE chamado ... WHERE id = ? AND versao = ? (ou 409)
    Note over SV: depois do commit: métricas e linha de auditoria
    SV-->>CT: ChamadoResponse
    CT-->>AX: 200 + JSON
    AX-->>ST: dados
    ST->>ST: atualiza detalhe e lista
    ST-->>UI: re-render

    Note over EH: qualquer exceção lançada entre<br/>FI e RP é convertida no JSON padrão de erro
```

Pontos a observar:

1. **Duas verificações distintas e em ordem fixa:** primeiro *quem pode* (permissão, 403), depois *se pode agora* (transição, 422). Inverter a ordem vazaria informação sobre o estado do chamado para quem não deveria vê-lo.
2. **O controller não sabe o resultado da regra** — só repassa e traduz exceções via handler.
3. **A visibilidade do solicitante é aplicada na query**, não filtrando em memória: `ChamadoSpecification` adiciona `solicitante_id = :usuarioAtual` quando o perfil é `SOLICITANTE`, então nem a paginação nem o `count` vazam registros alheios.

### 4.7 Decisões arquiteturais (ADRs)

| ADR | Decisão | Alternativas consideradas | Por que essa |
|---|---|---|---|
| 001 | Monorepo com `backend/` e `frontend/` | Dois repositórios | Uma entrega, um clone, um vídeo. Separar só faz sentido com times ou pipelines distintos |
| 002 | Monolito em camadas | Arquitetura hexagonal / clean; microsserviços | 4 entidades e ~20 endpoints não justificam portas e adaptadores. Dependência unidirecional já dá o isolamento necessário |
| 003 | Autenticação simulada via header `X-User-Id` resolvido em um filter | Spring Security + JWT desde o início | Foco no domínio. O filter é o único ponto que conhece o mecanismo — trocar por JWT mexe em uma classe |
| 004 | Autorização em `PermissaoService` chamado pelo service | `@PreAuthorize` por método | Sem Spring Security no MVP; e as regras dependem da **relação** usuário–chamado (dono? atendente responsável?), não só do perfil — isso é regra de negócio, não de infraestrutura |
| 005 | Máquina de estados em `TransicaoStatus` (tabela `de → para → perfis`) | `if/else` no service; Spring StateMachine | Um lugar só, testável isoladamente, legível como tabela. Spring StateMachine é exagero |
| 006 | DTOs com mapeamento manual | MapStruct; expor entidade JPA | Poucas classes; evita lib e processador de anotações. Expor entidade acopla API ao banco e causa lazy-loading acidental |
| 007 | Filtros com JPA `Specification` | Métodos derivados (`findByStatusAndPrioridade...`); JPQL manual | Combinação de 4 filtros opcionais + regra de visibilidade explode em métodos derivados; `Specification` compõe |
| 008 | H2 em memória com `data.sql` | PostgreSQL via Docker | Zero setup, roda em qualquer máquina da apresentação. JPA abstrai a troca; o perfil `prod` (no mesmo `application.yml`) já desliga o seed e valida o schema, e o datasource real entra por variável de ambiente |
| 009 | Soft delete (`ativo = false`) em usuário e categoria | Hard delete com cascata | Chamado histórico precisa continuar referenciando quem abriu e qual categoria |
| 010 | Erros como exceções de domínio + handler central | Retornar objeto `Result`/`Either` | Idiomático em Spring, menos ruído no service, mapeamento HTTP concentrado no handler |
| 011 | Listagens paginadas (`page`, `size`, `sort`) desde o MVP | Retornar lista completa | Custo zero com Spring Data `Pageable`; evita reescrever a tela depois |
| 012 | Observabilidade via Actuator + Micrometer + `X-Request-Id` no MDC | Só `System.out`/logs soltos; stack completa (Prometheus/Grafana/OTel) já no MVP | Actuator é uma dependência e zero código; dá health, métricas e correlação de logs. A stack externa pluga depois sem tocar no negócio |
| 013 | `Clock` injetável e `UsuarioAtual` por parâmetro | `Instant.now()` direto; `ThreadLocal`/`SecurityContextHolder` | Regras de SLA e permissão viram testes unitários puros; sem mock de contexto estático |
| 014 | CORS liberado só para `http://localhost:9000` | `allowedOrigins("*")`; proxy do Vite | Origem única e explícita; `X-Request-Id` exposto para o `q-notify` |
| 015 | `PermissaoService` responde "quem pode" (403); `TransicaoStatus`, "pode agora" (422) | Tudo na máquina de estados; tudo na permissão | Mantém a ordem 403 → 422 sem duplicar a máquina de estados |
| 016 | Reabertura é ação do solicitante dono (e do admin), pela rota de status | Endpoint `/reabrir`; atendente reabre | Segue o diagrama de estados sem inventar rota |
| 017 | 401 `NAO_AUTENTICADO` escrito pelo filter | `HandlerExceptionResolver` no filter | Mesmo formato de erro antes do `DispatcherServlet` |
| 018 | Gauges recalculados na leitura do dashboard (revisada na Fase 8: mantida) | `@Scheduled`; `Gauge` que consulta a cada scrape | Sem agendador nem query por scrape no MVP |
| 019 | Erros do Spring MVC tratados no handler central; conflitos como 409 `CONFLITO`; login com 401 | Deixar 404/405/415 caírem no 500 genérico | Nenhum erro do cliente vira 500; o login segue a regra I9 do filter |
| 020 | Concorrência por *optimistic locking* (`@Version` no `Chamado`) e guardas das invariantes I3/I11 no `UsuarioService` | Lock pessimista; nenhuma proteção | Duas assunções simultâneas: uma vence, a outra recebe 409 |
| 021 | Configuração base fechada; `dev` explícito; `prod` recusa subir com o login simulado | Perfil `dev` como padrão | Esquecer o perfil num deploy não abre console, Swagger nem login por header |
| 022 | `Chamado` como agregado com comportamento; permissão e SLA no pacote `domain` | Manter o modelo anêmico com setters | A regra fica num só lugar e é testada sem Spring |
| 023 | Contratos do service em `service.dto`; dependência entre camadas verificada por teste | Manter o service importando `web.dto` | Restaura a dependência unidirecional da ADR-002 |
| 024 | Métricas e auditoria emitidas depois do commit | Emitir dentro da transação | Operação revertida não conta nem deixa auditoria falsa |

O texto completo de cada ADR (contexto, decisão, alternativas e consequências) está em [`.ai/architecture.md`](../.ai/architecture.md), seção 2, que é a fonte única. As ADRs 019–024 registram as decisões da modernização; o detalhe de cada uma está nos relatórios de [`docs/refactoring/execucao/`](refactoring/execucao/).

### 4.8 Nível 4 — Estrutura de código

```
helpdesk-lite/
├── .ai/                          # contexto para agentes (Aula 2)
├── docs/ARQUITETURA.md           # este documento
├── backend/
│   ├── pom.xml
│   └── src/main/java/br/com/helpdesk/
│       ├── HelpdeskApplication.java
│       ├── config/               # raiz de composicao: AutenticacaoSimuladaConfig, ClockConfig, CorsConfig,
│       │                         # DominioConfig, OpenApiConfig, WebMvcConfig
│       ├── web/
│       │   ├── filter/           # RequestIdFilter, UsuarioAtualFilter, UsuarioAtual, UsuarioAtualArgumentResolver
│       │   ├── controller/       # AuthController, ChamadoController, ...
│       │   ├── dto/request/      # NovoChamadoRequest, AlterarStatusRequest, ... (Bean Validation; paraDados())
│       │   └── exception/        # GlobalExceptionHandler, ErroResponse
│       ├── service/              # ChamadoService, CategoriaService, UsuarioService, DashboardService,
│       │   │                     # MetricasNegocio, AposCommit
│       │   ├── dto/              # Dados* (entrada do caso de uso) e *Response (saida)
│       │   └── mapper/           # Entidade -> *Response
│       ├── domain/
│       │   ├── model/            # Usuario, Categoria, Chamado (agregado), Comentario
│       │   ├── enums/            # Perfil, StatusChamado, Prioridade
│       │   ├── state/            # TransicaoStatus
│       │   ├── permissao/        # PermissaoService
│       │   ├── sla/              # SlaCalculator, SituacaoSla
│       │   └── exception/        # TransicaoInvalidaException, AcessoNegadoException, NaoAutenticadoException, ...
│       └── repository/           # *Repository, ChamadoSpecification
│   ├── src/main/resources/
│   │   ├── application.yml       # configuracao base (fechada) + perfis dev, prod e test no mesmo arquivo
│   │   ├── META-INF/spring.factories  # registra a guarda do login simulado (EnvironmentPostProcessor)
│   │   ├── logback-spring.xml    # pattern com requestId e usuarioId (MDC)
│   │   └── data.sql              # seed
│   └── src/test/java/br/com/helpdesk/
│       ├── arquitetura/          # DependenciaEntreCamadasTest
│       ├── config/               # AutenticacaoSimuladaConfigTest
│       ├── domain/               # ChamadoTest, TransicaoStatusTest, PermissaoServiceTest, SlaCalculatorTest, PerfilTest
│       ├── service/              # ChamadoServiceTest, CategoriaServiceTest, UsuarioServiceTest, ...
│       ├── web/                  # ChamadoControllerTest (@WebMvcTest), GlobalExceptionHandlerTest, ...
│       ├── repository/           # ChamadoSpecificationTest (@DataJpaTest)
│       ├── integration/          # *IT (@SpringBootTest): ciclo de vida, contrato, erros, concorrencia, ...
│       │   └── configuracao/     # perfis (sem perfil, dev, prod), seed, OpenAPI, metricas expostas
│       └── support/              # builders e fixtures
└── frontend/
    ├── quasar.config.js
    └── src/
        ├── boot/                 # tema.js, sessao.js (revalida a sessao salva)
        ├── router/               # index.js, routes.js (meta.perfis por rota)
        ├── layouts/              # MainLayout.vue, LoginLayout.vue
        ├── pages/                # LoginPage, ChamadosPage, ChamadoDetalhePage, ...
        ├── components/           # StatusBadge, PrioridadeBadge, ChamadoAcoes, ComentarioTimeline, ChamadoFiltros, SeletorTema
        ├── constants/dominio.js  # rotulos, cores e icones de perfil, status e prioridade
        ├── utils/formatacao.js   # formatacao de data e hora
        ├── stores/               # auth.js, chamados.js, catalogo.js, dashboard.js
        ├── services/             # api.js, chamados.js, categorias.js, usuarios.js, dashboard.js
        └── __tests__/            # Vitest: stores, components, boot e api.js
```

### 4.9 Visão de implantação

```mermaid
flowchart LR
    subgraph DEV["MVP — máquina local"]
        D1["Terminal 1<br/>mvn spring-boot:run<br/>:8080 (API + H2 embutido)"]
        D2["Terminal 2<br/>quasar dev<br/>:9000 (SPA com hot reload)"]
        D2 -- "CORS liberado<br/>para :9000" --> D1
    end

    subgraph PROD["Evolução — produção (fora do escopo)"]
        N["Nginx<br/>serve build da SPA<br/>proxy /api → app"]
        A["Container Spring Boot<br/>perfil prod"]
        P[("PostgreSQL /<br/>SQL Server")]
        N --> A --> P
    end
```

Em produção a SPA e a API ficam sob a mesma origem (Nginx faz proxy de `/api`), o que dispensa o CORS. Os perfis estão todos no mesmo `application.yml`. A configuração base (sem perfil) já desliga console H2, Swagger e o login simulado e expõe só `health` e `prometheus`. O `./mvnw spring-boot:run` ativa o `dev`. O perfil `prod` acrescenta `ddl-auto: validate` e desliga o seed, e a aplicação **não sobe** em `prod` com `helpdesk.auth.simulada=true` (ADR-021). O datasource real entra por variável de ambiente (`SPRING_DATASOURCE_URL`, …), sem arquivo versionado.

**Migração de schema para `prod`.** Com `ddl-auto: validate`, um banco criado antes da coluna de *optimistic locking* (ADR-020) não sobe. O script é:

```sql
ALTER TABLE chamado ADD COLUMN versao BIGINT DEFAULT 0 NOT NULL;
```

Em `dev` e `test` o schema é recriado a cada boot e não precisa de script.

### 4.10 Aspectos transversais e requisitos não funcionais

| Aspecto | Como é tratado |
|---|---|
| Validação de entrada | Bean Validation nos DTOs (`@NotBlank`, `@Size`, `@NotNull`); erro 400 com lista de campos inválidos |
| Tratamento de erros | Handler central; formato único de erro (seção 6), inclusive para erros do Spring MVC (404 de rota, 405, 415, JSON malformado) e conflitos (409); nenhum stack trace vaza para o cliente |
| Autorização | Sempre no backend (`PermissaoService` + `Specification`); frontend só esconde botões |
| CORS | Liberado apenas para a origem `http://localhost:9000`. O `CorsConfig` vale em todos os perfis; em produção a SPA e a API ficam sob a mesma origem e a regra não é usada |
| Concorrência | *Optimistic locking* (`@Version`) no `Chamado`: a gravação que perde a corrida recebe 409 `CONFLITO` (ADR-020). As guardas de I3/I11 travam as linhas de `usuario` envolvidas (`SELECT ... FOR UPDATE`, admins ativos por id e depois o alvo; o `assumir` trava o atendente): a requisição concorrente espera e decide sobre o estado gravado (revisão da ADR-020) |
| Paginação e ordenação | `Pageable` do Spring Data em todas as listagens; padrão `size=20`, `sort=criadoEm,desc` |
| Datas e fuso | Armazenadas em UTC (`Instant`), serializadas em ISO-8601, exibidas em `America/Sao_Paulo` no frontend |
| Documentação da API | OpenAPI gerada pelo springdoc a partir das anotações dos controllers e DTOs |
| Logs, métricas e health | Detalhado em 4.11: `RequestIdFilter` + MDC, Actuator/Micrometer com métricas de negócio, log de auditoria de transições |
| Configuração | Um `application.yml` com a configuração base (fechada) e os perfis `dev`, `prod` e `test`; nada sensível versionado (ADR-021) |
| Testes | Detalhado em 4.12: pirâmide unitário → slice → integração; regras de domínio testáveis sem Spring; `Clock` injetável |
| Performance | Índices em `chamado(status)`, `chamado(solicitante_id)`, `chamado(atendente_id)`; `fetch = LAZY` nas associações, com `@EntityGraph` na listagem paginada, no detalhe, no dashboard e nos comentários: número de consultas por requisição constante, sem N+1 (verificado por `ConsultasPorRequisicaoIT`) |
| Segurança (evolução) | Trocar `UsuarioAtualFilter` por autenticação JWT/SSO; `PermissaoService` permanece igual |

### 4.11 Observabilidade

Objetivo: em qualquer momento responder "o sistema está saudável?", "o que aconteceu com o chamado #123?" e "quantos chamados estão fora do SLA agora?" sem abrir o banco na mão. No MVP tudo roda no próprio processo (Actuator); a evolução pluga Prometheus/Grafana e tracing sem mudar código de negócio.

```mermaid
flowchart LR
    subgraph APP["API Spring Boot"]
        RID["RequestIdFilter<br/>gera/propaga X-Request-Id<br/>→ MDC"]
        LOG["Logback<br/>logs com requestId, usuarioId"]
        MET["Micrometer<br/>métricas técnicas + de negócio"]
        ACT["Actuator<br/>/actuator/health<br/>/actuator/metrics<br/>/actuator/prometheus"]
        AUD["Log de auditoria<br/>transições e ações admin"]
        RID --> LOG
        RID --> AUD
        MET --> ACT
    end

    subgraph FE["SPA"]
        AXI["api.js<br/>lê o X-Request-Id da resposta<br/>e o mostra no q-notify de erro"]
    end

    subgraph EVO["Evolução (fora do MVP)"]
        PROM["Prometheus"]
        GRAF["Grafana"]
        OTEL["OpenTelemetry<br/>tracing distribuído"]
        PROM --> GRAF
    end

    AXI -- "X-Request-Id" --> RID
    ACT -. scrape .-> PROM
    LOG -. exporta .-> OTEL
```

**Os três pilares no MVP**

| Pilar | Implementação | O que responde |
|---|---|---|
| **Logs** | SLF4J + Logback. `RequestIdFilter` cria ou reaproveita `X-Request-Id`, coloca `requestId` e `usuarioId` no MDC; o mesmo id volta no header da resposta e é exibido no `q-notify` de erro do frontend | "O usuário reclamou de um erro — qual foi?" Basta pedir o id que apareceu na tela e filtrar o log |
| **Métricas** | Spring Boot Actuator + Micrometer. Métricas técnicas prontas (latência e status por endpoint, JVM, pool JDBC) + métricas de negócio: counters e timer emitidos pelo `ChamadoService` (depois do commit), gauges publicados pelo `DashboardService`, todos via `MetricasNegocio` | "A API está lenta?", "Quantos chamados foram abertos hoje?", "Quantos estão fora do SLA agora?" |
| **Health** | `/actuator/health` com indicador do banco; usado como readiness em produção | "Posso mandar tráfego pra essa instância?" |
| **Tracing** (evolução) | OpenTelemetry via Micrometer Tracing; o `X-Request-Id` já existe como trace id de fato | "Onde o tempo foi gasto nessa requisição?" |

**Métricas de negócio expostas**

| Métrica | Tipo | Tags | Registrada em |
|---|---|---|---|
| `helpdesk_chamados_abertos_total` | Counter | `categoria`, `prioridade` | `ChamadoService.abrir`, depois do commit |
| `helpdesk_chamados_transicoes_total` | Counter | `de`, `para`, `perfil` | `ChamadoService.alterarStatus` / `assumir`, depois do commit |
| `helpdesk_chamados_por_status` | Gauge | `status` | Recalculado na leitura do dashboard (ADR-018) |
| `helpdesk_chamados_fora_sla` | Gauge | — | Recalculado na leitura do dashboard, pela regra da seção 8.3: abertos/em atendimento com prazo vencido **e** resolvidos depois do prazo |
| `helpdesk_tempo_resolucao_seconds` (`_count`, `_sum`, `_max`) | Timer | `categoria` | Ao entrar em `RESOLVIDO` (`resolvidoEm − criadoEm`), depois do commit. O nome no Micrometer é `helpdesk_tempo_resolucao`; o sufixo `_seconds` vem do Prometheus |

**Log de auditoria** — toda transição de status e toda ação administrativa (CRUD de usuário/categoria) **confirmadas** geram uma linha `INFO` estruturada com: `requestId`, `usuarioId`, `perfil`, `acao`, `chamadoId`, `statusDe`, `statusPara`. A linha é escrita depois do commit (ADR-024): uma operação revertida não deixa rastro falso. Isso reconstrói a história de qualquer chamado sem tabela extra de auditoria no MVP (evolução: persistir em `chamado_evento`).

**Regras**

- Nenhum log em nível `INFO` ou acima contém dados pessoais além do id do usuário.
- `DEBUG` de SQL só no perfil `dev`.
- Actuator: na configuração base e em `prod`, só `health` (sem detalhes) e `prometheus`; em `dev`, também `info` e `metrics`, em `localhost`. `env` e `loggers` não são expostos em perfil nenhum.
- E-mail não aparece em log: as rotas públicas do login devolvem só `id`, `nome` e `perfil`.
- Erros tratados pelo `GlobalExceptionHandler`: 4xx logados em `WARN` sem stack trace; 5xx em `ERROR` com stack trace.

### 4.12 Testabilidade

A arquitetura foi desenhada para que as regras mais importantes sejam testáveis **sem subir o Spring**. O que faz isso possível:

| Decisão de design | Efeito na testabilidade |
|---|---|
| `TransicaoStatus`, `SlaCalculator`, `PermissaoService` e o agregado `Chamado` são classes puras do pacote `domain` (sem anotação do Spring, sem repositório, sem `HttpServletRequest`) | Testam-se com `new` e JUnit, em milissegundos |
| O usuário atual chega como **parâmetro** do service (o controller recebe `UsuarioAtual` e repassa a entidade `Usuario`), não via `ThreadLocal`/`SecurityContext` | Teste do service monta o usuário na mão; sem mock de contexto |
| `Clock` injetado (`java.time.Clock`) em `SlaCalculator` e no `ChamadoService` | Testes de SLA e de `resolvidoEm` usam `Clock.fixed(...)`; nada de `Thread.sleep` ou datas "quase certas" |
| Repositórios são interfaces do Spring Data | Service testa com Mockito; a query real testa isolada com `@DataJpaTest` |
| `ChamadoSpecification` é uma classe separada | Teste de visibilidade do solicitante roda contra H2 real, não contra mock |
| Handler de erro centralizado | Um `@WebMvcTest` cobre o mapeamento exceção → HTTP para todos os controllers |
| `api.js` isolado no frontend | Stores testam com Axios mockado (`vi.mock`) |

**Pirâmide de testes**

```mermaid
flowchart TB
    E2E["<b>E2E</b> (evolução)<br/>Cypress: fluxo completo pela UI<br/>0 no MVP"]
    INT["<b>Integração</b><br/>@SpringBootTest + H2<br/>ciclo de vida, contrato da API, erros HTTP,<br/>concorrência, perfis e observabilidade"]
    SLICE["<b>Fatias (slices)</b><br/>@WebMvcTest: controllers + validação + handler<br/>@DataJpaTest: Specification e repositories"]
    UNIT["<b>Unitários</b><br/>domain e service com JUnit 5 + Mockito + AssertJ<br/>Vitest: stores e components"]

    E2E --- INT --- SLICE --- UNIT

    style E2E fill:none,stroke-dasharray: 5 5
```

**O que cada nível cobre**

| Nível | Alvo | Casos obrigatórios no MVP |
|---|---|---|
| Unitário — domínio | `TransicaoStatus` | Todas as transições válidas; toda transição inválida lança `TransicaoInvalidaException`; reabertura só de `RESOLVIDO` |
| Unitário — domínio | `SlaCalculator` | Prazo correto; fora do SLA aberto vs resolvido tarde; tempo médio com zero, um e vários chamados |
| Unitário — service | `PermissaoService` | Cada linha da tabela da seção 3, incluindo os casos "só os seus" |
| Unitário — service | `ChamadoService` (repos mockados) | Assumir muda atendente e status; permissão é checada **antes** da transição; `resolvidoEm`/`fechadoEm` preenchidos com o `Clock` |
| Slice — web | `@WebMvcTest(ChamadoController)` | 400 em DTO inválido; 401 sem header; 403/404/422 mapeados a partir das exceções; JSON de erro no formato padrão |
| Slice — dados | `@DataJpaTest` | `ChamadoSpecification`: solicitante não vê chamados de outros nem no `count`; combinação de filtros |
| Integração | `@SpringBootTest` + `MockMvc` (ou servidor real) | Abrir → assumir → comentar → resolver → fechar com três usuários diferentes do seed; contrato JSON das rotas; erros HTTP; concorrência (409); configuração de cada perfil |
| Arquitetura | `DependenciaEntreCamadasTest` | Nenhuma dependência proibida entre camadas (ADR-023) |
| Frontend — unitário | `authStore`, `chamadosStore`, `ChamadoAcoes` | Helpers de perfil; recarrega lista após ação; botões exibidos conforme perfil + status |

**Convenções**

- Nome dos testes descreve regra, não método: `deveRejeitarTransicaoDeAbertoParaResolvido`, não `testAlterarStatus2`.
- Padrão *given/when/then* nos testes de service; fixtures via builders (`ChamadoBuilder.aberto().comSolicitante(...)`), nunca reaproveitando o `data.sql`.
- Testes de integração usam perfil `test` com H2 limpo (`@Transactional` faz rollback por teste).
- Cobertura mínima: 80% em `domain` e `service` (JaCoCo no `mvn verify`); controllers e frontend sem meta numérica no MVP.
- `mvn test` e `npm run test` são pré-requisito de todo commit de etapa (ver prompt de implementação).

---

## 5. Entidades principais e relacionamentos

```mermaid
erDiagram
    USUARIO ||--o{ CHAMADO : "abre (solicitante)"
    USUARIO o|--o{ CHAMADO : "atende (atendente)"
    CATEGORIA ||--o{ CHAMADO : "classifica"
    CHAMADO ||--o{ COMENTARIO : "possui"
    USUARIO ||--o{ COMENTARIO : "escreve"

    USUARIO {
        long id PK
        string nome
        string email UK
        string perfil "SOLICITANTE | ATENDENTE | ADMIN"
        boolean ativo
    }

    CATEGORIA {
        long id PK
        string nome UK
        int slaHoras
        boolean ativa
    }

    CHAMADO {
        long id PK
        string titulo
        string descricao
        string status "ABERTO | EM_ATENDIMENTO | RESOLVIDO | FECHADO"
        string prioridade "BAIXA | MEDIA | ALTA"
        long categoriaId FK
        long solicitanteId FK
        long atendenteId FK "nulo até ser assumido"
        datetime criadoEm
        datetime atualizadoEm
        datetime resolvidoEm
        datetime fechadoEm
        long versao "optimistic lock (ADR-020)"
    }

    COMENTARIO {
        long id PK
        long chamadoId FK
        long autorId FK
        string texto
        datetime criadoEm
    }
```

**Regras de integridade**

- Um chamado tem exatamente um solicitante e uma categoria; o atendente é opcional até ser assumido.
- Um chamado só pode ser assumido por usuário com perfil `ATENDENTE` ou `ADMIN`.
- Comentários são imutáveis após criação (sem edição/exclusão no MVP).
- Categoria e usuário nunca são excluídos — apenas desativados (ADR-009). Não há reativação no MVP.
- Um atendente com chamados em andamento não pode ser rebaixado a `SOLICITANTE` nem desativado, e o sistema mantém ao menos um administrador ativo (invariantes I3 e I11 de [`.ai/business-rules.md`](../.ai/business-rules.md)).

### Máquina de estados do chamado

```mermaid
stateDiagram-v2
    [*] --> ABERTO : solicitante abre
    ABERTO --> EM_ATENDIMENTO : atendente assume
    EM_ATENDIMENTO --> RESOLVIDO : atendente resolve
    RESOLVIDO --> FECHADO : solicitante confirma
    RESOLVIDO --> EM_ATENDIMENTO : solicitante reabre
    FECHADO --> [*]
```

---

## 6. Endpoints da API

Base: `/api`. Todas as respostas em JSON. No MVP, o usuário logado é identificado pelo header `X-User-Id` (autenticação simulada). Listagens aceitam `page`, `size` e `sort` e retornam o envelope padrão do Spring Data (`content`, `totalElements`, `totalPages`, `number`).

### Autenticação (simulada)

| Método | Rota | Descrição | Perfil |
|---|---|---|---|
| GET | `/auth/usuarios-disponiveis` | Lista usuários para "login" na tela inicial | Público |
| POST | `/auth/login` | Recebe `usuarioId`, retorna `id`, `nome` e `perfil` do usuário (sem e-mail); usuário inexistente ou inativo → 401 | Público |

### Chamados

| Método | Rota | Descrição | Perfil |
|---|---|---|---|
| GET | `/chamados` | Lista paginada com filtros `status`, `prioridade`, `categoriaId`, `meus=true` | Todos (solicitante vê só os seus, aplicado na query) |
| GET | `/chamados/{id}` | Detalhe do chamado com comentários | Todos (solicitante só os seus) |
| POST | `/chamados` | Abre chamado | Todos |
| PATCH | `/chamados/{id}/assumir` | Atendente assume o chamado | Atendente, Admin |
| PATCH | `/chamados/{id}/status` | Body `{ "status": "RESOLVIDO" }` — valida transição | Conforme tabela de permissões |
| GET | `/chamados/{id}/comentarios` | Lista comentários | Todos com acesso ao chamado |
| POST | `/chamados/{id}/comentarios` | Adiciona comentário | Todos com acesso ao chamado |

### Categorias

| Método | Rota | Descrição | Perfil |
|---|---|---|---|
| GET | `/categorias` | Lista categorias ativas. Com `incluirInativas=true`, lista também as desativadas | Todos (`incluirInativas=true`: só Admin) |
| POST | `/categorias` | Cria categoria | Admin |
| PUT | `/categorias/{id}` | Atualiza nome e SLA (não reativa: o campo `ativa` é ignorado) | Admin |
| DELETE | `/categorias/{id}` | Desativa categoria (soft delete) | Admin |

### Usuários

| Método | Rota | Descrição | Perfil |
|---|---|---|---|
| GET | `/usuarios` | Lista usuários | Admin |
| POST | `/usuarios` | Cria usuário | Admin |
| PUT | `/usuarios/{id}` | Atualiza usuário | Admin |
| DELETE | `/usuarios/{id}` | Desativa usuário (soft delete) | Admin |

### Dashboard

| Método | Rota | Descrição | Perfil |
|---|---|---|---|
| GET | `/dashboard/resumo` | Totais por status, por prioridade, fora do SLA, tempo médio de resolução | Atendente, Admin |

**Padrão de erros**

```json
{ "timestamp": "2026-09-21T22:10:00Z", "status": 422, "erro": "TRANSICAO_INVALIDA", "mensagem": "Chamado ABERTO não pode ir direto para RESOLVIDO" }
```

No 400 de validação vem também `campos` (`[{ "campo", "mensagem" }]`). Toda resposta, de sucesso ou erro, traz o header `X-Request-Id`.

| Status | `erro` | Quando |
|---|---|---|
| 200, 201, 204 | — | Sucesso |
| 400 | `VALIDACAO` | Bean Validation, JSON malformado, parâmetro de tipo errado, campo de ordenação inexistente |
| 401 | `NAO_AUTENTICADO` | `X-User-Id` ausente, inválido, de usuário inexistente ou inativo (filter e login) |
| 403 | `ACESSO_NEGADO` | Perfil ou relação com o chamado não permite a ação |
| 404 | `RECURSO_NAO_ENCONTRADO` | Recurso ou rota inexistente |
| 405 | `METODO_NAO_SUPORTADO` | Método HTTP não aceito pela rota |
| 409 | `CONFLITO` | Gravação concorrente (*optimistic lock*), trava de linha não obtida dentro do limite do banco ou violação de integridade |
| 415 | `MIDIA_NAO_SUPORTADA` | `Content-Type` diferente de JSON |
| 422 | `TRANSICAO_INVALIDA` | Transição de status inexistente ou não permitida para o perfil |
| 422 | `REGRA_NEGOCIO` | Demais regras: categoria inativa, nome ou e-mail repetido, último admin, atendente com chamados em andamento, autodesativação |
| 500 | `ERRO_INTERNO` | Falha inesperada; a mensagem pede o `X-Request-Id` |

---

## 7. Tecnologias sugeridas

| Camada | Genérico | Escolha para o MVP | Motivo |
|---|---|---|---|
| Frontend | Framework SPA + lib de componentes | Vue 3 + Vite + Quasar + Axios | Produtividade, componentes prontos (tabelas, forms, dialogs) |
| Backend | Framework web com ORM | Java 21 + Spring Boot 3 + Spring Data JPA + Bean Validation | Ecossistema maduro, stack conhecida pelo grupo |
| Banco | Banco relacional | H2 em memória (MVP) → PostgreSQL/SQL Server (produção) | Zero configuração local; seed via `data.sql` |
| Documentação da API | OpenAPI | springdoc-openapi (Swagger UI) | Facilita demonstração e testes no vídeo |
| Observabilidade | Health, métricas, logs correlacionados | Spring Boot Actuator + Micrometer + Logback (MDC) → Prometheus/Grafana na evolução | Zero código para health/métricas técnicas; métricas de negócio com poucas linhas |
| Testes backend | Framework de testes + mocks | JUnit 5, Mockito, AssertJ, Spring Test (`@WebMvcTest`, `@DataJpaTest`, `@SpringBootTest`), JaCoCo | Padrão do ecossistema, já vem no starter |
| Testes frontend | Runner de testes | Vitest + @vue/test-utils | Integrado ao Vite, roda rápido |
| Build | Gerenciador de dependências | Maven (backend), npm (frontend) | Padrão |
| Versionamento | Git | GitHub | Entregável da disciplina |

---

## 8. Fluxos principais

### 8.1 Ciclo de vida completo de um chamado

```mermaid
sequenceDiagram
    actor Sol as Solicitante
    actor Ate as Atendente
    participant FE as Frontend
    participant API as Backend
    participant DB as Banco

    Sol->>FE: Preenche formulário de chamado
    FE->>API: POST /api/chamados
    API->>DB: INSERT chamado (status = ABERTO)
    API-->>FE: 201 Created
    FE-->>Sol: Chamado #123 aberto

    Ate->>FE: Abre fila de chamados
    FE->>API: GET /api/chamados?status=ABERTO
    API-->>FE: Lista
    Ate->>FE: Clica em "Assumir"
    FE->>API: PATCH /api/chamados/123/assumir
    API->>API: Valida perfil e status atual
    API->>DB: UPDATE atendente, status = EM_ATENDIMENTO
    API-->>FE: 200 OK

    Ate->>FE: Comenta e marca como resolvido
    FE->>API: POST /api/chamados/123/comentarios
    FE->>API: PATCH /api/chamados/123/status {RESOLVIDO}
    API->>DB: UPDATE status, resolvidoEm
    API-->>FE: 200 OK

    Sol->>FE: Confirma que resolveu
    FE->>API: PATCH /api/chamados/123/status {FECHADO}
    API->>API: Valida que é o solicitante
    API->>DB: UPDATE status, fechadoEm
    API-->>FE: 200 OK
```

### 8.2 Login simulado (MVP)

1. Tela inicial lista usuários mockados (`GET /auth/usuarios-disponiveis`).
2. Usuário escolhe um, frontend chama `POST /auth/login` e guarda o retorno no `authStore` (Pinia), persistido no `localStorage`.
3. Toda requisição seguinte envia `X-User-Id`; o backend resolve perfil e aplica permissões.
4. Ao recarregar a página, o `boot/sessao.js` revalida o usuário salvo; se o backend responder 401 (usuário desativado, por exemplo), a sessão é encerrada e o usuário volta ao login.
5. As rotas `/auth/**` só existem com `helpdesk.auth.simulada=true` (perfis `dev` e `test`).

### 8.3 Cálculo de SLA (dashboard)

- Prazo = `criadoEm + categoria.slaHoras`.
- Chamado está **fora do SLA** se `status ∈ {ABERTO, EM_ATENDIMENTO}` e `agora > prazo`, ou se `resolvidoEm > prazo`.
- Tempo médio de resolução = média de `resolvidoEm − criadoEm` dos chamados resolvidos/fechados.

---

## 9. Simplificações e limitações do MVP

As decisões arquiteturais com alternativas descartadas estão na seção 4.7. Aqui ficam só os cortes de escopo feitos para caber na próxima aula.

| Simplificação | Justificativa | Evolução futura |
|---|---|---|
| Autenticação simulada por `X-User-Id` | Foco no domínio; evita gastar a aula com segurança | Spring Security + JWT |
| H2 em memória com seed | Roda com `mvn spring-boot:run`, sem instalar nada | PostgreSQL/SQL Server via Docker |
| Sem upload de anexos | Complexidade fora do escopo | Storage de arquivos (S3/Blob) |
| Sem notificações | Idem | E-mail / websocket |
| Soft delete em usuários e categorias, sem reativação | Preserva histórico dos chamados; reativar seria funcionalidade nova | Rotas `PATCH .../reativar` (decisão do grupo, plano de modernização §14) |
| Sem reatribuição de chamado | O MVP só impede o chamado órfão (I3) | Rota para o admin trocar o atendente |
| Gauges só atualizam na leitura do dashboard | Sem Prometheus configurado no MVP (ADR-018) | `Gauge` com função de consulta quando houver scrape |

---

## 10. Prompts utilizados para criar a arquitetura

**Ferramentas:** Claude (chat) para definição de escopo, entidades, endpoints e texto do documento; Mermaid (renderizado no GitHub) para os diagramas.

### Prompt 1 — definição do sistema

```
Preciso definir a arquitetura de um sistema simples para uma atividade acadêmica de
AI Driven Development. Requisitos da atividade: funcionalidades principais, tipos de
usuário e permissões, diagrama de arquitetura em camadas (frontend, backend, banco),
entidades e relacionamentos, endpoints REST, tecnologias sugeridas e fluxos principais.

O sistema será um HelpDesk interno (chamados de TI/Facilities). Deve ser simples o
suficiente para virar um MVP com dados mockados rodando localmente na próxima aula,
mas ter perfis distintos e um fluxo com estados para ficar interessante.

Stack de preferência: Java/Spring Boot no backend, Vue 3 no frontend, banco relacional.
Gere o documento completo em Markdown, com os diagramas em Mermaid.
```

### Prompt 2 — refinamento

```
Ajustes no documento:
- Adicione uma tabela de permissões cruzando ações x perfis (Solicitante, Atendente, Admin).
- Adicione um diagrama de estados (stateDiagram-v2) para o ciclo de vida do chamado,
  incluindo reabertura.
- Defina um padrão de resposta de erro e os códigos HTTP usados.
- Inclua uma seção de decisões do MVP explicando o que foi simplificado e por quê.
- Deixe explícito como o login simulado vai funcionar sem autenticação real.
```

### Prompt 3 — validação

```
Revise o documento procurando inconsistências: endpoints que não têm entidade
correspondente, permissões que contradizem os fluxos, campos do ER que não aparecem
em nenhum endpoint. Liste o que encontrar e corrija.
```

### Prompt 4 — aprofundamento da arquitetura

```
A seção de arquitetura ficou superficial: só um diagrama de três caixas. Reescreva-a
seguindo o modelo C4 (contexto, containers, componentes, código), com:
- diagrama de componentes do backend mostrando filter de autenticação, controllers,
  services, máquina de estados, serviço de permissão, repositories e exception handler;
- diagrama de componentes do frontend (router, layout, pages, stores, services);
- diagrama de sequência do caminho de uma requisição atravessando todas as camadas,
  incluindo onde cada tipo de erro (400/403/404/422) é gerado;
- tabela de ADRs com as alternativas consideradas e por que foram descartadas;
- estrutura de pacotes e pastas;
- visão de implantação (local vs produção);
- tabela de aspectos transversais (validação, erros, CORS, paginação, datas, logs,
  testes, performance).
Mantenha o restante do documento e ajuste referências cruzadas.
```

### Prompt 5 — observabilidade e testabilidade

```
Adicione à seção de arquitetura duas subseções para o documento ficar completo:
- Observabilidade: os três pilares (logs, métricas, tracing) no contexto do MVP, com
  correlação por request id, Actuator/Micrometer, métricas de negócio do domínio de
  chamados (nomes, tipo, tags), log de auditoria de transições e regras de privacidade
  nos logs. Deixe explícito o que é MVP e o que é evolução.
- Testabilidade: quais decisões de design tornam as regras testáveis sem subir o
  Spring (classes puras, Clock injetável, usuário por parâmetro), pirâmide de testes
  com os níveis usados, tabela de casos obrigatórios por nível e convenções.
Reflita isso nos componentes, na estrutura de código, nos ADRs e na tabela de
tecnologias.
```

---

## Navegação

- Entregável da Aula 2: `02_Prompt_Geracao_Contexto.md` e `03_Prompt_Implementacao.md`
