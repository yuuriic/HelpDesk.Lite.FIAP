# Correção pontual do N-01 — invariantes de usuário sob concorrência

| Item | Valor |
|---|---|
| Origem | [05-auditoria-final.md](../05-auditoria-final.md), achado **N-01** (confirmado em execução) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix`: só muda o resultado de requisições **concorrentes**. Comportamento sequencial, rotas e corpos de resposta inalterados |
| Tratados | **N-01** para a I11 (ao menos um admin ativo, REG-01) e, comprovadamente aplicável, para a I3 (atendente com chamados em andamento, REG-02) |
| Fora desta correção | Todas as demais dívidas da auditoria final, inclusive N-03 (`== Perfil.ADMIN` na guarda), que continua igual |

---

## 1. Causa

As duas guardas criadas na Fase 2 são **"verifica e depois grava"**, sem nada que serialize as
transações:

| Guarda | Verificação | Escrita | Corrida |
|---|---|---|---|
| REG-01 (I11) | `countByPerfilAndAtivoTrue(ADMIN) > 1` | Rebaixa ou desativa **um** admin | O admin A rebaixa o B enquanto o B rebaixa o A: cada transação conta 2 antes de a outra gravar, e cada uma grava uma linha **diferente** |
| REG-02 (I3) | `existsByAtendenteIdAndStatusIn(...)` é falso | Rebaixa ou desativa o atendente | O atendente assume um chamado (grava em `chamado`) enquanto o admin o rebaixa (grava em `usuario`): nenhuma vê a escrita da outra |

É *write skew*. As escritas não se sobrepõem, então nem o `@Version` do `Chamado` (ADR-020) nem
um `@Version` em `Usuario` fariam as duas transações conflitarem.

O REG-02 tem ainda uma variante **sem concorrência real de transações**. O
`UsuarioAtualFilter` lê o usuário antes de o caso de uso começar. Se o admin confirmar o
rebaixamento nesse intervalo, o `assumir` usa o perfil antigo (ATENDENTE) e passa por cima da
guarda.

### O REG-02 é comprovadamente aplicável

Os testes da §3 foram escritos **antes** da correção e executados contra o código da Fase 9.
Todos falharam, mostrando o estado inválido gravado:

| Teste | Falha com o código anterior |
|---|---|
| `AoMenosUmAdmin.naoDeveZerarOsAdminsComRebaixamentosCruzados` | `expected: 1L but was: 0L` (admins ativos) |
| `AoMenosUmAdmin.naoDeveZerarOsAdminsComDesativacaoERebaixamento` | `expected: 1L but was: 0L` |
| `AtendenteComChamados.naoDeveAssumirComPerfilRebaixadoNoMeioDoCaminho` | `expected: ABERTO but was: EM_ATENDIMENTO` (chamado assumido por quem acabou de virar SOLICITANTE) |
| `AtendenteComChamados.naoDeveRebaixarQuemAcabouDeAssumir` | `expected: ATENDENTE but was: SOLICITANTE` (rebaixado com um chamado em atendimento) |
| `AtendenteComChamados.naoDeveAssumirComUsuarioDesativadoNoMeioDoCaminho` | `expected: null but was: Usuario@...` (chamado com atendente desativado) |
| `AtendenteComChamados.naoDeveAssumirComOUsuarioDesatualizadoDoFiltro` | O chamado ficou com atendente (a variante sem threads) |

Por isso o REG-02 foi tratado na mesma correção.

## 2. Estratégia

**Trava pessimista de linha (`SELECT ... FOR UPDATE`) sobre as linhas de `usuario` que as
guardas protegem, sempre na mesma ordem.** A requisição concorrente espera o commit da primeira
e decide sobre o estado já gravado.

| Onde | O que trava | Em que ordem |
|---|---|---|
| `UsuarioService.atualizar` e `desativar` (`buscarComTrava`) | Todos os admins ativos e, depois, o usuário alterado | 1º os admins, por id crescente; 2º o alvo. Só então rodam a contagem de admins e a busca de chamados do atendente, que veem o que a outra transação confirmou |
| `ChamadoService.assumir` | A linha do próprio atendente, **antes** de ler o chamado | Uma linha. Quem vira atendente é o usuário gravado e travado, não o objeto que o filtro leu: perfil rebaixado → 403; usuário desativado → 401 `NAO_AUTENTICADO`, a mesma resposta do filtro (I9) |
| `GlobalExceptionHandler` | — | `PessimisticLockingFailureException` (trava não obtida dentro do limite do banco ou deadlock) → 409 `CONFLITO`, em vez de cair no 500 genérico |

**Por que a trava pessimista e não o *optimistic locking* da ADR-020:**

- **O *write skew* não gera conflito de versão.** Para usar versão seria preciso incrementar à
  força as versões de todos os admins (`OPTIMISTIC_FORCE_INCREMENT`). O Hibernate grava o
  rebaixamento antes desses incrementos, o que faria duas transações cruzadas travarem as linhas
  em ordem inversa (risco de deadlock). E o perdedor receberia 409 mesmo quando a resposta
  correta é a regra de negócio (422).
- **A trava pessimista dispensa mudança de schema.** Não há coluna nova nem script de migração
  para `prod`, e ela usa o que JPA e Spring Data já oferecem (`@Lock`).
- **O perdedor recebe a resposta da regra.** Ele espera o vencedor confirmar e então recebe
  **422** (último admin, atendente com chamado) ou **403** (perfil já rebaixado). É a mesma
  resposta que daria se tivesse chegado depois.

**Comportamento do banco, verificado antes de escolher (H2 2.2.224, o da aplicação):**

- depois de esperar a trava, o `SELECT ... FOR UPDATE` devolve a linha já confirmada e reavalia o
  `WHERE`;
- uma consulta nova enxerga o commit da outra transação;
- o limite de espera padrão é de cerca de **2 s**, e ao estourá-lo o banco responde com o
  erro 50200. Daí o mapeamento para 409.

**O que não mudou:**

- rotas, status e corpos das respostas sequenciais;
- schema e seed;
- a guarda em si (mesmas regras, mesmas mensagens);
- N-03: a guarda continua com `== Perfil.ADMIN`.

**Custo:**

| Requisição | Antes | Depois |
|---|---|---|
| `PATCH /api/chamados/1/assumir` | 4 comandos SQL (medido na auditoria final) | 5. O comando a mais é a leitura travada do usuário |
| `PUT /api/usuarios/{id}` | não medido antes | 4 |
| `DELETE /api/usuarios/{id}` | não medido antes | 5 |

Nos dois últimos, a trava substitui o `findById` do alvo e acrescenta a leitura travada dos
admins ativos.

O `ChamadoService` passou a receber o `UsuarioRepository`: são **10 dependências**, contra as 9
registradas no DOM-06. A classificação do DOM-06 (dívida técnica) não foi alterada.

## 3. Testes

### Concorrentes e determinísticos: `ConcorrenciaUsuarioIT` (novo, 6 testes)

Cada teste executa duas "requisições" (chamadas ao service real, cada uma na sua transação e na
sua thread), sempre na mesma ordem:

1. a primeira executa o caso de uso e **para antes do commit**;
2. a segunda roda até terminar, **ou** até o H2 registrar que ela espera uma trava da primeira
   (`INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID`);
3. só então a primeira confirma.

Não há `sleep` nem dependência de tempo. Sem a correção, a segunda nunca espera e confirma sobre
uma leitura que a primeira invalida. Com a correção, ela sempre espera, o que é verificado em
cada teste (`segundaEsperouLock`).

| Teste | Primeira | Segunda | Garantia depois da correção |
|---|---|---|---|
| `naoDeveZerarOsAdminsComRebaixamentosCruzados` | Carla rebaixa Diego | Diego rebaixa Carla | 1 admin ativo; a segunda recebe `RegraNegocioException` (422) |
| `naoDeveZerarOsAdminsComDesativacaoERebaixamento` | Carla desativa Diego | Diego rebaixa Carla | 1 admin ativo; a segunda recebe 422 |
| `naoDeveAssumirComPerfilRebaixadoNoMeioDoCaminho` | Carla rebaixa Bruno | Bruno assume | Chamado continua ABERTO; a segunda recebe `AcessoNegadoException` (403) |
| `naoDeveRebaixarQuemAcabouDeAssumir` | Bruno assume | Carla rebaixa Bruno | Bruno continua ATENDENTE e atendente do chamado; a segunda recebe 422 |
| `naoDeveAssumirComUsuarioDesativadoNoMeioDoCaminho` | Carla desativa Bruno | Bruno assume | Chamado sem atendente; a segunda recebe `NaoAutenticadoException` (401) |
| `naoDeveAssumirComOUsuarioDesatualizadoDoFiltro` (sem threads) | Rebaixamento confirmado | `assumir` com o objeto lido antes | Chamado sem atendente; 403 |

A pré-condição de cada teste verifica que não há outros admins ativos na base, para o resultado
não depender de dados de outras suítes.

**Estabilidade:** a classe foi executada **10 vezes seguidas**, e as 10 execuções passaram.

### Unitários e de web

| Arquivo | Mudança |
|---|---|
| `UsuarioServiceTest` | **Adaptado:** os 14 *stubs* de `findById` dos testes de `atualizar`/`desativar` passaram a `findComTravaById` (os de `autenticar` continuam em `findById`). As asserções não mudaram. **Novo:** `deveTravarAdminsDepoisOAlvoAntesDasGuardas` (InOrder: admins → alvo → contagem → chamados) |
| `ChamadoServiceTest` | **Adaptado:** construtor com o `UsuarioRepository`, e o *stub* padrão devolve o usuário gravado igual ao do filtro. **Novos:** `deveDecidirPeloUsuarioGravado` (403), `deveRecusarUsuarioDesativadoDepoisDoFiltro` (401) e `deveTravarOUsuarioAntesDeLerOChamado` |
| `TratamentoDeErrosWebTest` | **Novo:** `deveMapearTravaNaoObtidaPara409`. Verifica 409 `CONFLITO`, que a mensagem do banco não aparece no corpo e que o log é WARN, sem "Erro interno" |

Nenhum teste de contrato ou de integração existente foi editado. `InvariantesUsuarioIT` (as
guardas em sequência), `ConcorrenciaChamadoIT` e `ConsultasPorRequisicaoIT` passaram sem
alteração.

### Verificação empírica com o jar

É o mesmo roteiro da auditoria final: dois admins rebaixam um ao outro por HTTP, com requisições
paralelas, em 20 tentativas.

| | Antes (auditoria final) | Depois |
|---|---|---|
| Resultado | 1ª tentativa: `PUT` 200/200, **sistema sem admin** | 20 tentativas, **sempre exatamente um 200**: 16 × 200/422, 2 × 422/200 e 2 × 200/403 (a requisição do Diego chegou ao filtro depois de ele já ter sido rebaixado). Nenhuma tentativa terminou sem admin |
| Linhas `ERROR` no log | — | 0 |

## 4. Validação

| Verificação | Antes (auditoria final) | Depois |
|---|---|---|
| Backend (`./mvnw verify`, relatórios apagados antes) | 291 ✓ | **302 ✓** (+6 concorrentes, +4 unitários, +1 web), 0 falhas, 0 erros, 0 ignorados; gate do JaCoCo aprovado |
| Frontend (`npx vitest run`) | 86 ✓ | **86 ✓** (sem alteração) |
| `npx quasar build` | ✓ | ✓ |
| Cobertura de linhas (total / `domain`+`service`) | 97,9% / 97,7% | **98,0%** (669/683) / **97,7%** |
| Cobertura de *branches* (total / `domain`+`service`) | 79,7% / 74,3% | 79,7% / 74,3% |
| Complexidade ciclomática (lizard 1.24.0) | 283 funções, máximo 10, nenhuma > 10 | 284 funções, máximo 10, nenhuma > 10. `ChamadoService.assumir`: 1; `UsuarioService.atualizar`: 5; `desativar`: 2 |
| `DependenciaEntreCamadasTest` | ✓ | ✓ (a trava está no `repository`; nenhuma dependência nova entre camadas) |
| Testes `[DEFEITO CONHECIDO]` | 0 | 0 |

## 5. Arquivos alterados

| Arquivo | Alteração |
|---|---|
| `repository/UsuarioRepository.java` | `findComTravaByPerfilAndAtivoTrueOrderByIdAsc` e `findComTravaById`, com `@Lock(PESSIMISTIC_WRITE)` |
| `service/UsuarioService.java` | `buscar` → `buscarComTrava` em `atualizar` e `desativar`; Javadoc da classe com a causa e a ordem das travas |
| `service/ChamadoService.java` | `assumir` trava e usa o usuário gravado; `UsuarioRepository` injetado |
| `web/exception/GlobalExceptionHandler.java` | Handler de `PessimisticLockingFailureException` → 409 |
| Testes | `ConcorrenciaUsuarioIT` (novo); `UsuarioServiceTest`, `ChamadoServiceTest` e `TratamentoDeErrosWebTest` (adaptados e ampliados, ver §3) |
| Documentação | Classificação de N-01, REG-01 e REG-02 em [05-auditoria-final.md](../05-auditoria-final.md) e em [04-relatorio-modernizacao.md](../04-relatorio-modernizacao.md); ADR-020 (`.ai/architecture.md`); `ARQUITETURA.md` §4.10 e §6; `business-rules.md` §4 e §8; `MVP.md` §9 |

Nenhum commit foi feito.
