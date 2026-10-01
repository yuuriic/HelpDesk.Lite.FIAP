# Fase 2 — Regras de negócio e concorrência (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §6](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix`: muda comportamento; os testes de defeito foram substituídos por testes de contrato |
| Achados da fase | REG-01, REG-02, CON-01. O plano não atribui outros achados a esta fase |

---

## 1. Regras corrigidas

| ID | Antes | Depois |
|---|---|---|
| **REG-01** | O único admin conseguia rebaixar o próprio perfil (200). Depois disso, ninguém mais gerenciava usuários, sem caminho de recuperação | **422** `REGRA_NEGOCIO` "O sistema precisa de ao menos um administrador ativo" sempre que a operação deixaria zero admins ativos. Vale para rebaixar e para desativar. Admin inativo não conta |
| **REG-02** | Um atendente com chamado em andamento podia ser rebaixado a SOLICITANTE (200) ou desativado (204). O chamado ficava com um atendente que não conseguia mais agir, e ninguém da fila podia assumir | **422** `REGRA_NEGOCIO` ao rebaixar a SOLICITANTE ou desativar um usuário que é atendente de chamado `EM_ATENDIMENTO` ou `RESOLVIDO` (reabrível, porque a reabertura devolve o chamado a ele) |
| **CON-01** | Sem lock: duas assunções simultâneas passavam as duas pela `TransicaoStatus`, venceu a última escrita, e as duas respostas eram 200 | **Lock otimista** (`@Version`). Um único vencedor; o perdedor recebe **409** `CONFLITO` (se leu antes do commit do vencedor) ou 422 `TRANSICAO_INVALIDA` (se leu depois) |

### O que **não** mudou, de propósito

- **Nenhuma funcionalidade nova.** Não há reatribuição de chamado, conforme o plano (§14). A saída que já existia continua valendo: o admin resolve e fecha o chamado, e aí o rebaixamento ou a desativação é aceito. Isso está coberto por `deveLiberarDepoisQueOAdminConcluiOsChamados`.
- **Não bloqueia além da invariante:**
  - editar nome ou e-mail;
  - trocar ATENDENTE ↔ ADMIN (os dois atendem);
  - rebaixar um admin quando existe outro ativo;
  - sair com chamados apenas `FECHADO` (o histórico continua apontando para o usuário);
  - desativar quem só tem chamados `ABERTO` (ainda sem atendente).
- **Auto-rebaixamento com outro admin ativo:** continua permitido. A regra é a invariante "ao menos um admin"; não se criou uma regra nova de simetria com a I10.

---

## 2. Estratégia de concorrência

**Escolha: lock otimista via `@Version Long versao` em `Chamado`.**

| Critério | Lock otimista (adotado) | Alternativas descartadas |
|---|---|---|
| Compatível com JPA/Hibernate e H2 → PostgreSQL | Sim, recurso padrão do JPA | *Lock* pessimista (`SELECT ... FOR UPDATE`): segura a linha durante toda a requisição e muda as consultas do repositório |
| Toca a arquitetura atual | Um campo na entidade; nenhum método de service ou repositório muda | `UPDATE ... WHERE status='ABERTO'` condicional: exigiria uma consulta nova e duplicaria a regra de transição fora da `TransicaoStatus` |
| Cobre todas as escritas do chamado | Sim: assumir, alterar status e qualquer alteração futura | O `UPDATE` condicional cobre só o assumir |
| Resposta para o cliente | 409 `CONFLITO`, já mapeado na Fase 1 | — |

Como funciona: o Hibernate grava com `UPDATE chamado SET ..., versao = versao + 1 WHERE id = ? AND versao = ?`. Se outra transação confirmou antes, nenhuma linha é afetada, o commit lança `ObjectOptimisticLockingFailureException`, a transação é revertida e o `GlobalExceptionHandler` responde 409.

Detalhes da implementação:
- A `versao` **não tem setter** (`@Setter(AccessLevel.NONE)`): só o Hibernate a altera.
- A coluna tem `DEFAULT 0` (`@ColumnDefault("0")`). O `data.sql` e os dados existentes funcionam sem alteração, o que foi verificado no jar com o seed.
- A `versao` **não aparece na API**. `ChamadoContratoApiIT` fixa o conjunto exato de campos, e ele passou sem edição.

### Migração para o perfil `prod` (`ddl-auto: validate`)

```sql
ALTER TABLE chamado ADD COLUMN versao BIGINT DEFAULT 0 NOT NULL;
```

A consolidação no documento de arquitetura fica para a Fase 9, conforme o plano.

---

## 3. Alterações

| Arquivo | Mudança |
|---|---|
| [UsuarioService.java](../../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java) | Guardas `garantirOutroAdminAtivo` (REG-01) e `garantirSemChamadosEmAndamento` (REG-02) em `atualizar` e `desativar`. Passa a receber `ChamadoRepository`, como o `CategoriaService` já fazia |
| [UsuarioRepository.java](../../../backend/src/main/java/br/com/helpdesk/repository/UsuarioRepository.java) | `countByPerfilAndAtivoTrue` (consulta derivada) |
| [ChamadoRepository.java](../../../backend/src/main/java/br/com/helpdesk/repository/ChamadoRepository.java) | `existsByAtendenteIdAndStatusIn` (consulta derivada) |
| [Chamado.java](../../../backend/src/main/java/br/com/helpdesk/domain/model/Chamado.java) | `@Version Long versao`, sem setter e com `DEFAULT 0` |
| [.ai/business-rules.md](../../../.ai/business-rules.md) | I3 detalhada; novas I11 (ao menos um admin) e I12 (lock otimista) |

---

## 4. Testes

### Testes de defeito substituídos (4 removidos de `DefeitosConhecidosIT`)

Antes de apagá-los, os 4 foram executados contra o código corrigido e **falharam** mostrando o novo comportamento: 422 nos três de REG, e `OptimisticLockException` no de CON-01.

| Removido | Substituído por |
|---|---|
| `unicoAdminPodeRebaixarASiMesmo` | `InvariantesUsuarioIT.AoMenosUmAdmin` (3 testes) |
| `Reg02.atendenteRebaixadoDeixaChamadoOrfao` | `InvariantesUsuarioIT.AtendenteComChamadoEmAndamento.deveImpedirRebaixarComChamadoEmAtendimento` |
| `Reg02.atendenteDesativadoContinuaComOChamado` | `...deveImpedirDesativarComChamadoEmAtendimento` |
| `ultimaEscritaVenceSemLockOtimista` | `ConcorrenciaChamadoIT.deveRecusarAAssuncaoBaseadaEmLeituraDesatualizada`. É determinístico, pelo `ChamadoService` real e **sem setters**, para sobreviver ao DOM-01 da Fase 5 |

### Testes novos (24)

| Arquivo | Testes | O que garante |
|---|:---:|---|
| [InvariantesUsuarioIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/InvariantesUsuarioIT.java) | 11 | REG-01 e REG-02 pela API, **mais os casos que precisam continuar permitidos** (lista da §1) e o caminho de desbloqueio sem reatribuição |
| [ConcorrenciaChamadoIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/ConcorrenciaChamadoIT.java) | 3 | Versão inicial 0; intercalação determinística (o primeiro vence, o segundo recebe `ObjectOptimisticLockingFailureException`); 6 atendentes simultâneos pela API com **exatamente um 200**, os demais 409 ou 422 e nenhum 500 |
| `UsuarioServiceTest` (2 `@Nested` novos) | 10 | Unitários das guardas, incluindo os limites: dois admins, admin inativo, edição sem troca de perfil, promoção, desativação do único admin e o conjunto exato de status consultado (`EM_ATENDIMENTO`, `RESOLVIDO`) |

**Estabilidade do teste concorrente:** rodado 5 vezes seguidas, com um único vencedor em todas. A distribuição dos perdedores foi de 4 a 5 respostas 409 e de 0 a 1 respostas 422, e nenhum 500. Isso mostra que as transações de fato se sobrepõem e que o H2 resolve o conflito pela versão, sem outra exceção de concorrência escapando.

### Testes existentes adaptados (1, só para acompanhar o construtor)

`UsuarioServiceTest.prepararServico`: o construtor recebe o `ChamadoRepository` mockado. Nenhuma asserção existente mudou.

### Contratos preservados, sem edição

`CadastrosApiIT` (atualizar e desativar usuário sem chamados, I10), `ChamadoContratoApiIT` (inclusive o conjunto exato de campos do JSON, sem a `versao`), `CicloDeVidaChamadoIT`, `ChamadoServiceTest`, `ErrosHttpApiIT` e todos os testes de domínio.

---

## 5. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 213 ✓ | **233 ✓** (−4 de defeito, +24), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 71 ✓ | **71 ✓** (sem mudança) |
| Cobertura de linhas, backend | 95,5% | **95,6%** |
| Cobertura de branches, backend | 69,3% | **71,8%** |
| domain + service | 94,9% | **95,1%** |
| `UsuarioService` | 100% | **100%** de linhas e branches |
| Testes `[DEFEITO CONHECIDO]` restantes | 13 | **9** (5 no backend: REG-03, SEG-03, SEG-04, CON-03, OBS-01; 4 no frontend) |

### Verificação no jar empacotado (perfil `dev`, com o seed do `data.sql`)

```
DDL gerada                                   versao bigint default 0 not null
PATCH /api/chamados/1/assumir  (Bruno)       -> 200   (chamado do seed, versao via DEFAULT)
PATCH /api/chamados/1/assumir  (de novo)     -> 422   TRANSICAO_INVALIDA
PUT   /api/usuarios/3 -> SOLICITANTE (Carla) -> 422   "O sistema precisa de ao menos um administrador ativo"
PUT   /api/usuarios/2 -> SOLICITANTE (Bruno) -> 422   atendente de chamados em atendimento
DELETE /api/usuarios/2 (Bruno)               -> 422
DELETE /api/usuarios/1 (Ana, solicitante)    -> 204
GET   /api/chamados/1                        -> 200   sem o campo "versao"
linhas ERROR no log: 0
```

---

## 6. Pontos para as próximas fases

- **OBS-02 (Fase 8):** o perdedor de uma assunção concorrente chega a registrar a métrica de transição e a linha de auditoria antes de o commit falhar. É o comportamento documentado no OBS-02, que move essas emissões para depois do commit.
- **Frontend (Fase 7):** um 409 hoje aparece como notify de erro com a mensagem "O registro foi alterado por outra requisicao. Recarregue e tente novamente". Recarregar o detalhe automaticamente seria melhoria de UX, não correção, e fica a critério da Fase 7.
- **Decisão do grupo:** incluir `RESOLVIDO` na guarda do REG-02 (decisão do plano) pode segurar a desativação até os solicitantes confirmarem. O admin consegue destravar fechando os chamados. Se o grupo preferir considerar só `EM_ATENDIMENTO`, a mudança é uma linha (`STATUS_QUE_PRENDEM_O_ATENDENTE`) e um teste (`deveConsiderarChamadoResolvidoReabrivel`).
