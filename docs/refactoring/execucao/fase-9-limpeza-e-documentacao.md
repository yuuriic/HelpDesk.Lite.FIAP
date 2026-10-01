# Fase 9 — Limpeza técnica e consolidação da documentação

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §13](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `refactor` + `docs`: **sem mudança de comportamento nem de contrato REST**. A única correção de defeito é documental (REG-03) |
| Tratados | **REG-03** e **DOC-01…10** (necessários); **DOM-05**, **REG-06/REG-07** (opcionais da fase); **FE-03** e **FE-05** (opcionais adiados pela Fase 7 para esta); a exposição do `UsuarioAtual` no OpenAPI (achado da Fase 6); o script de migração do `versao` (risco da §15 do plano) |
| Desvio do plano | As ADRs **não** foram copiadas para uma pasta `docs/adr/` (§6) |
| Testes `[DEFEITO CONHECIDO]` restantes | **0** em ambas as suítes |

---

## 1. Código morto removido (DOM-05, FE-05)

Cada símbolo foi conferido sem nenhuma referência no código de produção antes de ser removido.

| Símbolo | Onde | Situação na auditoria | Agora |
|---|---|---|---|
| `Chamado.pertenceA(Usuario)` | `domain/model/Chamado.java` | Duplicava `foiSolicitadoPor`; sem uso nem em teste | Removido |
| `StatusChamado.concluido()` | `domain/enums/StatusChamado.java` | Sem uso nem em teste | Removido |
| `UsuarioRepository.findByEmailIgnoreCase` | `repository/UsuarioRepository.java` | Sem uso | Removido (e o import de `Optional`) |
| `TransicaoStatus.proximosStatus` | `domain/state/TransicaoStatus.java` | Só em testes | Removido, com os 2 testes que o exercitavam |
| `TransicaoInvalidaException.getDe/getPara` | `domain/exception/TransicaoInvalidaException.java` | Nunca lidos pelo handler | Removidos. A Fase 1 **não** os incluiu no corpo do 422, então a ressalva do plano não se aplica. O construtor ficou só com a mensagem. Saiu 1 teste (`deveExporOsStatusDaTransicaoNaExcecao`) |
| `UsuarioAtual.id()/perfil()/nome()` | `web/filter/UsuarioAtual.java` | Só em testes | Removidos. O record ficou com `usuario()` e a constante do atributo |
| `this.$api` | `frontend/src/boot/axios.js` | Nenhum componente usa a Options API | Arquivo removido e `'axios'` retirado da lista `boot` do `quasar.config.js` |
| `DefeitosConhecidosIT` | `backend/src/test/.../integration/` | Arquivo dos testes de defeito da baseline | Removido ao tratar o último defeito (REG-03). Os seis Javadocs que apontavam para ele com `{@link}` passaram a citá-lo em texto |

**Mantido de propósito:** `TransicaoStatus.permitida`. Também só é usado em teste, mas é o
oráculo dos testes exaustivos da máquina de estados (as 48 combinações de `TransicaoStatusTest`).
Sem ele, o teste teria de capturar exceção em cada combinação. `Usuario.isAdmin/isAtendente/
isSolicitante`, também listados no DOM-05, já tinham saído na Fase 5 (DOM-03).

## 2. Trechos simplificados

| ID | Antes | Depois | Justificativa no plano |
|---|---|---|---|
| REG-06 | `CategoriaService.criar` e `atualizar` repetiam *trim* + verificação de nome único; `UsuarioService`, o mesmo para o e-mail | `nomeDisponivel(nome, idIgnorado)` e `emailDisponivel(email, idIgnorado)`: um método privado por regra, usado na criação (`null`) e na atualização (id) | §13, *Extract Method* |
| REG-07 | `trim()` do e-mail inalcançável pela API (a Bean Validation reprova antes) | **Mantido**, documentado como defensivo: o service também é chamado sem a validação web (testes unitários, futuros chamadores). `CadastrosApiIT.deveReprovarEmailComEspacosAntesDoTrim` passou **sem edição** | §13 manda decidir; a decisão que não muda comportamento |
| FE-03 | Rótulos, cores e ícones de perfil, status e prioridade copiados em 8 arquivos; `formatar` de data em 4. O ADMIN aparecia como "Admin" no login e na lista de usuários, e como "Administrador" no cabeçalho e no formulário | `src/constants/dominio.js` (`PERFIS`, `STATUS`, `PRIORIDADES`, `opcoesDe`) e `src/utils/formatacao.js` (`formatarDataHora`). "Administrador" em todo lugar | §11, *Extract Module*; adiado pela Fase 7 |
| FE-05 | *Fallback* `http://localhost:8080/api` repetido no `quasar.config.js` e no `api.js`, e o comentário "Único lugar que define a URL" no primeiro | O padrão fica só no `api.js`; o `quasar.config.js` repassa `API_BASE_URL` apenas quando definida | §11, *Remove Dead Code*; adiado pela Fase 7 |

Arquivos tocados pelo FE-03: `StatusBadge`, `PrioridadeBadge`, `ComentarioTimeline`,
`ChamadoFiltros`, `MainLayout`, `LoginPage`, `UsuariosPage`, `NovoChamadoPage`, `ChamadosPage`,
`ChamadoDetalhePage` e `DashboardPage`. Valores, cores e *fallbacks* são os que cada arquivo
já usava. A única diferença visível é o rótulo "Administrador".

**FE-05 verificado no build:** sem `API_BASE_URL`, o bundle contém
`{}.API_BASE_URL||'http://localhost:8080/api'`. Com `API_BASE_URL=https://exemplo.test/api`,
contém a URL informada. O teste `api.spec.js` "usa a URL padrão" passou sem edição.

## 3. REG-03 — comentário que prometia reativação

- **Achado.** O Javadoc de `CategoriaService.listarTodas` dizia que a tela de administração
  lista as inativas "para reativá-las". Não existe reativação: o `PUT` não muda `ativa`, e não há
  rota dedicada.
- **Correção.** O Javadoc diz o que o código faz: sem reativação no MVP, o `PUT` atualiza só
  nome e SLA, e reativar é funcionalidade nova (plano, §14). **Nenhuma funcionalidade foi criada.**
- **Teste.** O teste de defeito de `DefeitosConhecidosIT` foi substituído por um teste de
  contrato, `CadastrosApiIT.Categorias.naoDeveReativarCategoriaPeloPut`: um `PUT` com
  `"ativa": true` numa categoria desativada responde 200, atualiza o SLA e mantém `ativa = false`.
- A mesma regra foi registrada em `.ai/business-rules.md` (§4), `docs/ARQUITETURA.md` (§5, §6,
  §9) e `docs/MVP.md` (§8, §10).

## 4. OpenAPI — `UsuarioAtual` documentado como parâmetro

- **Achado (Fase 6, §5).** O springdoc documentava o `UsuarioAtual` dos controllers como um
  parâmetro de *query* com a entidade `Usuario` inteira como schema, em 16 operações. Esse
  parâmetro não existe: quem o preenche é o filtro, a partir do header. O header `X-User-Id`, que
  o cliente envia de fato, não aparecia.
- **Correção, só na documentação.** O `OpenApiConfig`:
  - registra `UsuarioAtual` em `SpringDocUtils.addRequestWrapperToIgnore`;
  - declara o esquema de segurança `loginSimulado` (*apiKey* no header `X-User-Id`) como
    requisito global, que é o que o botão *Authorize* do Swagger UI preenche.

  O `AuthController` leva `@SecurityRequirements` vazio: as rotas de login aparecem como
  públicas, como de fato são.
- **Contrato REST inalterado.** Nenhuma rota, status, header ou corpo mudou. O novo
  `DocumentacaoOpenApiIT` (3 testes) verifica três coisas:
  - nenhuma operação tem o parâmetro `usuarioAtual`, e `Usuario`/`UsuarioAtual` não são schema;
  - o esquema `loginSimulado` existe, é exigido globalmente e não vale para `/api/auth/**`;
  - os 12 *paths*, os schemas de entrada e saída e os parâmetros reais da listagem continuam lá.
- **Prova de que o teste detecta a regressão:** com a linha `addRequestWrapperToIgnore`
  comentada, o primeiro teste falha. Depois a linha foi restaurada.

## 5. Divergências entre documentação e código corrigidas

### 5.1 As registradas na auditoria (DOC-01…10)

| ID | Divergência | Correção |
|---|---|---|
| DOC-01 | §4.1 dizia "dependência unidirecional" com o `service` importando `web.dto` | O código foi corrigido na Fase 6. A §4.1 agora descreve `service.dto`, o `config` como raiz de composição e o `DependenciaEntreCamadasTest` |
| DOC-02 | ADRs 014–018 só em `.ai/architecture.md`; duas fontes de verdade | §4.7 do `ARQUITETURA.md` passou a ser o **índice completo** (001–024). O texto integral fica só em `.ai/architecture.md` §2, declarado fonte única. ADRs novas: 019–024 (§6) |
| DOC-03 | §4.8 citava `MetricasConfig` (inexistente) e omitia `DominioConfig`/`WebMvcConfig`; §4.9 e ADR-008 citavam um `application-prod.yml` inexistente | Árvore de pacotes refeita a partir do código (backend, testes e frontend). §4.9 e ADR-008 (nos dois documentos) descrevem o `application.yml` único com perfis |
| DOC-04 | §4.10, ADR-014 e `CorsConfig`: CORS "apenas no perfil dev" e "em produção o bean deixa de ter efeito" | O bean vale em todos os perfis. Em produção a regra só não é usada, porque a origem é a mesma. Corrigido no Javadoc do `CorsConfig`, na §4.10 e no título e consequências da ADR-014 |
| DOC-05 | §4.10: `@EntityGraph` "nas consultas", com a listagem tendo N+1 | O código foi corrigido na Fase 4. A §4.10 lista onde está o `@EntityGraph` e cita o `ConsultasPorRequisicaoIT` |
| DOC-06 | §4.11: `fora_sla` "sobre abertos/em atendimento"; métricas "registradas no `ChamadoService`"; `api.js` "loga erros com o id"; timer `_segundos` | Tabela de métricas refeita: semântica do `fora_sla` (inclui resolvidos tarde), quem emite cada métrica, emissão depois do commit, nome real `helpdesk_tempo_resolucao_seconds`. O diagrama diz que o `api.js` **mostra** o id no `q-notify`. Mesma correção em `business-rules.md` e `MVP.md` |
| DOC-07 | "4xx em WARN sem stack trace" e "nenhum dado pessoal em log" eram falsos | Os dois passaram a ser verdade no código (Fases 1 e 3). A §4.11 ganhou a regra das rotas públicas sem e-mail e a exposição real do Actuator por perfil |
| DOC-08 | §6: `GET /categorias` "lista ativas"; códigos sem 401, 405, 409, 415 | §6 documenta `incluirInativas` (só admin), o `PUT` sem reativação, o login que devolve só `id`/`nome`/`perfil` com 401, o campo `campos` do 400 e a **tabela completa** de status × `erro` |
| DOC-09 | I3 e I9 de `business-rules.md` violáveis no código | O código foi corrigido nas Fases 1 e 2, e o texto de I3/I9 atualizado na Fase 2. Nesta fase só se conferiu (I3: 422 no cadastro; I9: 401 no login e no filter) |
| DOC-10 | §4.4: `ChamadoService → SlaCalculator` | Diagrama de componentes refeito: `PermissaoService` e `SlaCalculator` no domínio; o SLA é usado pelo `ChamadoMapper` e pelo `DashboardService`; o `ChamadoService` chama o agregado, que valida a aresta; `AposCommit` |

### 5.2 Encontradas durante a revisão

| Documento | Divergência | Correção |
|---|---|---|
| `ARQUITETURA.md` §4.5 | Sem `dashboardStore`, `boot/sessao.js`, aviso de sessão perdida, constantes; `catalogoStore` com uma lista só | Diagrama e regras do frontend atualizados |
| `ARQUITETURA.md` §4.6 | `chamado.setStatus` (não existe mais setter); sem 409 | Sequência mostra `chamado.alterarStatus` validando a aresta, o `UPDATE ... AND versao = ?` e a emissão depois do commit |
| `ARQUITETURA.md` §4.12 e `.ai/architecture.md` §6 | Pirâmide com "1 teste de integração"; classes puras sem o `Chamado` | Pirâmide e casos obrigatórios descrevem as suítes atuais e o teste de arquitetura |
| `ARQUITETURA.md` §5 | ER sem `versao`; regra "não pode ser excluída se houver chamados" (nunca há exclusão) | `versao` no diagrama; regras de integridade com soft delete definitivo e as invariantes I3/I11 |
| `ARQUITETURA.md` §8.2 | Sessão "em memória" | Persistida no `localStorage`, revalidada pelo `boot/sessao.js`; `/auth/**` só com o login simulado ligado |
| `ARQUITETURA.md` §9 | Sem os cortes que a modernização deixou registrados | Sem reativação, sem reatribuição, gauges só na leitura |
| `.ai/architecture.md` §3/§4 | `PermissaoService`/`SlaCalculator` sem o pacote; teste de ordem com um só nome | Pacotes `domain.*`, os dois testes de ordem e o IT pela API |
| `.ai/standards.md` §1.3/§1.4/§4/§6 | `boot/axios.js`; árvore de testes com um IT; três stores | Árvores refeitas; regra das constantes do frontend; quatro stores; emissão depois do commit |
| `.ai/tech-stack.md` §7 | "Em `prod` só `health` e `prometheus`" sem citar a configuração base | Base e `prod` fechados; `spring-boot:run` ativa o `dev` |
| `MVP.md` §7 | "No Swagger, o `X-User-Id` aparece como um campo no formulário": não aparecia (era o `usuarioAtual` falso de query) | Instrução do botão *Authorize* |
| `MVP.md` §1 e §9 | "18 ADRs"; 151 e 49 testes; `ARQUITETURA.md` "é o mesmo documento" da Atividade 1 | 24 ADRs; 291 e 86 testes com as classes atuais; a nota diz que `docs/` é mantido coerente com o código e `Atividade 1/` preserva o entregue |
| `MVP.md` §3 | Sem aviso de que `java -jar` sobe fechado | Parágrafo sobre `dev` × sem perfil |
| `README.md` | Sem `docs/refactoring/` | Árvore e lista de documentação de apoio |

`Atividade 1/` e `Atividade 2/` **não** foram tocados: são os entregáveis das aulas, como
foram entregues.

## 6. ADRs novas e desvio do plano

O plano pedia "consolidar as ADRs em `docs/adr/`". O problema registrado (DOC-02) é haver
**duas** fontes de verdade. Copiar as 24 ADRs para uma pasta nova criaria a terceira. Por isso:

- o texto integral fica em `.ai/architecture.md` §2, agora declarado fonte única;
- `docs/ARQUITETURA.md` §4.7 tem o índice das 24, com link para o texto.

| ADR | Decisão | Fase |
|---|---|---|
| 019 | Erros do Spring MVC no handler central; 409 `CONFLITO`; 401 no login | 1 |
| 020 | *Optimistic locking* no `Chamado`; guardas das invariantes I3/I11 | 2 |
| 021 | Configuração base fechada; `dev` explícito; `prod` recusa o login simulado | 3 |
| 022 | `Chamado` como agregado; permissão e SLA no `domain` | 5 |
| 023 | Contratos do service em `service.dto`; dependência verificada por teste | 6 |
| 024 | Métricas e auditoria depois do commit | 8 |

O plano citava também "perfil padrão" (coberto pela 021) e "guardas de REG-01/02" (cobertas
pela 020). A ADR-018 já tinha recebido o parágrafo de revisão na Fase 8.

**Script de migração do `versao`** (risco da §15 do plano), registrado em `ARQUITETURA.md`
§4.9 e na ADR-020:

```sql
ALTER TABLE chamado ADD COLUMN versao BIGINT DEFAULT 0 NOT NULL;
```

## 7. O que continua aberto (fora desta fase)

São opcionais de outras fases, que os respectivos relatórios justificaram não tratar. Esta
fase não os retomou, porque a limpeza só podia tocar o que tem justificativa registrada para ela.

| ID | Achado | Onde foi decidido |
|---|---|---|
| EXC-02 | Hierarquia comum de exceções | Fase 6, quadro de abertura |
| EXC-04 | `ErroResponse` com `Instant.now()` em vez de `Clock` | Fase 1, §2 |
| DOM-06 | Extrair `ComentarioService` | Fase 5, §2.7 |
| CON-04 | Dashboard carrega a base inteira | Fase 4, §4 |
| SEG-05, SEG-07 | Opcionais de segurança | Fase 3, §5 |
| FE-06, FE-07, TST-03 | Formatos na lista; *toggle* do solicitante; cobertura do frontend | Fase 7, §4 |
| OBS-03 | Formato único de auditoria | Fase 8, §4 |

As decisões que exigiriam funcionalidade nova continuam na §14 do plano: reativação,
reatribuição, autenticação real e `acoesPermitidas` na API.

## 8. Validação final

Os relatórios do Surefire/Failsafe foram apagados antes de cada execução, para a contagem não
herdar arquivos antigos.

| Verificação | Antes (Fase 8) | Depois (Fase 9) |
|---|---|---|
| Backend (`./mvnw verify`) | 291 ✓ | **291 ✓**, 0 falhas, 0 erros, 0 ignorados; gate do JaCoCo aprovado. Saldo: −2 `proximosStatus`, −1 `getDe/getPara`, −1 defeito REG-03, +1 contrato REG-03, +3 OpenAPI |
| Frontend (`npx vitest run`) | 86 ✓ | **86 ✓** (9 arquivos), sem nenhuma edição de teste no FE-03/FE-05 |
| `npx quasar build` | ✓ | **✓** |
| Cobertura, relatório JaCoCo (total) | 97,0% linhas · 76,1% *branches* | **97,9% linhas · 79,7% *branches*** (661/675 linhas; 149/187 *branches*). Parte do ganho vem só de remover código morto, que tira linhas e *branches* do denominador |
| Cobertura de `domain` + `service` (escopo do gate de 80%) | — | **97,7% linhas · 74,3% *branches*** |
| Complexidade ciclomática (lizard 1.24.0, limite 10) | Nenhuma função acima de 10 | **Nenhuma**: 283 funções (Java, JS e Vue, sem testes), máximo 10 (`services/chamados.js listar`, igual à `main`), `ChamadoAcoes` com máximo 3. *Correção:* a execução registrada originalmente nesta fase não rodou (o lizard não estava no `PATH` e o erro foi suprimido); a medição foi refeita na auditoria final ([05-auditoria-final.md](../05-auditoria-final.md) §2) |
| `./mvnw verify -Dgroups=defeito-conhecido` | 1 teste | **0 testes executados** |
| `@Tag("defeito-conhecido")` ou `[DEFEITO CONHECIDO]` em nome de teste (backend + frontend) | 1 | **0** |
| Contrato REST | — | Inalterado: nenhum teste de contrato editado; `DocumentacaoOpenApiIT` confere *paths* e schemas |

As menções restantes a `[DEFEITO CONHECIDO]` são comentários de cabeçalho que explicam qual
teste de defeito cada teste de contrato substituiu: 3 no backend (`CadastrosApiIT` e
`ObservabilidadeIT`) e 4 no frontend.

## 9. Estado do repositório

- Branch `refactoring`; `main` em `c5f4335`, sem alteração.
- **Nenhum commit** feito. No índice estão só o que operações do `git` registraram: as
  renomeações (`git mv`) das fases anteriores e a remoção de `frontend/src/boot/axios.js`
  (`git rm`, esta fase). O resto está no diretório de trabalho, não preparado.
- O relatório consolidado da modernização está em
  [04-relatorio-modernizacao.md](../04-relatorio-modernizacao.md).
