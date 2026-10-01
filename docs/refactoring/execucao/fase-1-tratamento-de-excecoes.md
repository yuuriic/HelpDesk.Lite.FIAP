# Fase 1 — Tratamento de exceções (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §5](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix`: muda comportamento; os testes de defeito foram substituídos por testes de contrato |

---

## 1. Achados corrigidos

| ID | Antes | Depois |
|---|---|---|
| **EXC-01** | Rota inexistente, método não mapeado, `Content-Type` inválido e `sort` por campo inexistente → **500** `ERRO_INTERNO`, com log ERROR e stack trace | **404** `RECURSO_NAO_ENCONTRADO` · **405** `METODO_NAO_SUPORTADO` (com header `Allow`) · **415** `MIDIA_NAO_SUPORTADA` (com header `Accept`) · **400** `VALIDACAO` ("Campo de ordenacao invalido: x"). Todos com log WARN, sem stack trace |
| **CON-02** (parte da fase 1) | `DataIntegrityViolationException` → 500, com a mensagem do banco (constraint e valor) no log | **409** `CONFLITO` com mensagem genérica. A mensagem do banco não vai para o corpo nem para o log |
| — (preparação do CON-01) | `OptimisticLockingFailureException` → 500 | **409** `CONFLITO`. Pronto para quando o `@Version` entrar, na fase 2 |
| **REG-04** | Login de usuário inativo → 422 `REGRA_NEGOCIO`; inexistente → 404 | **401** `NAO_AUTENTICADO` nos dois casos, igual ao filter (regra I9) |
| **SEG-06** | O 401 dizia "Usuario 999 nao encontrado" ou "Usuario 7 esta inativo" | Mensagem única: "Usuario invalido ou inativo". O motivo real continua no log WARN |

**Defeito encontrado e corrigido durante a execução:** no primeiro desenho do handler, um 5xx vindo da classe base (por exemplo, 503 por timeout assíncrono) devolvia `status: 500` no corpo com HTTP 503. O teste `GlobalExceptionHandlerTest` pegou o problema antes do fim da fase, e agora o corpo sempre acompanha o status HTTP.

---

## 2. Alterações

### Código de produção

| Arquivo | Mudança |
|---|---|
| [GlobalExceptionHandler.java](../../../backend/src/main/java/br/com/helpdesk/web/exception/GlobalExceptionHandler.java) | Passa a estender `ResponseEntityExceptionHandler`. `handleExceptionInternal` é o ponto único que monta o `ErroResponse` pelo status e aplica a regra de log (4xx WARN, 5xx ERROR). Os handlers antigos de validação, JSON malformado, tipo errado e parâmetro ausente foram absorvidos pela classe base, **com corpo e log idênticos**. Novos handlers: `PropertyReferenceException` (400), `DataIntegrityViolationException` e `OptimisticLockingFailureException` (409), `NaoAutenticadoException` (401). Saiu o handler morto de `NoHandlerFoundException`. O 405 não duplica mais o WARN do logger `PageNotFound` |
| [NaoAutenticadoException.java](../../../backend/src/main/java/br/com/helpdesk/domain/exception/NaoAutenticadoException.java) | **Nova.** Exceção de domínio → 401, com a mensagem pública única (`MENSAGEM_PADRAO`) usada pelo login e pelo filter |
| [UsuarioService.java](../../../backend/src/main/java/br/com/helpdesk/service/UsuarioService.java) | `autenticar` lança `NaoAutenticadoException` para inexistente e para inativo |
| [UsuarioAtualFilter.java](../../../backend/src/main/java/br/com/helpdesk/web/filter/UsuarioAtualFilter.java) | O 401 separa a mensagem pública (genérica) do detalhe de log |

### Documentação do contrato de erros

Atualizada junto com a correção, porque a fase criou códigos novos:
- [.ai/business-rules.md](../../../.ai/business-rules.md), tabela de códigos, que é a fonte que prevalece;
- [docs/MVP.md](../../MVP.md), tabela de códigos.

A §6 de `docs/ARQUITETURA.md` fica para a Fase 9 (DOC-08), como prevê o plano.

### Decisões tomadas

- **Sem validação de `sort` no controller:** o `sort` inválido lança `PropertyReferenceException` antes de chegar ao banco (confirmado no log da baseline), então basta mapear essa exceção. A lista branca de campos (SEG-07) continua opcional na fase 3.
- **Sem código dedicado para 406:** quando o cliente não aceita JSON, o próprio corpo de erro em JSON não pode ser negociado, e um ramo dedicado seria inalcançável. Os 4xx sem código próprio usam `REQUISICAO_INVALIDA`.
- **Ordem preservada:** o filter continua autenticando antes da resolução de rota. `GET /api/nao-existe` sem `X-User-Id` responde 401, não 404.
- **Fora desta fase:** EXC-04 (`Clock` no `ErroResponse`, opcional), EXC-02 (hierarquia de exceções, fase 6) e qualquer mudança de arquitetura.

---

## 3. Testes

### Testes de defeito substituídos (8 removidos de `DefeitosConhecidosIT`)

Antes de apagá-los, os 8 foram executados contra o código corrigido e **todos falharam** mostrando os novos status (415, 405, 404, 400, 401). Isso prova que detectavam o defeito.

| Removido | Substituído por |
|---|---|
| `Exc01.rotaInexistenteDevolve500` | `ErrosHttpApiIT.deveDevolver404ParaRotaInexistente`, `deveDevolver404ForaDaApi` |
| `Exc01.metodoNaoSuportadoDevolve500` | `ErrosHttpApiIT.deveDevolver405ComAllow` |
| `Exc01.contentTypeNaoSuportadoDevolve500` | `ErrosHttpApiIT.deveDevolver415` |
| `Exc01.sortInvalidoDevolve500` | `ErrosHttpApiIT.deveDevolver400ParaOrdenacaoInvalida` |
| `Exc01.erroDeClienteELogadoComoErro` | `ErrosHttpApiIT.deveLogarErroDeClienteEmWarn` |
| `Reg04.loginDeInativoDevolve422` | `ErrosHttpApiIT.deveRecusarLoginDeInativoCom401` |
| `Reg04.loginDeInexistenteDevolve404` | `ErrosHttpApiIT.deveRecusarLoginDeInexistenteCom401` |
| `Reg04.mensagemDo401RevelaExistencia` | `ErrosHttpApiIT.deveResponderIgualParaInexistenteEInativoNoFilter`, `deveManterOMotivoNoLog` |

### Testes novos (19)

| Arquivo | Testes | O que garante |
|---|:---:|---|
| [ErrosHttpApiIT.java](../../../backend/src/test/java/br/com/helpdesk/integration/ErrosHttpApiIT.java) | 13 | Pilha real: os status acima, os headers `Allow` e `Accept`, o corpo com **exatamente** os 4 campos padrão e sem detalhe interno, log WARN sem ERROR, ordenações válidas (inclusive aninhadas) funcionando, autenticação antes da rota, login ativo funcionando |
| [TratamentoDeErrosWebTest.java](../../../backend/src/test/java/br/com/helpdesk/web/TratamentoDeErrosWebTest.java) | 3 | 409 para integridade (sem constraint nem e-mail no corpo e no log), 409 para conflito de versão, 500 genérico sem a mensagem original |
| [GlobalExceptionHandlerTest.java](../../../backend/src/test/java/br/com/helpdesk/web/GlobalExceptionHandlerTest.java) | 3 | Ramos que a API não provoca: 5xx da classe base com o status do corpo igual ao HTTP, 4xx genérico e requisição que não é servlet |

### Testes existentes adaptados (2, conforme o protocolo do plano)

- `UsuarioServiceTest.deveRecusarLoginDeUsuarioInativo` e `deveRecusarLoginDeUsuarioInexistente`: passam a esperar `NaoAutenticadoException` com a mensagem única. Os dois fixavam o REG-04 sem rótulo (documento 02, §5).
- `ChamadoControllerTest.deveDevolver401QuandoOUsuarioEstaInativo` **não precisou mudar**: a mensagem nova ainda contém "inativo".

### Contratos preservados, sem edição

`ChamadoControllerTest` (400 de validação com `campos`, JSON malformado, tipo errado, 401, 403, 404, 422), `CadastrosApiIT`, `ChamadoContratoApiIT`, `CicloDeVidaChamadoIT` e todos os testes unitários de domínio.

---

## 4. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Backend (`./mvnw verify`) | 202 ✓ | **213 ✓** (−8 de defeito, +19 de contrato), 0 falhas, gate do JaCoCo aprovado |
| Frontend (`npm test`) | 71 ✓ | **71 ✓** (sem mudança) |
| Cobertura de linhas, backend | 95,4% | **95,5%** |
| Cobertura de branches, backend | 67,1% | **69,3%** |
| `GlobalExceptionHandler` | — | **100%** de linhas e de branches |
| domain + service | 95,0% | 94,9%\* |
| Testes `[DEFEITO CONHECIDO]` restantes | 21 | **13** (9 no backend, 4 no frontend) |

\* A variação é aritmética: saíram 4 linhas cobertas do `UsuarioService` (o `if` antigo do login), e as linhas sem cobertura são as mesmas da baseline (código morto e `equals` das entidades, itens do DOM-05).

### Verificação no jar empacotado (`java -jar`, perfil `dev`)

```
GET    /api/nao-existe               -> 404 RECURSO_NAO_ENCONTRADO
DELETE /api/chamados/1               -> 405 METODO_NAO_SUPORTADO   Allow: GET
POST   /api/chamados (text/plain)    -> 415 MIDIA_NAO_SUPORTADA    Accept: application/json, application/*+json
GET    /api/chamados?sort=naoExiste  -> 400 VALIDACAO "Campo de ordenacao invalido: naoExiste"
GET    /api/chamados?sort=categoria.nome,asc -> 200
POST   /api/auth/login {999}         -> 401 "Usuario invalido ou inativo"
GET    /api/chamados  X-User-Id: 999 -> 401 "Usuario invalido ou inativo"
POST   /api/chamados  '{x'           -> 400 VALIDACAO (contrato anterior mantido)
linhas ERROR no log: 0
```
