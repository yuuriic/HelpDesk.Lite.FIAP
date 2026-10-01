# Fase 4 — Persistência e performance (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §8](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix`/`perf`: sem mudança de contrato. `ChamadoContratoApiIT` (formato e ordem do JSON), `ChamadoSpecificationTest` (visibilidade e `count`) e `ErrosHttpApiIT` passaram **sem edição** |
| Tratados | **CON-03** (necessária), **CON-06** (achado novo desta fase, mesma causa do CON-03), **REG-05** e **CON-05** (opcionais: consulta desnecessária e sequências do seed) |
| Analisado e **não** implementado | **CON-04**, opcional. Justificativa na §4 |

---

## 1. Método de medição

Mesmas condições da auditoria, antes e depois:
- jar empacotado, perfil `dev`, seed do `data.sql` (8 chamados, 3 usuários, 4 categorias, 6 comentários);
- SQL contado pelo log do Hibernate (`org.hibernate.SQL=DEBUG`), filtrado pelo `X-Request-Id` de cada requisição, que o `RequestIdFilter` põe no MDC;
- o script é o mesmo nas duas rodadas;
- antes das medições, um comentário da Carla (admin) é incluído no chamado 4, para o detalhe ter um autor que não é o solicitante nem o atendente.

A medição "antes" foi feita no jar da Fase 3, antes de qualquer mudança desta fase. **O L1 reproduz exatamente a auditoria (9 consultas).**

---

## 2. Consultas por requisição: antes × depois

| # | Requisição | Antes | Depois | Achado |
|---|---|:---:|:---:|---|
| L1 | `GET /api/chamados?size=20` (admin): **o cenário da auditoria** | **9** | **2** | CON-03 |
| L2 | `GET /api/chamados` (solicitante; visibilidade no `WHERE`) | 7 | 2 | CON-03 |
| L3 | `GET /api/chamados?status=EM_ATENDIMENTO` | 7 | 2 | CON-03 |
| L4 | `GET /api/chamados?sort=categoria.nome,asc` | 9 | 2 | CON-03 |
| L5 | `GET /api/chamados?size=3&page=1` (página cheia, com `count`) | 8 | 3 | CON-03 |
| D1 | `GET /api/chamados/4` (4 comentários, 3 autores) | 4 | 3 | CON-06 |
| D2 | `GET /api/chamados/4/comentarios` | 4 | 3 | CON-06 |
| A1 | `PATCH /api/chamados/1/assumir` (sem comentários) | 4 | 4 | — (inalterado) |
| A2 | `PATCH /api/chamados/4/status` → RESOLVIDO (4 comentários) | 5 | 4 | CON-06 |
| R1 | `GET /api/dashboard/resumo` | 2 | 2 | — (já era uma consulta com join; ver CON-04) |
| C1 | `DELETE /api/categorias/4` | 4 | 3 | REG-05 |

Em todas as contagens, 1 consulta é do `UsuarioAtualFilter`, que carrega o usuário do `X-User-Id`. Esse custo é inerente ao mecanismo de autenticação e está fora do escopo.

### O que o seed não mostra: o crescimento

O seed é pequeno e repete categorias e usuários, então o "antes" do L1 ficou em 9. O custo real do N+1 cresce com o número de entidades **distintas** na página. Os testes medem isso com dados construídos para o pior caso:

| Cenário (`ConsultasPorRequisicaoIT`) | Antes (calculado) | Depois (medido) |
|---|:---:|:---:|
| 3 chamados, categoria e solicitante distintos (o cenário do antigo teste de defeito) | **8** (medido na baseline) | **2** |
| 20 chamados, 60 relacionados distintos, página cheia | 1 + 1 + 1 (`count`) + 60 = **63** | **3** |
| Detalhe com 6 comentários de 6 autores distintos | 3 + 6 = **9** | **3** |

**Depois da correção, a contagem é constante:** não depende do tamanho da página nem do número de autores.

---

## 3. Achados tratados: causa e estratégia

### CON-03: N+1 na listagem de chamados

- **Causa, confirmada no SQL:** o `ChamadoService.listar` chama o `findAll(Specification, Pageable)` herdado de `JpaSpecificationExecutor`, que traz só a tabela `chamado`. As três associações são `@ManyToOne(fetch = LAZY)`, e o `ChamadoMapper.paraResumo` lê `categoria`, `solicitante` e `atendente` de cada item, então cada entidade distinta vira um SELECT. O usuário do filter também é recarregado, porque o filter roda fora da transação do service (`open-in-view: false`).
- **Estratégia:** sobrescrever o próprio `findAll(Specification, Pageable)` no `ChamadoRepository` existente, com `@EntityGraph(attributePaths = {"categoria", "solicitante", "atendente"})`. É o mesmo recurso que o repositório já usava no detalhe (`findWithRelacionamentosById`) e no dashboard.
- **Por que é seguro:**
  - As três associações são para-um, então o join não multiplica linhas: `offset`/`fetch first` continuam no SQL, sem o alerta HHH90003004 de paginação em memória (verificado: 0 ocorrências no log).
  - O `count` da paginação é gerado pelo Spring Data **sem** o grafo e continua sem joins (verificado no SQL do L5).
  - A `Specification` (filtros e visibilidade do solicitante no `WHERE`) não mudou.
  - A ordenação por `categoria.nome` reaproveita o join do grafo, sem um segundo join.
- **Alternativas descartadas:** projeção/DTO por JPQL (duplicaria o mapper e a `Specification`); `@BatchSize` ou `default_batch_fetch_size` (reduz para algumas consultas, mas continua variável e muda o comportamento global de *fetch*); consulta nova com `join fetch` manual (duplicaria o método que já existe).

### CON-06: autor de comentário carregado um a um (achado novo)

- **Causa:** o mesmo padrão do CON-03, que a auditoria não tinha listado. O `findAllByChamadoIdOrderByCriadoEmAsc` traz os comentários sem o autor, e o `ComentarioMapper` lê `comentario.getAutor()`. Autores que já estão no contexto, por serem o solicitante ou o atendente do chamado, não custam nada; **cada outro autor custa um SELECT**. O problema aparece no detalhe, na listagem de comentários e em toda resposta de `assumir`/`alterarStatus`, que devolve o detalhe.
- **Estratégia:** `@EntityGraph(attributePaths = "autor")` no método de consulta existente do `ComentarioRepository`.
- **Por que entrou nesta fase:** é um achado de persistência com a mesma causa e a mesma correção do CON-03, e deixá-lo seria entregar a fase com um N+1 conhecido. Ele não estava na auditoria e fica registrado aqui como achado novo.

### REG-05: consulta feita só para o log

- **Causa:** o `CategoriaService.desativar` executava `chamadoRepository.existsByCategoriaId(id)` apenas para preencher `possuiChamados=` na linha de log. O soft delete não depende disso (regra I5).
- **Estratégia:** *Inline/Remove*. Saiu a consulta; com ela, o método `existsByCategoriaId`, que não tinha outro uso, e a dependência de `ChamadoRepository` no `CategoriaService`. A linha de auditoria continua com `acao`, `categoriaId` e `perfil`.

---

## 4. Achados opcionais analisados: CON-04 (não implementado) e CON-05 (implementado)

### CON-04: o dashboard lê a base inteira (opcional)

- **O que a medição mostra:** o dashboard **não tem N+1**: são 2 consultas, a do filter e uma com os três joins, com 8 ou com 10 chamados. O custo dele é de **memória e tempo O(n)**: cada leitura traz todas as linhas de `chamado` para contar status e prioridade e avaliar o SLA em Java.
- **Por que não agora:**
  1. **Evitaria carregamento, mas duplicaria regra.** As contagens por status e prioridade iriam facilmente para um `GROUP BY`. Porém a lista "fora do SLA" e o tempo médio dependem do `SlaCalculator`, a regra da §8.3 com o `Clock` injetado. Levar essa regra para SQL (`criado_em + sla_horas < :agora OR resolvido_em > prazo`) criaria uma segunda fonte da mesma regra, que é exatamente o tipo de problema que as Fases 5 e 6 querem eliminar.
  2. **O volume do MVP não justifica.** O plano condiciona o CON-04 a "haver volume que justifique", e hoje a base tem 8 chamados.
- **O que ficou garantido:** `ConsultasPorRequisicaoIT.deveManterODashboardEmDuasConsultas` fixa as 2 consultas, então uma mudança futura não reintroduz consultas por chamado.
- **Direção quando houver volume:** fazer as contagens por `GROUP BY` e manter a avaliação de SLA no domínio, limitada aos chamados que ainda podem estar fora do prazo.

### CON-05: `RESTART WITH` fixo no seed (opcional), **implementado**

- **Situação:** o `data.sql` insere ids explícitos, o que não avança as colunas IDENTITY, e reposicionava cada sequência com um número fixo (4, 5, 9, 7). Incluir uma linha no seed e esquecer de atualizar o número deixava a sequência atrás do seed, e o primeiro cadastro pela interface falhava com violação de chave primária. Isso foi reproduzido no H2 do projeto: `Unique index or primary key violation`.
- **Correção:** `ALTER TABLE ... ALTER COLUMN id RESTART WITH (SELECT MAX(id) + 1 FROM ...)`, nas quatro tabelas.
- **Correção de uma afirmação minha:** a primeira versão deste registro dizia que o `RESTART WITH` do H2 só aceitava um número literal e adiava o achado por isso. **Testei antes de manter a afirmação, e ela estava errada:** o H2 2.3.232 aceita a expressão e reposicionou a sequência corretamente. Com a correção simples disponível, a sugerida no próprio plano, o achado foi tratado.
- **Teste:** [SeedDemonstracaoIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/configuracao/SeedDemonstracaoIT.java). No perfil `dev` com o seed, criar usuário, categoria, chamado e comentário pela API responde 201, com ids depois dos do seed. Ele reaproveita o contexto do `ConfiguracaoDevIT`.

---

## 5. Testes

### Teste de defeito substituído (1)

| Removido de `DefeitosConhecidosIT` | Substituído por |
|---|---|
| `listagemFazNMaisUm` (exigia **8** consultas; contra o código novo falhou com **2**) | `ConsultasPorRequisicaoIT.Listagem.deveListarOCenarioDaAuditoriaEmDuasConsultas` (mesmo cenário, **2**) |

Sem esse teste, a `DefeitosConhecidosIT` não precisa mais das estatísticas do Hibernate e volta a usar o contexto Spring compartilhado com as outras suítes.

### Testes novos: [ConsultasPorRequisicaoIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/ConsultasPorRequisicaoIT.java) (11)

| Grupo | Testes | Contagem garantida |
|---|:---:|---|
| Listagem (CON-03) | 6 | 3 chamados → 2; 20 chamados com 60 relacionados distintos → 3 (página cheia + `count`); página 2 de 3 → 3; solicitante → 2, só os dele; filtro + `sort=categoria.nome` → 2; `meus=true` → 2 |
| Comentários (CON-06) | 3 | Detalhe com 1 e com 6 autores → 3 nos dois casos; `GET /comentarios` com 5 autores → 3; alterar status com 5 autores → 3 + 1 UPDATE |
| REG-05 | 1 | Desativar categoria → usuário + categoria + UPDATE = 3 |
| Dashboard | 1 | 10 chamados → 2 (fixa o comportamento que já era bom) |

Cada teste confere também o resultado funcional (`totalElements`, tamanho da página, nomes das associações no JSON, número de comentários), não só a contagem.

**Uma premissa minha estava errada e foi corrigida:** eu esperava 2 consultas para 20 chamados com `size=20`, mas a página cheia faz o Spring Data rodar o `count`. O valor correto é 3, e ele é constante.

### Testes existentes adaptados (1)

`CategoriaServiceTest`: o construtor não recebe mais `ChamadoRepository`, e o `deveDesativarEmVezDeExcluir` perdeu o *stub* de `existsByCategoriaId`, que o Mockito estrito acusaria como não usado. A intenção do teste, "categoria com chamados nunca é removida, só desativada", continua a mesma.

---

## 6. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 251 ✓ | **262 ✓** (−1 de defeito, +12), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 71 ✓ | **71 ✓** |
| Cobertura de linhas, backend | 95,7% | **95,7%** |
| Cobertura de branches, backend | 72,6% | **72,6%** |
| domain + service | 95,0% | **95,1%** |
| Testes `[DEFEITO CONHECIDO]` restantes | 7 | **6** (backend: REG-03, OBS-01; frontend: EXC-03, FE-04, FE-02 ×2) |
