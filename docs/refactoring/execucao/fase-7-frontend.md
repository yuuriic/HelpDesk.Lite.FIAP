# Fase 7 — Frontend (registro de execução)

| Item | Valor |
|---|---|
| Plano | [03-plano-modernizacao.md §11](../03-plano-modernizacao.md) |
| Branch | `refactoring` (a `main` continua em `c5f4335`) |
| Natureza | `fix` (FE-02, FE-04, EXC-03, SEG-08) + `refactor` (FE-01) |
| Tratados | **FE-01**, **FE-02**, **FE-04**, **EXC-03** (necessários); **SEG-08** (opcional, pedido nesta etapa como "inconsistência do estado do usuário") |
| Não tratados, de propósito | FE-03, FE-05, FE-06, FE-07, TST-03 (opcionais; §4) |
| Backend | **Não foi alterado.** Os contratos REST continuam os mesmos |

---

## 1. Problemas corrigidos

### FE-02: clique duplo disparava a ação duas vezes

- **Causa:** o `ChamadoAcoes` guardava um `processando` próprio e o zerava no `finally` logo depois do `emit`. Como o `emit` não espera o handler assíncrono do pai, os botões nunca travavam durante a requisição.
- **Correção:** o estado passa a vir de **quem faz a requisição**.
  - A `ChamadoDetalhePage` mantém `processandoAcao`, liga o estado antes do `await` e desliga no `finally`. Ela também ignora uma segunda ação enquanto a primeira está em voo.
  - O componente recebe `processando` como prop: trava todos os botões e mostra o loading só no botão clicado.
- **Prova:** o teste da página (`ChamadoDetalhePage.spec`) dá dois cliques em "Assumir" com a requisição pendente e espera **1** PATCH. **Teste de mutação:** sem a ligação `:processando` na página, ele falha com *"expected length 1 but got 2"*.
- **Observação honesta:** os dois testes `[DEFEITO CONHECIDO FE-02]` antigos **continuaram passando** depois da mudança no componente. Eles montavam o componente sozinho, sem um pai controlando o estado, e o defeito estava justamente na conversa pai ↔ filho. Por isso a prova da correção é o teste da página real, e eles foram substituídos.

### FE-04: lista de categorias com dois significados

- **Causa:** o `catalogoStore` tinha uma única `categorias`, que o formulário de chamado carregava "só ativas" e a tela de administração recarregava "com inativas" depois de cada salvamento. Prevalecia o último a carregar. Um único `carregando` também era compartilhado entre categorias e usuários.
- **Correção:** *Split Variable*.
  - `categorias` (só ativas: formulário e filtro da listagem) e `todasCategorias` (administração), com `carregarCategorias()` e `carregarTodasCategorias()`;
  - salvar ou desativar recarrega só `todasCategorias`;
  - `carregandoCategorias` e `carregandoUsuarios` separados.
- **Telas ajustadas:** a `CategoriasPage` usa `todasCategorias`; o `:loading` de cada tela aponta para o próprio recurso. Nenhuma tela mudou visualmente.

### EXC-03: estado do usuário e erros fora de controle

- **401 não encerrava a sessão:** um usuário desativado continuava "logado", recebendo notificações de erro. Agora o `api.js` avisa, por um callback registrado, quando **alguém logado** recebe 401. O novo `boot/sessao.js` encerra a sessão (`auth.sair()`) e manda para `/login`, guardando a rota atual em `destino`, como o guard já fazia.
  - Um 401 sem ninguém logado, que é um login recusado, não dispara nada.
  - 403, 404, 422 e 500 também não: a sessão continua válida.
  - O callback evita que o `api.js` importe o store e o router, o que geraria dependência circular.
- **Erros escapando dos handlers:** 6 handlers de página faziam `await` sem tratar o erro, e ele virava *unhandled rejection* no console (assumir, alterar status, comentar, entrar, salvar e desativar nos cadastros). Todos passaram a usar **o mesmo padrão que as páginas já usavam ao carregar dados**: o interceptor já notificou, então a página só absorve o erro e mantém o estado (diálogo aberto, texto no campo, botão destravado). Não foi criado helper novo.

### SEG-08: perfil desatualizado no `localStorage`

- **Causa:** a sessão salva era usada como estava. Se o admin mudasse o perfil de alguém, o menu e o guard dessa pessoa continuavam com o perfil antigo até um novo login (o backend já recusava as ações).
- **Correção:** ao abrir o app, o `boot/sessao.js` chama `auth.revalidarSessao()`, que usa o **mesmo `POST /auth/login` que a tela de login já usa**, com o id guardado, e atualiza nome e perfil.
  - Se a rota atual não for mais permitida para o perfil novo, aplica **a mesma regra do guard do router** e manda para `/chamados`.
  - Se o usuário foi desativado, o 401 encerra a sessão pelo caminho do EXC-03.
  - Sem backend, a sessão fica como estava. A conferência não trava a abertura do app.
- **Não é fluxo novo de autenticação:** nenhuma rota, tela ou credencial nova; é a mesma chamada de login, feita mais uma vez ao abrir o app.

---

## 2. FE-01: `acoes` do `ChamadoAcoes.vue`

### Antes: CCN 12

Três `if` compostos com `&&` e `||` codificavam à mão as arestas da máquina de estados e as regras de relação.

### Depois: CCN 3

```js
const ARESTAS = [           // mesma ordem e mesmos perfis da TransicaoStatus.TABELA do backend
  { de: 'ABERTO',         para: 'EM_ATENDIMENTO', perfis: ['ATENDENTE', 'ADMIN'],   evento: 'assumir', ... },
  { de: 'EM_ATENDIMENTO', para: 'RESOLVIDO',      perfis: ['ATENDENTE', 'ADMIN'],   evento: 'alterar-status', ... },
  { de: 'RESOLVIDO',      para: 'FECHADO',        perfis: ['SOLICITANTE', 'ADMIN'], evento: 'alterar-status', ... },
  { de: 'RESOLVIDO',      para: 'EM_ATENDIMENTO', perfis: ['SOLICITANTE', 'ADMIN'], evento: 'alterar-status', ... }
]
const RELACAO_PARA_ALTERAR_STATUS = {   // mesma regra do PermissaoService.verificarAlteracaoStatus
  ADMIN: () => true, ATENDENTE: (c, id) => ..., SOLICITANTE: (c, id) => ...
}
const acoes = computed(() => ARESTAS.filter(a =>
  a.de === props.chamado?.status && a.perfis.includes(props.perfil) && cumpreRelacao(a)))
```

### Decisões

- **Não duplica mais do que já existia.** O frontend já reimplementava a regra; ela agora é **um espelho linha a linha** da `TransicaoStatus.TABELA` (as mesmas 4 arestas, na mesma ordem, com os mesmos perfis). A relação por perfil tem o mesmo formato do `switch` do `PermissaoService`. Os dois comentários apontam para a fonte no backend.
- **Por que não `acoesPermitidas` na API:** eliminaria a duplicação, mas mudaria o contrato REST e acrescentaria um campo à API, e a etapa pede para preservar os contratos. Fica registrado como alternativa (plano, §14).
- **Mesmas regras, provado:** os **18 testes** de `ChamadoAcoes.spec` (a matriz perfil × status × relação, criada na baseline) passaram **sem nenhuma edição**.

### Complexidade, medida de novo com o `lizard` (mesma ferramenta da auditoria)

| Ponto | Antes | Depois |
|---|:---:|:---:|
| `ChamadoAcoes.vue`, computed `acoes` | **12** | **3** |
| Maior função do `ChamadoAcoes.vue` | 12 | 3 (`acoes`, `cumpreRelacao`, `executar`) |
| Funções com CCN > 10 no projeto (backend + frontend) | **1** | **0** |
| Interceptor de erro do `api.js` | 5 | 8 (a condição do 401 com sessão) |

O maior valor restante no projeto é o `listar` de `services/chamados.js`, com CCN 10, que só usa `||`/`??` para valores padrão e já estava assim na auditoria.

---

## 3. Testes

### Testes de defeito substituídos (todos os 4 do frontend)

| Removido | Substituído por |
|---|---|
| `[DEFEITO CONHECIDO FE-02]` ×2 (`ChamadoAcoesProcessando.spec`) | `ChamadoDetalhePage.spec` (clique duplo de ponta a ponta) + `ChamadoAcoesProcessando.spec` reescrito (contrato da prop `processando`) |
| `[DEFEITO CONHECIDO FE-04]` (`catalogoStore.spec`) | "salvar ou desativar no admin não põe categorias inativas na lista do formulário" |
| `[DEFEITO CONHECIDO EXC-03]` (`api.spec`) | grupo "401 com sessão" (`api.spec`) + `sessaoBoot.spec` |

**Não resta nenhum teste `[DEFEITO CONHECIDO]` no frontend.**

### Testes novos e adaptados

| Arquivo | O que garante |
|---|---|
| [ChamadoDetalhePage.spec.js](../../../frontend/src/__tests__/ChamadoDetalhePage.spec.js) (novo, 2) | Página real com `ChamadoAcoes`, Pinia e store de verdade: clique duplo → 1 PATCH com os botões travados até a resposta; ação que falha destrava e **não deixa rejeição sem tratamento** (o Vitest reprova se deixar) |
| [ChamadoAcoesProcessando.spec.js](../../../frontend/src/__tests__/ChamadoAcoesProcessando.spec.js) (reescrito, 4) | Livre sem processamento; travado e sem emitir enquanto o pai processa; loading no botão clicado; `alterar-status` emite o destino da aresta |
| [sessaoBoot.spec.js](../../../frontend/src/__tests__/sessaoBoot.spec.js) (novo, 6) | 401 com sessão → sai e vai ao login com `destino`; já no login, não redireciona de novo; perfil rebaixado sai da rota proibida; perfil igual fica; sem backend a sessão fica; sem sessão não chama o backend |
| `api.spec.js` (+3, −1 defeito) | O aviso só vale para 401 com alguém logado |
| `authStore.spec.js` (+2) | `revalidarSessao` atualiza o perfil e o `localStorage`; sem sessão não chama o backend |
| `catalogoStore.spec.js` (+1, 1 adaptado) | Listas separadas; carregamento de usuários e de categorias independentes |
| `setup.js` | Stub de `ResizeObserver` (o jsdom não tem; o `QLayout` usa), ao lado do stub de `matchMedia` que já existia |

**Contrato preservado sem edição:** `ChamadoAcoes.spec` (18), `chamadosStore.spec` (11), `SeletorTema.spec` (11) e os testes originais do `authStore.spec`.

---

## 4. Não tratados (opcionais) e por quê

| Achado | Motivo |
|---|---|
| FE-03 (constantes e formatação duplicadas) | É duplicação de apresentação, sem defeito funcional. Fica para a limpeza final (Fase 9), que esta etapa não inclui |
| FE-05 (`$api` sem uso; *fallback* da URL duplicado) | Código morto. É limpeza (Fase 9) |
| FE-06 (lista recebe o objeto de detalhe inteiro) | Os campos extras na linha da lista não aparecem nem quebram nada |
| FE-07 (toggle "Somente os meus" sem efeito para o solicitante) | Escondê-lo **muda a tela**, e a etapa pede para preservar as telas |
| TST-03 (cobertura no frontend) | Exigiria uma dependência nova (`@vitest/coverage-v8`) |

---

## 5. Validação

| Suíte | Antes da fase | Depois |
|---|:---:|:---:|
| Frontend (`npm test`) | 71 ✓ | **86 ✓** (9 arquivos), 0 falhas |
| Build de produção (`quasar build`) | — | **Build succeeded**; o `boot/sessao` está no bundle |
| Backend (`./mvnw verify`) | 287 ✓ | **287 ✓** (sem alterações) |
| Testes `[DEFEITO CONHECIDO]` restantes | 6 | **2** (backend: REG-03 e OBS-01, das Fases 8/9) |
