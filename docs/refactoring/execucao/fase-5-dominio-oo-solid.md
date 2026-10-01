# Fase 5 — Domínio, Orientação a Objetos e SOLID (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §9](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `refactor`: **sem mudança de comportamento**. Nenhum teste de contrato ou de integração foi editado, e nenhum teste de defeito foi substituído, porque a fase não corrige defeito |
| Tratados | **DOM-01**, **DOM-02**, **DOM-03**, **ARQ-03** (necessários); **DOM-04** (parâmetro morto), **ARQ-02**, **ARQ-04** (opcionais, justificados na §2) |
| Não tratado, de propósito | **DOM-06** (Extract Class `ComentarioService`); ver §2.7 |

---

## 1. Execução incremental

Cada passo terminou com a suíte completa do backend verde, antes do passo seguinte.

| Passo | Achados | Suíte ao fim do passo |
|:---:|---|---|
| 1 | DOM-01, DOM-02: o agregado `Chamado` passa a ser dono das transições; `Comentario` imutável | 262 ✓, sem editar nenhum teste de contrato |
| 1b | Testes do agregado, sem Spring e sem mocks, mais a guarda estrutural contra setters | 277 ✓ |
| 2 | DOM-03, ARQ-03, DOM-04: capacidades no `Perfil` e regra de visibilidade única | 280 ✓ |
| 3 | ARQ-02: `PermissaoService` e `SlaCalculator` movidos para `domain`, sem Spring | 280 ✓ |
| 4 | ARQ-04: `SituacaoSla`, com o SLA avaliado uma vez por chamado | 280 ✓ |

---

## 2. Problemas tratados e decisões de design

### 2.1 DOM-01: modelo anêmico e setters públicos

**Antes:** `Chamado` tinha `@Setter` e construtores públicos. O `ChamadoService` montava o chamado pelo builder e mudava `status`, `atendente` e os carimbos com 9 chamadas a `setX(...)`. Nada impedia um `setStatus(FECHADO)` sem passar pela máquina de estados. `Comentario` também tinha `@Setter`, embora a regra I4 o declare imutável.

**Depois:**

| Método de intenção | O que encapsula |
|---|---|
| `Chamado.abrir(titulo, descricao, prioridade, categoria, solicitante, agora)` | Nasce em ABERTO, sem atendente, `criadoEm = atualizadoEm`. **A invariante I8 (categoria ativa) saiu do service e é recusada pelo próprio agregado** |
| `chamado.assumir(atendente, transicoes, agora)` | Valida `status → EM_ATENDIMENTO` para o perfil do atendente, define o atendente, muda o status e carimba |
| `chamado.alterarStatus(novo, perfil, transicoes, agora)` | Valida a aresta, aplica os carimbos da transição e muda o status |
| `chamado.tempoAteResolucao()` | `resolvidoEm − criadoEm`, usado pela métrica. Antes, o service fazia essa conta |
| `Comentario.registrar(chamado, autor, texto, agora)` | Comentário novo, sem setters depois |

**Decisões:**
- **Sem setters em `Chamado` e `Comentario`.** O construtor sem argumentos ficou `protected` (para o JPA) e o completo, `private` (para o builder). Um teste estrutural (`ChamadoTest.naoDeveExporSetters`) falha se alguém devolver um setter público.
- **O `@Builder` continua existindo,** só para reconstituir chamados e comentários já existentes em *fixtures* de teste. Chamado novo nasce por `abrir`. Removê-lo exigiria reescrever o `ChamadoBuilder` de suporte sem ganho de proteção em produção, onde ninguém mais o usa.
- **A validação continua sendo da `TransicaoStatus`.** A entidade recebe a tabela e a consulta, **não duplica** as arestas. A tabela segue como a única fonte de "quem pode ir para onde" (ADR-005).
- **A ordem 403 → 422 continua escrita no service:** `permissaoService.verificar...` (403) roda antes de `chamado.assumir/alterarStatus` (422). O agregado não conhece permissão (ADR-015). O `InOrder` do `ChamadoServiceTest` continua passando sem edição.
- **Métrica e auditoria continuam no service.** A entidade não conhece Micrometer nem log.
- **Entidades JPA continuam sendo classes.** `Usuario` e `Categoria` mantêm os setters: são cadastros simples editados campo a campo, e as invariantes deles (REG-01, REG-02, unicidade) dependem de consultas ao repositório, então ficam no service.

### 2.2 DOM-02: efeitos da transição separados da tabela

O `switch` de carimbos (`aplicarEfeitosDaTransicao`) saiu do `ChamadoService` e virou um método privado do `Chamado`. Ele é chamado só depois de a transição ser validada, e os efeitos ficam junto do estado que alteram. **Não foi criado um padrão State:** a tabela resolve o "pode?" e um `switch` de 4 casos resolve os efeitos, sem justificativa para uma hierarquia de classes.

### 2.3 DOM-03: *type code* `perfil == X`

**Antes:** 11 comparações de identidade (`perfil == Perfil.SOLICITANTE`…): 7 no `PermissaoService`, 1 na `ChamadoSpecification` e 3 nos `Usuario.isX()`, que não eram usados.

**Depois:** o `Perfil` responde a perguntas de **capacidade**: `enxergaTodosOsChamados()`, `atendeChamados()` e `gerenciaCadastros()`, declaradas junto de cada constante. Quem usa:

| Onde | Pergunta |
|---|---|
| `PermissaoService` (visualização, comentário, dashboard) | `enxergaTodosOsChamados()` |
| `PermissaoService.verificarAssuncao` | `atendeChamados()` |
| `PermissaoService` (categorias, usuários) | `gerenciaCadastros()` |
| `ChamadoSpecification` (visibilidade no `WHERE`) | `enxergaTodosOsChamados()` |
| `UsuarioService` (guarda do REG-02: "deixa de poder atender") | `atendeChamados()` |

- A regra que depende do perfil **e** da relação com o chamado (alterar status "só nos seus") virou um `switch` **exaustivo** sobre o `Perfil`: um perfil novo não compila até alguém decidir que relação ele exige.
- **Restam 2 comparações de identidade, de propósito,** na guarda do REG-01 (`UsuarioService`). A regra é literalmente sobre o perfil ADMIN ("sempre existe ao menos um ADMIN ativo"), não sobre uma capacidade.
- `Usuario.isAdmin/isAtendente/isSolicitante` (sem uso) foram removidos.

### 2.4 ARQ-03: regra de visibilidade em três lugares

A `ChamadoSpecification` (query) e o `PermissaoService` (detalhe e comentário) agora fazem **a mesma pergunta**: `perfil.enxergaTodosOsChamados()`. No `PermissaoService`, visualização e comentário usam um único predicado privado (`podeVer`). **As mensagens de erro continuam idênticas**, porque são contrato fixado nos testes.

### 2.5 DOM-04: parâmetro morto

`verificarAssuncao(usuario, chamado)` virou `verificarAssuncao(usuario)`: o chamado nunca era lido. **O `verificarAbertura` (corpo vazio) foi mantido:** o plano deixa a escolha entre remover e manter com teste documentando a decisão, e o método torna explícita a regra "todos os perfis abrem chamado", coberta pelo `PermissaoServiceTest.Abertura`.

### 2.6 ARQ-02: regras de domínio fora do domínio

`SlaCalculator` → `domain.sla`, `PermissaoService` → `domain.permissao`, sem `@Service`, expostos como beans pelo `DominioConfig`, a mesma política que a `TransicaoStatus` já seguia. As duas foram movidas com `git mv` para preservar o histórico. O `PermissaoServiceTest` foi para o pacote de testes de domínio, junto do `SlaCalculatorTest`.

**Resultado verificável:** o pacote `domain` não importa **nada** de Spring, `service`, `web` ou `repository` (0 arquivos).

### 2.7 ARQ-04: SLA reavaliado várias vezes

**Antes:** no dashboard, "fora do SLA" era avaliado no filtro, **dentro do comparador da ordenação** (uma vez por comparação, O(n log n)) e de novo no mapper. O `ChamadoMapper` tomava a decisão de SLA ao montar a resposta.

**Depois:**
- `SlaCalculator.situacao(chamado)` devolve o value object `SituacaoSla(prazo, foraDoSla, atraso)`. É a **única implementação** da regra: `foraDoSla()` e `atraso()` delegam a ela, e o relógio é lido uma vez por avaliação.
- O mapper só copia os valores.
- O dashboard avalia **uma vez por chamado** e entrega a avaliação ao mapper (`paraResumo(chamado, sla)`).
- `SituacaoSla` é um `record` porque é um valor imutável, não uma entidade.

### 2.8 DOM-06 (não tratado): Extract Class `ComentarioService`

O plano o marcava como opcional. Não foi feito porque comentar e listar comentários dependem do **mesmo** "buscar o chamado e verificar a visibilidade" dos demais casos de uso do chamado. O comentário é parte do agregado (a regra I4 e a visibilidade vêm do chamado). Extrair a classe duplicaria esse passo ou exigiria compartilhá-lo entre dois services, sem reduzir dependências: o `ChamadoService` continuaria com as mesmas 9.

---

## 3. Responsabilidades movidas ou encapsuladas

| Responsabilidade | Antes | Depois |
|---|---|---|
| Montar um chamado novo em estado válido | `ChamadoService.abrir` (builder campo a campo) | `Chamado.abrir` |
| Invariante I8 (categoria ativa) | `ChamadoService.abrir` | `Chamado.abrir` |
| Aplicar a transição de assumir | `ChamadoService.assumir` (3 setters) | `Chamado.assumir` |
| Carimbos de cada transição | `ChamadoService.aplicarEfeitosDaTransicao` | `Chamado` (privado) |
| Duração até a resolução (métrica) | `Duration.between(...)` no service | `Chamado.tempoAteResolucao()` |
| Criar comentário | builder no service | `Comentario.registrar` |
| "Este perfil vê tudo / atende / gerencia?" | `perfil == X` espalhado | `Perfil` |
| Visibilidade do detalhe e do comentário | 2 `if` duplicados | `PermissaoService.podeVer` |
| Avaliar o SLA de um chamado | 3 avaliações (mapper e dashboard) | `SlaCalculator.situacao`, uma vez |
| Regras de permissão e de SLA | pacote `service`, `@Service` | pacote `domain`, sem Spring |

---

## 4. Impacto em acoplamento e coesão

| Indicador | `main` | Agora |
|---|:---:|:---:|
| `@Setter` em `Chamado` / `Comentario` | sim / sim | **não / não** |
| `chamado.setX(...)` no `ChamadoService` | 9 | **0** |
| Comparações de identidade de perfil (`== Perfil.X`) | 11 | **2** (a regra do ADMIN, de propósito) |
| Lugares que conhecem a tabela de transições | tabela + `switch` de efeitos no service | tabela (efeitos no agregado) |
| Imports de Spring/`service`/`web`/`repository` no pacote `domain` | — | **0** |
| Regras de domínio anotadas com `@Service` | 2 | **0** |
| Linhas do `ChamadoService` | 245 | 200 |
| Métodos de comportamento no `Chamado` | 0 | 4 (+1 no `Comentario`) |
| Avaliações de SLA por chamado no dashboard | 2 + O(log n) | **1** |
| CC máximo no backend (lizard) | 6 | 7 (`verificarAlteracaoStatus`, pelo `switch` exaustivo), ainda **< 10** |
| Cobertura de linhas do `Chamado` | 17% | **90%** |

**Leitura:**
- **Coesão:** o estado do chamado e as regras que o protegem estão agora na mesma classe, e o `ChamadoService` passou a ser só a sequência de orquestração que o Javadoc sempre descreveu.
- **Acoplamento:** o `ChamadoService` continua com 9 dependências. O ganho não está na contagem de dependências, e sim no fato de o service não conhecer mais os detalhes internos do agregado (setters e carimbos) nem a regra de SLA.
- **Regra de dependência:** o domínio ficou completamente sem Spring. As violações entre `service` e `web` (ARQ-01) são da Fase 6 e não foram tocadas.

---

## 5. O que foi preservado, e como se sabe

| Contrato | Proteção (sem edição nesta fase) |
|---|---|
| Transições (8 combinações válidas, FECHADO final, reabertura só de RESOLVIDO) | `TransicaoStatusTest`, mais o novo `ChamadoTest` |
| Ordem 403 → 422 | `ChamadoServiceTest.deveChamarPermissaoEDepoisTransicaoNaOrdem` (`InOrder`), `CicloDeVidaChamadoIT` |
| Atribuição de atendente | `ChamadoContratoApiIT.Atribuicao`, `ChamadoServiceTest.Assumir` |
| Permissões e mensagens de erro | `PermissaoServiceTest` (só pacote e assinatura mudaram), `CadastrosApiIT` |
| SLA | `SlaCalculatorTest` (18 testes, só o import mudou) e o formato de `prazoSla`/`foraDoSla` em `ChamadoContratoApiIT` |
| Auditoria | `ChamadoContratoApiIT.Auditoria`; no jar real, as 7 linhas do ciclo de vida saíram idênticas ao formato anterior |
| Concorrência (`@Version`) | `ConcorrenciaChamadoIT` (o intercalado determinístico e os 6 atendentes simultâneos), sem edição |
| Consultas por requisição (Fase 4) | `ConsultasPorRequisicaoIT`, sem edição |
| Invariantes de usuário (Fase 2) | `InvariantesUsuarioIT`, `UsuarioServiceTest` |

**Testes existentes adaptados**, todos por assinatura ou pacote, sem mudar nenhuma asserção:
- `PermissaoServiceTest`: pacote novo e `verificarAssuncao` sem o parâmetro morto;
- imports em `SlaCalculatorTest`, `ChamadoServiceTest`, `CategoriaServiceTest`, `DashboardServiceTest` e `UsuarioServiceTest`;
- `ChamadoSpecificationTest.salvar`: o `setResolvidoEm` depois do `build` virou `.resolvidoEm(...)` no builder, e o chamado persistido é o mesmo.

**Testes novos (18):**
- [ChamadoTest.java](../../../backend/src/test/java/br/com/helpdesk/domain/ChamadoTest.java), 15 testes: o agregado sem Spring nem mocks (abrir e I8, assumir e seus limites, resolver, fechar, reabrir, transição inválida sem alterar nada, perfil errado, FECHADO final, comentário), mais a guarda estrutural contra setters;
- [PerfilTest.java](../../../backend/src/test/java/br/com/helpdesk/domain/PerfilTest.java), 3 testes: a tabela de capacidades.

---

## 6. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 262 ✓ | **280 ✓** (+18), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 71 ✓ | **71 ✓** |
| Cobertura de linhas, backend | 95,7% | **96,9%** |
| Cobertura de branches, backend | 72,6% | **75,9%** |
| domain + service | 95,1% | **96,8%** |
| Testes `[DEFEITO CONHECIDO]` restantes | 6 | **6** (esta fase não corrige defeitos) |

**Jar empacotado (perfil `dev`), ciclo completo:**
- abrir → categoria inativa 422 → solicitante resolve 403 → admin resolve ABERTO 422 → assumir → resolver → reabrir → resolver → fechar → FECHADO final 422 → comentar;
- dashboard com 3 fora do SLA, na ordem do mais atrasado;
- métrica de tempo de resolução registrada;
- 7 linhas de auditoria no formato anterior;
- 0 linhas ERROR.

---

## 7. Anotado para outras fases

- **Fase 8 (observabilidade):** o timer é exposto no Prometheus como `helpdesk_tempo_resolucao_segundos_seconds_*`, porque o Micrometer acrescenta a unidade a um nome que já termina em "segundos". Já era assim na `main`; a documentação (§4.11) fala em `helpdesk_tempo_resolucao_segundos`.
- **Fase 9 (DOM-05):** `Chamado.pertenceA` e `StatusChamado.concluido` continuam sem uso; ficaram de fora para não misturar remoção de código morto com a reestruturação.
