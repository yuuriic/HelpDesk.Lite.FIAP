# HelpDesk Lite — Baseline de testes de caracterização

| Item | Valor |
|---|---|
| Branch | `refactoring` |
| Commit de referência | `c5f4335` (código de produção **inalterado**; só foram adicionados testes) |
| Data | 30/09/2026 |
| Base | [01-auditoria-tecnica.md](01-auditoria-tecnica.md) |

---

## 1. Objetivo e regras adotadas

Criar uma rede de segurança **antes** de qualquer refatoração, sem corrigir defeitos nem alterar regras de negócio.

1. **Só foram adicionados testes.** Nenhum arquivo de `src/main` foi tocado, e nenhum teste existente foi alterado.
2. **Contrato ≠ defeito.** Os testes se dividem em dois grupos:
   - **Contrato:** comportamento correto que a refatoração tem de preservar. Se um desses quebrar, a refatoração está errada.
   - **Defeito conhecido:** comportamento atual *incorreto*, fixado como está hoje e identificado pelo ID da auditoria. Se um desses quebrar, alguém mudou o defeito, e isso só é aceitável se a mudança for a correção planejada.
3. **Nenhum defeito foi transformado em "comportamento esperado".** Todo teste de defeito:
   - tem `[DEFEITO CONHECIDO <ID>]` no nome exibido;
   - descreve, no Javadoc ou em comentário, **qual é o comportamento correto**;
   - no backend, leva `@Tag("defeito-conhecido")` e fica isolado em `DefeitosConhecidosIT`.
4. **Protocolo de correção.** Ao corrigir um achado, o teste de defeito correspondente vai falhar, e isso é esperado. Nesse momento:
   1. apagar o teste de defeito;
   2. escrever, na suíte de contrato, o teste com a asserção correta;
   3. registrar o ID do achado no commit.

   Nunca ajustar a asserção ao novo comportamento sem saber por que ele mudou.

### Comandos

```bash
# backend: suíte completa + gate JaCoCo
cd backend && ./mvnw verify

# backend: só os defeitos conhecidos
./mvnw test -Dgroups=defeito-conhecido

# backend: tudo, menos os defeitos conhecidos
./mvnw test -DexcludedGroups=defeito-conhecido

# frontend
cd frontend && npm test

# frontend: localizar os defeitos conhecidos
grep -rn "DEFEITO CONHECIDO" src/__tests__
```

---

## 2. Novo baseline

| Suíte | Antes | Depois | Adicionados | Resultado |
|---|:---:|:---:|:---:|---|
| Backend (JUnit) | 151 | **202** | +51 | 202 ✓, 0 falhas, 0 ignorados |
| Frontend (Vitest) | 49 | **71** | +22 | 71 ✓, 0 falhas |
| **Total** | **200** | **273** | **+73** | |

Dos 73 testes novos, **21 fixam defeitos conhecidos** (17 no backend e 4 no frontend) e **52 protegem contrato**.

### Cobertura de linhas no backend (JaCoCo)

| Pacote | Antes | Depois |
|---|:---:|:---:|
| **Total** | 92,1% | **95,4%** |
| domain + service (gate ≥ 80%) | 95,0% | 95,0% |
| `web.controller` | **50,0%** | **100%** |
| `web.exception` | 80,6% | **100%** |
| `web.filter` | 92,3% | 92,3% |

A cobertura de branches do backend, medida pela primeira vez nesta etapa, é de **67,1%**. O frontend não tem medição de cobertura configurada (TST-03).

---

## 3. Testes adicionados

### Backend

| Arquivo | Testes | Tipo | O que protege |
|---|:---:|---|---|
| [CadastrosApiIT.java](../../backend/src/test/java/br/com/helpdesk/integration/CadastrosApiIT.java) | 22 | Contrato | Contrato HTTP de **categorias, usuários, dashboard e login simulado**, que antes não tinham teste na camada web: status 200/201/204/400/403/404/422, códigos de erro, `incluirInativas` só para o admin, soft delete, normalização, I10 e as chaves do JSON do dashboard |
| [ChamadoContratoApiIT.java](../../backend/src/test/java/br/com/helpdesk/integration/ChamadoContratoApiIT.java) | 12 | Contrato | **Formato exato do JSON** (detalhe, aninhados, listagem, envelope de paginação, omissão de nulos, ISO-8601, `Location`); **atribuição de atendente**; **linha de auditoria** da §4.11 |
| [DefeitosConhecidosIT.java](../../backend/src/test/java/br/com/helpdesk/integration/DefeitosConhecidosIT.java) | 17 | **Defeito** | EXC-01, REG-01, REG-02, REG-03, REG-04, SEG-03, SEG-04, SEG-06, CON-01, CON-03, OBS-01 (detalhe na seção 5) |

### Frontend

| Arquivo | Testes | Tipo | O que protege |
|---|:---:|---|---|
| [api.spec.js](../../frontend/src/__tests__/api.spec.js) | 11 | Contrato + 1 defeito | O interceptor do cliente HTTP, que antes não tinha teste: `X-Request-Id` único por requisição, `X-User-Id` só com sessão, URL padrão, tradução do erro em notify (mensagem, campos do 400, tipo por status, erro de rede, request id), notify impossível de silenciar. Defeito fixado: **EXC-03** |
| [catalogoStore.spec.js](../../frontend/src/__tests__/catalogoStore.spec.js) | 9 | Contrato + 1 defeito | Rotas e recarga após cada escrita de categorias e usuários. Defeito fixado: **FE-04** |
| [ChamadoAcoesProcessando.spec.js](../../frontend/src/__tests__/ChamadoAcoesProcessando.spec.js) | 2 | **Defeito** | **FE-02**: o botão não trava e o clique duplo emite duas vezes |

---

## 4. Matriz: área crítica × proteção

Legenda:
- **E** = já protegida por teste existente;
- **N** = protegida por teste novo;
- **D** = defeito fixado como está hoje;
- **—** = sem teste automatizado (justificativa na seção 6).

### 4.1 Permissões e perfis

| Comportamento | Proteção | Onde |
|---|:---:|---|
| Cada linha da tabela de permissões (§3), incluindo os casos "só os seus" | E | `PermissaoServiceTest` (21 testes) |
| 403 **antes** de 422 (permissão antes da transição) | E | `ChamadoServiceTest.AlterarStatus`, `CicloDeVidaChamadoIT` |
| Solicitante não enxerga chamado alheio em nenhum endpoint, nem no `count` | E | `ChamadoSpecificationTest`, `CicloDeVidaChamadoIT` |
| Cadastros exclusivos do admin (HTTP) | N | `CadastrosApiIT` (categorias, usuários, `incluirInativas`) |
| Dashboard para atendente e admin; 403 para solicitante (HTTP) | N | `CadastrosApiIT.Dashboard` |
| Atendente não responsável vê e comenta, mas não resolve | N | `ChamadoContratoApiIT.Atribuicao` |
| Botões do frontend conforme perfil × status × relação | E | `ChamadoAcoes.spec.js` (18 testes). Protege a refatoração do FE-01 |
| O admin pode se rebaixar e deixar o sistema sem admin | **D** | REG-01 |

### 4.2 Transições de status

| Comportamento | Proteção | Onde |
|---|:---:|---|
| Exatamente 8 combinações válidas de aresta × perfil; todo o resto é inválido | E | `TransicaoStatusTest` |
| `FECHADO` é final; reabertura só a partir de `RESOLVIDO`, pelo solicitante ou admin (ADR-016) | E | `TransicaoStatusTest` |
| Carimbos (`resolvidoEm`, `fechadoEm`, reabertura zera os dois) vindos do `Clock` | E | `ChamadoServiceTest` |
| Ciclo completo e reabertura pela API | E | `CicloDeVidaChamadoIT` |
| Após reabertura, o chamado é resolvido de novo e `resolvidoEm` é regravado | N | `ChamadoContratoApiIT.Atribuicao` |

### 4.3 Atribuição de atendentes

| Comportamento | Proteção | Onde |
|---|:---:|---|
| Assumir define o atendente e muda para `EM_ATENDIMENTO` | E | `ChamadoServiceTest.Assumir`, `CicloDeVidaChamadoIT` |
| O admin também assume e vira o atendente | N | `ChamadoContratoApiIT` |
| Chamado assumido não troca de dono (outro atendente: 422) | N | `ChamadoContratoApiIT` |
| Reaberto, volta para o mesmo atendente; outro recebe 403/422 | N | `ChamadoContratoApiIT` |
| `meus=true` é a fila de quem atende | E + N | `ChamadoSpecificationTest`; `ChamadoContratoApiIT` (HTTP) |
| Atendente rebaixado ou desativado deixa o chamado órfão | **D** | REG-02 (2 testes) |
| Duas assunções simultâneas: vale a última, sem erro | **D** | CON-01 |

### 4.4 Tratamento de exceções

| Comportamento | Proteção | Onde |
|---|:---:|---|
| 400/401/403/404/422 no `ChamadoController`, com o JSON padrão | E | `ChamadoControllerTest` |
| Os mesmos códigos nas rotas de cadastro, dashboard e login | N | `CadastrosApiIT` |
| Rota inexistente, método errado, `Content-Type` errado e `sort` inválido → 500 com log ERROR | **D** | EXC-01 (5 testes) |
| Login devolve 422/404 onde o filter devolve 401 | **D** | REG-04 (2 testes) |
| Tradução de erro em notify no frontend (mensagem, campos, tipo, rede) | N | `api.spec.js` |
| 401 no frontend não encerra a sessão | **D** | EXC-03 |

### 4.5 Regras de domínio

| Comportamento | Proteção | Onde |
|---|:---:|---|
| SLA: prazo, fora do SLA, atraso, tempo médio | E | `SlaCalculatorTest` (18 testes) |
| `prazoSla` = `criadoEm` + `slaHoras` na resposta HTTP | N | `ChamadoContratoApiIT` |
| Abrir chamado só em categoria ativa (I8) | E | `ChamadoServiceTest.Abrir` |
| Unicidade de nome e e-mail sem diferenciar maiúsculas (I7) | E + N | Testes de service; `CadastrosApiIT` (HTTP) |
| Soft delete de usuário e categoria (I5/I6); usuário desativado recebe 401 | E + N | Testes de service; `CadastrosApiIT` |
| Categoria desativada não pode ser reativada | **D** | REG-03 |

### 4.6 Contrato consumido pelo frontend (afetado por ARQ-01, ARQ-04 e DOM-01)

| Comportamento | Proteção | Onde |
|---|:---:|---|
| Campos exatos do detalhe, da listagem e dos objetos aninhados; usuário embutido **sem e-mail** | N | `ChamadoContratoApiIT.FormatoDoJson` |
| Campos nulos omitidos (`non_null`) | N | `ChamadoContratoApiIT` |
| Envelope de paginação (`content`, `totalElements`, `totalPages`, `number`, `size`) | N | `ChamadoContratoApiIT` |
| Ordem padrão `criadoEm desc`, 20 por página | N | `ChamadoContratoApiIT` |
| Stores do frontend: rotas e recarga | E + N | `chamadosStore.spec.js`; `catalogoStore.spec.js` |

### 4.7 Observabilidade, segurança e desempenho

| Comportamento | Proteção | Onde |
|---|:---:|---|
| `X-Request-Id` gerado ou propagado | E | `ChamadoControllerTest` |
| Linha de auditoria com `acao`, `chamadoId`, `perfil`, `statusDe`, `statusPara` e `usr` | N | `ChamadoContratoApiIT.Auditoria` |
| Métricas de negócio (counters, timer, gauges pré-registrados) | E | `MetricasNegocioTest`, `DashboardServiceTest` |
| Gauges só mudam na leitura do dashboard | **D** | OBS-01 |
| Rota pública expõe e-mail | **D** | SEG-03 |
| E-mail no log WARN | **D** | SEG-04 |
| Mensagem do 401 revela se o usuário existe | **D** | SEG-06 |
| N+1 na listagem: 8 consultas para 3 chamados | **D** | CON-03 |

---

## 5. Defeitos conhecidos fixados (21)

| ID | Teste | Comportamento atual fixado | Comportamento correto |
|---|---|---|---|
| EXC-01 | `DefeitosConhecidosIT.Exc01` (5) | Rota inexistente, `DELETE` não mapeado, `text/plain` e `sort` inválido → **500 `ERRO_INTERNO`**; log em ERROR | 404, 405, 415, 400; log WARN |
| REG-01 | `unicoAdminPodeRebaixarASiMesmo` | O único admin se rebaixa (200); depois disso, 403 para todos em `/api/usuarios` | 422: não rebaixar o último admin ativo |
| REG-02 | `Reg02` (2) | Atendente com chamado `EM_ATENDIMENTO` rebaixado (200) ou desativado (204). O chamado mantém esse atendente; ex-atendente leva 403; outro atendente leva 422 ao assumir; só o admin resolve | Bloquear, ou devolver para a fila; caso de uso de reatribuição |
| REG-03 | `categoriaDesativadaNaoPodeSerReativada` | `PUT` com `"ativa":true` → 200 e `ativa` continua `false` | Rota de reativação |
| REG-04 | `Reg04` (2) | Login de inativo → 422 `REGRA_NEGOCIO`; inexistente → 404 | 401 `NAO_AUTENTICADO` nos dois |
| SEG-06 | `mensagemDo401RevelaExistencia` | O 401 diz "Usuario 999999 nao encontrado" | Mensagem genérica |
| SEG-03 | `rotaPublicaExpoeEmail` | `GET /api/auth/usuarios-disponiveis` traz o e-mail | Resumo sem e-mail |
| SEG-04 | `emailApareceNoLog` | "Ja existe um usuario com o e-mail ana@…" em log WARN | Log sem dado pessoal |
| CON-01 | `ultimaEscritaVenceSemLockOtimista` | Duas cópias lidas em `ABERTO` são gravadas uma sobre a outra, **sem erro**; a assunção do Bruno se perde | Conflito de versão (409) |
| CON-03 | `listagemFazNMaisUm` | **8** consultas para listar 3 chamados (1 usuário + 1 página + 3 categorias + 3 solicitantes) | 2 consultas |
| OBS-01 | `gaugeSoAtualizaNaLeituraDoDashboard` | Abrir chamados não mexe no gauge; só `GET /dashboard/resumo` o atualiza | Gauge reflete o banco |
| EXC-03 | `api.spec.js` | Após 401, o `X-User-Id` continua sendo enviado | 401 → logout e `/login` |
| FE-04 | `catalogoStore.spec.js` | Depois de salvar uma categoria, a lista usada no formulário de chamado passa a conter inativas | Listas separadas |
| FE-02 | `ChamadoAcoesProcessando.spec.js` (2) | Botão não desabilita durante a ação; clique duplo emite duas vezes | Travado até o pai terminar |

**Como sabemos que os testes de defeito detectam a correção:**
- **CON-01:** com `@Version`, o `merge` da cópia desatualizada lança exceção.
- **CON-03:** com `@EntityGraph`, a contagem cai para 2.
- **FE-02:** um `q-btn` desabilitado expõe a classe `disabled` e `aria-disabled="true"`, o que foi verificado com um spec descartável.
- **Demais:** asserções diretas de status HTTP ou de conteúdo.

### Testes existentes que já fixavam defeitos sem rótulo

Eles não foram alterados, conforme a regra 1, e também vão precisar mudar quando o achado for corrigido:

| Teste existente | Achado | O que fixa hoje |
|---|---|---|
| `ChamadoControllerTest.deveDevolver401QuandoOUsuarioEstaInativo` | SEG-06 | A mensagem do 401 contém "inativo" |
| `UsuarioServiceTest.deveRecusarLoginDeUsuarioInativo` / `...Inexistente` | REG-04 | `RegraNegocioException` e `RecursoNaoEncontradoException` no login |
| `ChamadoControllerTest.devePropagarORequestIdDoCliente` | SEG-05 | Reuso do id do cliente. **Continua válido** após a correção, que só deve rejeitar ids malformados |
| `DashboardServiceTest.deveAtualizarOsGaugesACadaLeitura` | OBS-01 | Os gauges são republicados na leitura (ADR-018) |

---

## 6. O que ficou sem teste automatizado, e por quê

| Achado | Motivo |
|---|---|
| SEG-01 (personificação por `X-User-Id`) | É o mecanismo da ADR-003, exercitado implicitamente por todos os testes de integração. A correção é trocar o mecanismo, não um comportamento pontual |
| SEG-02 (perfil padrão `dev`) | É configuração de empacotamento; a evidência está na auditoria (`curl` no jar). Um reflexo visível: o `@WebMvcTest` sobe no perfil `dev` (TST-02) |
| SEG-05, SEG-07 | O reuso do id já é fixado por teste existente; o `sort` inválido está coberto pelo EXC-01 |
| CON-02 (unicidade *check-then-act*) | Não é reproduzível de forma determinística sem concorrência real. A correção (mapear `DataIntegrityViolationException`) terá teste próprio |
| CON-04 (dashboard carrega tudo em memória) | Achado de desempenho. O resultado funcional do dashboard está protegido por `DashboardServiceTest` e `CadastrosApiIT` |
| OBS-02, OBS-03 | Baixa severidade. As linhas de auditoria do chamado estão fixadas em `ChamadoContratoApiIT` |
| DOM-01…06, ARQ-01…04 | Achados estruturais, sem comportamento observável próprio. São protegidos indiretamente pela matriz acima: qualquer mudança de comportamento durante a refatoração quebra um teste de contrato |
| FE-01, FE-03, FE-05…07 | A matriz de botões já é testada (18 testes); os demais são duplicação e cosmética |

---

## 7. Achado novo durante a caracterização

| ID | Sev. | Descrição |
|---|---|---|
| **REG-07** | Baixa | Um e-mail com espaços nas pontas é **reprovado com 400** pelo `@Email` do `UsuarioRequest` antes de chegar ao service. O `trim()` do e-mail em [UsuarioService.java:59,81](../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java#L59) é, na prática, inalcançável pela API; só o nome é aparado de fato. Fixado como contrato atual em `CadastrosApiIT.deveReprovarEmailComEspacosAntesDoTrim`, para que a mudança da normalização (REG-06) seja deliberada |
