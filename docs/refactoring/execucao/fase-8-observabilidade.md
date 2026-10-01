# Fase 8 — Observabilidade (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §12](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix` (OBS-02 e nome do timer) + reclassificação documentada (OBS-01) |
| Corrigidos | **OBS-02** (métricas e auditoria só do que foi confirmado); **nome do timer** com unidade duplicada (anotado na Fase 5) |
| Mantido por decisão arquitetural | **OBS-01** (ADR-018): o teste de defeito virou teste de contrato |
| Fora do escopo (opcionais) | OBS-03 (formato único de auditoria) e os demais itens da §4 |
| Plataformas novas | Nenhuma: sem agendador, sem serviço externo, sem dependência nova |

---

## 1. Situação encontrada (jar da Fase 7, perfil `dev`)

| Série no `/actuator/prometheus` | Tipo | Situação |
|---|---|---|
| `helpdesk_chamados_abertos_total` | counter | ✓ nome correto |
| `helpdesk_chamados_transicoes_total` | counter | ✓ nome correto |
| `helpdesk_chamados_por_status` | gauge | 0 até a primeira leitura do dashboard (OBS-01) |
| `helpdesk_chamados_fora_sla` | gauge | idem |
| **`helpdesk_tempo_resolucao_segundos_seconds_*`** | summary | ✗ **unidade duplicada** |

Além do nome: os contadores, o timer e a linha de auditoria eram emitidos **antes do commit**. Eles contavam também operações que a transação desfazia (OBS-02).

---

## 2. Corrigido

### 2.1 OBS-02: métricas e auditoria antes do commit

- **Evidência, com o teste escrito antes da correção:** `ObservabilidadeIT.naoDeveContarNemAuditarAAssuncaoQuePerdeNoCommit`. Ele reproduz de forma determinística duas assunções do mesmo chamado: a da Eva lê o chamado em ABERTO, a do Bruno assume e confirma no meio, e a da Eva tenta gravar sobre a leitura antiga e falha no commit com conflito de versão (409). **No código anterior, ele falhou com *"expected 1.0 but was 2.0"*:** o perdedor contava como transição e deixava uma linha de auditoria de algo que não aconteceu. A Fase 2 tinha anotado isso para esta fase.
- **Correção:** [AposCommit.java](../../../backend/src/main/java/br/com/helpdesk/service/AposCommit.java), um utilitário de 15 linhas que usa o `TransactionSynchronizationManager` do próprio Spring. O efeito roda no `afterCommit`; se a transação for revertida, ele não acontece. Fora de transação (testes unitários), roda na hora.
  - **`MetricasNegocio`:** os contadores e o timer leem os valores das tags na hora (sem *lazy loading* depois do commit) e incrementam depois do commit. **Os gauges não mudam:** republicam uma leitura (ADR-018), não uma escrita.
  - **Linhas de auditoria:** as do `ChamadoService` (transições e comentário) e também as administrativas do `CategoriaService` e do `UsuarioService`, que tinham a mesma causa (por exemplo, um cadastro que falha no commit pela corrida de e-mail duplicado do CON-02).
- **O que não mudou:** o formato das linhas, as tags e os pontos onde cada métrica é registrada. O `afterCommit` roda na thread da requisição, ainda dentro dos filtros, então o `requestId` e o `usuarioId` do MDC continuam nas linhas (o teste confere `usr=`).

### 2.2 Nome do timer com unidade duplicada

- **Causa:** o timer se chamava `helpdesk_tempo_resolucao_segundos`, e o registro do Prometheus acrescenta a unidade-base dos timers, `_seconds`.
- **Correção:** o nome no Micrometer passou a ser `helpdesk_tempo_resolucao`, e a série exposta ficou **`helpdesk_tempo_resolucao_seconds_{count,sum,max}`**, no padrão do Prometheus. A constante `MetricasNegocio.TEMPO_RESOLUCAO` documenta o porquê.
- **Contadores:** já estavam corretos. O teste garante que não aparece `_total_total`.
- **Impacto:** o MVP não tem Prometheus nem painéis configurados (§5.5 do `.ai/architecture.md`), então nenhuma consulta existente quebra.

---

## 3. Mantido por decisão arquitetural: OBS-01 e a ADR-018

- **Comportamento:** os gauges `helpdesk_chamados_por_status` e `helpdesk_chamados_fora_sla` só mudam quando alguém lê o dashboard; até a primeira leitura, valem 0.
- **A decisão existe e cobre esse caso:** a ADR-018 escolheu recalcular na leitura do dashboard, descartando um `@Scheduled` e um `Gauge` que consultasse o banco a cada scrape, e registrou como consequência: *"o gauge fica desatualizado se ninguém abrir o dashboard — aceitável no MVP, e o `/actuator/prometheus` mostra zero até a primeira leitura"*.
- **Reavaliação:**
  1. O comportamento observado é exatamente a consequência aceita, e não um efeito não previsto.
  2. O MVP não tem Prometheus configurado, então nenhum alerta depende do gauge.
  3. As duas alternativas trariam justamente o que a ADR quis evitar (um agendador, ou consultas disparadas pelo scrape), e a etapa pede para não introduzir infraestrutura.
- **Decisão:** **manter.** O teste `[DEFEITO CONHECIDO OBS-01]` foi **reclassificado** como contrato (`ObservabilidadeIT.deveAtualizarOGaugeNaLeituraDoDashboard`). A ADR-018 em `.ai/architecture.md` ganhou um parágrafo de revisão, com a condição para reabri-la (haver scrape de verdade).
- **Verificação no jar:** gauge `ABERTO` = **0.0** antes do dashboard e **3.0** depois da primeira leitura.

---

## 4. Também mantidos (analisados, sem mudança)

| Ponto | Situação | Por que fica |
|---|---|---|
| Timer de resolução conta de novo quando um chamado reaberto é resolvido outra vez | Cada resolução é um evento, medido desde a abertura | O plano previa só a mudança para depois do commit. Contar "por chamado" em vez de "por resolução" é uma mudança de semântica da métrica. Ver a opção na §6 |
| Tag `categoria` usa o **nome** (renomear divide a série) | Igual à `main` | O plano não previa mudar as tags. Trocar por id muda os rótulos das séries |
| Formatos de auditoria por tipo de alvo (`chamadoId`, `categoriaId`, `usuarioAlvoId`) (OBS-03) | Todos `chave=valor` com `acao` e `perfil`; `requestId`/`usuarioId` vêm do MDC | Opcional no plano. Unificar mudaria o formato de log que o `ObservabilidadeIT` fixa |

---

## 5. Testes

### Novos

| Arquivo | Testes | O que garante |
|---|:---:|---|
| [ObservabilidadeIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/ObservabilidadeIT.java) | 4 | **Com commit real** (sem `@Transactional`, com limpeza no `@AfterEach`): abrir, assumir e resolver contam uma vez cada no contador e no timer; o formato exato das linhas de auditoria (§4.11), com `usr=` do MDC; a linha do comentário; **o perdedor no commit não conta nem é auditado**; o gauge da ADR-018 (contrato) |
| [configuracao/MetricasExpostasIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/configuracao/MetricasExpostasIT.java) | 2 | **Servidor real** (perfil `dev`, contexto do `ConfiguracaoDevIT` reaproveitado): `# TYPE helpdesk_tempo_resolucao_seconds summary`, séries `_count/_sum/_max`, nenhum `_segundos`; contadores e gauges com os nomes e tipos documentados, sem `_total_total` |

**Prova de que detectam:**
- o teste do perdedor falhou no código anterior (*expected 1.0 but was 2.0*);
- o teste do nome, com a constante temporariamente de volta ao nome antigo, falhou, e o código foi restaurado.

### Movidos ou reclassificados

| Antes | Agora | Motivo |
|---|---|---|
| `ChamadoContratoApiIT.Auditoria` | `ObservabilidadeIT.Confirmadas`, com **as mesmas asserções de formato** | A classe antiga roda numa transação de teste que nunca é confirmada; com a auditoria depois do commit, ela não veria mais as linhas, corretamente |
| `DefeitosConhecidosIT.gaugeSoAtualizaNaLeituraDoDashboard` `[DEFEITO CONHECIDO OBS-01]` | `ObservabilidadeIT.deveAtualizarOGaugeNaLeituraDoDashboard` | Reclassificado (§3) |

A `DefeitosConhecidosIT` ficou só com o REG-03; o que não era mais usado nela foi removido.

### Preservados sem edição

`MetricasNegocioTest` (usa as constantes, então acompanhou o nome novo sozinho), `DashboardServiceTest`, `ChamadoServiceTest` (com os mocks de métricas), `ConcorrenciaChamadoIT` e todos os testes de contrato HTTP.

---

## 6. Melhorias opcionais fora do escopo

| Melhoria | O que exigiria |
|---|---|
| Gauges sempre atualizados | Revisar a ADR-018: `Gauge` com função de consulta (com cache) ou `@Scheduled`. Faz sentido quando houver scrape |
| Timer "por chamado" em vez de "por resolução" | Registrar só na primeira resolução, ou no fechamento; é decisão de semântica |
| Tag `categoria` estável | Usar o id (ou um código) no lugar do nome |
| OBS-03 (auditoria num componente único) | Um `Auditoria` com formato único (`acao`, `alvo`, `alvoId`, `perfil`); muda o formato de log fixado em teste |
| Auditoria persistida | Tabela `chamado_evento` (já prevista na §5.5 do `.ai/architecture.md`) |
| Percentis no timer | `publishPercentileHistogram()`; útil só com Prometheus de verdade |

---

## 7. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 287 ✓ | **291 ✓** (+4 e +2 novos, −1 movido, −1 reclassificado), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 86 ✓ | **86 ✓** |
| Cobertura de linhas, backend | 97,0% | **97,0%** |
| Cobertura de branches, backend | 75,9% | **76,1%** |
| `AposCommit` / `MetricasNegocio` | — | **100%** de linhas |
| Testes `[DEFEITO CONHECIDO]` restantes | 2 | **1** (REG-03, Fase 9) |

### Verificação no jar empacotado (perfil `dev`)

```
gauge por_status{ABERTO} antes do dashboard ............ 0.0   (ADR-018)
6 atendentes assumindo o chamado 9 ao mesmo tempo ...... 1x 200, 5x 422
  helpdesk_chamados_transicoes_total ABERTO->EM_ATEND.  0 -> 1.0  (delta 1, = numero de 200)
  linhas acao=ASSUMIR_CHAMADO chamadoId=9 ............. 1
gauge por_status{ABERTO} depois do dashboard ........... 3.0
# TYPE helpdesk_chamados_abertos_total counter
# TYPE helpdesk_chamados_transicoes_total counter
# TYPE helpdesk_chamados_por_status gauge
# TYPE helpdesk_chamados_fora_sla gauge
# TYPE helpdesk_tempo_resolucao_seconds summary          (+ _count, _sum, _max)
ocorrencias de "_segundos" no scrape ................... 0
linhas ERROR no log .................................... 0
```

Nesta rodada, os 5 perdedores leram o chamado **depois** do commit do vencedor e receberam 422, que é recusado antes de gravar. O caminho do 409, o que contava errado antes, é provado de forma determinística pelo `ObservabilidadeIT`, que não depende do escalonamento das threads.
