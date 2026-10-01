# Prompts Utilizados — Auditoria e Refatoração de Arquitetura

## HelpDesk Lite — Engenharia de Software 2.0 / FIAP

Este documento registra os prompts utilizados durante a atividade prática de Auditoria e Refatoração de Arquitetura do projeto HelpDesk Lite.

A condução foi incremental: primeiro foi realizado o diagnóstico técnico do sistema e estabelecida uma baseline de testes; em seguida, foi elaborado um plano de modernização e as alterações foram executadas em fases isoladas, sempre com validação. Após a refatoração, uma nova auditoria foi realizada, seguida de uma correção crítica pós-auditoria e da consolidação do relatório final.

Os textos abaixo preservam os prompts utilizados no processo. Instruções operacionais relacionadas a Git, branches, commits, push e permissões não fazem parte deste documento.

---

## Prompt 01 — Auditoria Técnica Inicial

> Realize uma auditoria técnica completa do projeto HelpDesk Lite antes de qualquer refatoração. Analise a estrutura e a arquitetura do sistema, identificando Code Smells, dívida técnica, duplicações, responsabilidades mal distribuídas, alto acoplamento, baixa coesão, código desnecessariamente complexo e possíveis violações dos princípios SOLID e de Orientação a Objetos. Verifique também se existem regras de negócio em camadas inadequadas, problemas de tratamento de exceções, pontos de segurança que mereçam atenção e divergências entre a arquitetura documentada e a implementação atual. Identifique métodos com complexidade ciclomática acima de 10 e, caso não existam, informe isso explicitamente. Para cada problema relevante, indique onde ele ocorre, sua evidência, impacto e uma possível direção de refatoração. Não altere o código nesta etapa; produza apenas o diagnóstico técnico que servirá de base para a modernização.

---

## Prompt 02 — Baseline de Testes de Caracterização

> Com base na auditoria técnica realizada no HelpDesk Lite, estabeleça uma baseline de testes de caracterização antes de iniciar a refatoração. Analise os testes existentes e identifique quais dos problemas e comportamentos críticos apontados na auditoria já estão adequadamente protegidos, priorizando permissões e perfis de usuários, transições de status dos chamados, atribuição de atendentes, tratamento de exceções, regras de domínio e demais áreas que serão afetadas pela modernização. Crie somente os testes adicionais necessários para proteger o comportamento atual e permitir que as próximas refatorações sejam realizadas com segurança. Não corrija ainda os problemas encontrados nem altere regras de negócio; quando o comportamento atual representar um defeito conhecido, documente-o claramente em vez de transformá-lo silenciosamente no comportamento esperado. Ao final, execute toda a suíte e apresente os testes adicionados, os comportamentos protegidos e o novo baseline obtido.

---

## Prompt 03 — Plano de Modernização e Priorização

> Com base na auditoria técnica e na baseline de testes de caracterização do HelpDesk Lite, elabore um plano estruturado de modernização antes de alterar o código de produção. Agrupe os problemas encontrados por áreas como arquitetura e SOLID, domínio, segurança, tratamento de exceções, persistência e performance, concorrência, frontend, observabilidade e legibilidade. Priorize os achados considerando severidade, impacto, risco de regressão e dependências entre as refatorações, definindo uma ordem incremental de execução. Para cada grupo, indique quais problemas serão tratados, quais técnicas de refatoração são adequadas e quais testes protegem a mudança. Diferencie correções necessárias de melhorias opcionais e evite propostas de novas funcionalidades, reescrita do sistema ou overengineering. O plano deve preservar o comportamento válido existente, ao mesmo tempo em que permita substituir progressivamente os testes de defeitos conhecidos por testes que representem o comportamento correto. Não altere código nesta etapa.

---

## Prompt 04 — Tratamento de Exceções

> Com base na auditoria e no plano de modernização do HelpDesk Lite, execute a Fase 1 referente ao tratamento de exceções. Corrija os casos identificados em que erros do cliente, como rota inexistente, método HTTP incorreto, Content-Type inválido e parâmetros de ordenação inválidos, acabam sendo tratados como erro interno 500. Revise o `GlobalExceptionHandler` e o tratamento atual para que cada situação produza um status HTTP semanticamente adequado, mantendo respostas de erro consistentes e sem exposição desnecessária de detalhes internos. Preserve os contratos válidos existentes e substitua os testes de defeitos conhecidos relacionados a esta fase por testes que representem o comportamento correto. Não faça refatorações arquiteturais ou alterações pertencentes às fases seguintes. Ao final, execute toda a suíte de testes e apresente os problemas corrigidos, alterações realizadas e resultados da validação.

---

## Prompt 05 — Regras de Negócio e Concorrência

> Execute a Fase 2 do plano de modernização do HelpDesk Lite, tratando os problemas identificados nas regras de negócio e no controle de concorrência. Corrija as situações em que o sistema pode ficar sem nenhum administrador e em que atendentes com chamados em andamento podem ser rebaixados ou desativados, preservando as invariantes do domínio sem adicionar novas funcionalidades de reatribuição. Implemente também o controle adequado de concorrência para impedir que dois atendentes assumam simultaneamente o mesmo chamado, utilizando uma estratégia compatível com a persistência e a arquitetura atuais. Considere ainda os demais achados classificados nesta fase pelo plano de modernização. Para cada correção, substitua o respectivo teste de defeito conhecido por testes que representem o comportamento correto, preservando os contratos válidos existentes. Execute toda a suíte após as alterações e apresente as regras corrigidas, a estratégia de concorrência adotada e os resultados da validação. Não avance para refatorações de domínio, arquitetura ou fases posteriores.

---

## Prompt 06 — Segurança e Configuração

> Execute a Fase 3 do plano de modernização do HelpDesk Lite, tratando exclusivamente os achados de segurança e configuração atribuídos a esta fase. Corrija a exposição indevida de dados de usuários, informações sensíveis presentes em logs e configurações inseguras relacionadas aos perfis da aplicação e aos endpoints de observabilidade. Garanta também que o perfil de produção não possa iniciar com a autenticação simulada por `X-User-Id` habilitada, preservando esse mecanismo nos ambientes em que ele faz parte do MVP. Não implemente Spring Security, JWT ou um novo mecanismo de autenticação, pois isso está fora do escopo da modernização. Revise os demais achados de segurança previstos para esta fase no plano e aplique somente as correções necessárias já identificadas. Substitua os respectivos testes de defeitos conhecidos por testes do comportamento correto e adicione testes específicos quando necessário. Ao final, execute toda a suíte e apresente os problemas corrigidos, as medidas adotadas, os testes realizados e eventuais riscos de segurança que permaneçam fora do escopo. Não avance para persistência, domínio, arquitetura ou outras fases posteriores.

---

## Prompt 07 — Persistência e Performance

> Execute a Fase 4 do plano de modernização do HelpDesk Lite, tratando exclusivamente os achados de persistência e performance atribuídos a esta fase. Corrija o problema de N+1 identificado na listagem de chamados e os demais problemas de acesso a dados previstos no plano, preservando paginação, filtros, ordenação, visibilidade por perfil e o contrato atual da API. Analise a causa de cada problema antes de escolher a solução e utilize os recursos adequados do JPA e dos repositories existentes, evitando carregamento excessivo de dados, consultas desnecessárias ou novas abstrações sem necessidade. Crie ou adapte testes que comprovem tanto o comportamento funcional quanto a redução das consultas quando aplicável. Compare quantitativamente o cenário antes e depois utilizando o mesmo conjunto de dados e condições da auditoria. Ao final, execute toda a suíte e apresente os achados tratados, a estratégia adotada, a quantidade de consultas antes e depois e os resultados da validação. Não avance para refatorações de domínio, SOLID, arquitetura ou fases posteriores.

---

## Prompt 08 — Domínio, OO e SOLID

> Execute a Fase 5 do plano de modernização do HelpDesk Lite, tratando exclusivamente os achados relacionados ao modelo de domínio, Orientação a Objetos e princípios SOLID. Refatore os problemas comprovados na auditoria, especialmente o modelo anêmico de `Chamado`, o uso de setters públicos para alterar estado e datas, a dispersão das regras de transição e as responsabilidades inadequadamente distribuídas. Busque encapsular invariantes e comportamentos no domínio, aumentar coesão e reduzir acoplamento, aplicando técnicas como encapsulamento, Extract Method, Move Method, Extract Class ou outras somente quando justificadas pelos achados do plano. Preserve os contratos externos e as regras de negócio já validadas, incluindo transições de status, atribuição, permissões, SLA, auditoria e controle de concorrência. Não transforme entidades JPA em Records nem aplique padrões ou abstrações apenas para demonstrar recursos modernos do Java. Execute as mudanças de forma incremental e valide continuamente com os testes existentes. Ao final, apresente os problemas tratados, as decisões de design adotadas, as responsabilidades movidas ou encapsuladas, os impactos sobre acoplamento e coesão e os resultados da suíte completa. Não avance para a refatoração da arquitetura em camadas da Fase 6.

---

## Prompt 09 — Arquitetura em Camadas e Desacoplamento

> Execute a Fase 6 do plano de modernização do HelpDesk Lite, tratando exclusivamente os achados relacionados à arquitetura em camadas e às dependências entre os módulos internos. Revise as dependências atuais entre `web`, `service`, `domain` e `repository`, corrigindo principalmente os casos em que a camada de aplicação/service depende de DTOs ou outros tipos pertencentes à camada web. Reorganize os contratos e mapeamentos necessários para que as dependências respeitem a direção arquitetural definida para o projeto, mantendo controllers responsáveis pela interface HTTP, services pela orquestração dos casos de uso, domínio pelas regras de negócio e repositories pela persistência. Preserve integralmente os contratos REST consumidos pelo frontend e evite criar novas camadas, interfaces ou abstrações que não resolvam um problema identificado na auditoria. Utilize os testes existentes para garantir que a reorganização não altere o comportamento funcional e, quando necessário, crie testes arquiteturais que impeçam a reintrodução das dependências indevidas. Ao final, apresente as violações corrigidas, a organização resultante das dependências, os impactos sobre acoplamento e os resultados da suíte completa. Não avance para frontend, observabilidade ou limpeza final.

---

## Prompt 10 — Frontend

> Execute a Fase 7 do plano de modernização do HelpDesk Lite, tratando exclusivamente os achados atribuídos ao frontend. Corrija os defeitos funcionais identificados na auditoria e ainda pendentes, incluindo o controle de ações repetidas por clique duplo, a atualização incorreta da lista de categorias após alterações de cadastro e a inconsistência do estado do usuário mantido no frontend. Revise também o método ou propriedade `acoes` de `ChamadoAcoes.vue`, identificado com complexidade ciclomática 12, e reduza sua complexidade quando isso puder ser feito sem alterar as regras de negócio ou duplicar ainda mais a máquina de estados do backend. Preserve o fluxo, as telas, os contratos REST e a experiência funcional já existente; não crie novas funcionalidades, novos fluxos de autenticação ou regras de negócio exclusivas no frontend. Substitua os respectivos testes de defeitos conhecidos por testes do comportamento correto e adicione testes apenas onde forem necessários para proteger as mudanças. Ao final, execute as suítes de frontend e backend, meça novamente a complexidade do ponto refatorado e apresente os problemas corrigidos, as decisões adotadas e os resultados da validação. Não avance para observabilidade ou limpeza e documentação final.

---

## Prompt 11 — Observabilidade

> Execute a Fase 8 do plano de modernização do HelpDesk Lite, tratando exclusivamente os achados relacionados à observabilidade. Revise o comportamento das métricas, logs e gauges identificados na auditoria e nas fases anteriores, corrigindo somente os problemas previstos no plano e preservando as decisões arquiteturais já estabelecidas para o MVP. Analise especificamente o OBS-01 à luz da ADR existente: caso o comportamento atual dos gauges seja intencional e coerente com a arquitetura, reclassifique o teste de defeito conhecido como teste de contrato, documentando tecnicamente a decisão em vez de alterar o sistema sem necessidade. Corrija também inconsistências comprovadas nas métricas, incluindo nomenclaturas inadequadas como unidades duplicadas, quando previsto nesta fase. Preserve os contratos funcionais e não introduza novas plataformas, serviços de monitoramento ou infraestrutura externa. Ao final, execute as suítes completas, verifique as métricas expostas pela aplicação e apresente o que foi corrigido, o que foi mantido por decisão arquitetural, os testes correspondentes e eventuais melhorias opcionais que permaneceram fora do escopo. Não avance para a limpeza e documentação final da Fase 9.

---

## Prompt 12 — Legibilidade, Limpeza e Documentação

> Execute a Fase 9 do plano de modernização do HelpDesk Lite, realizando a limpeza técnica e a consolidação da documentação após as refatorações. Trate os itens previstos para esta fase, incluindo o REG-03, corrigindo a documentação ou comentário que indica uma reativação de categoria inexistente sem criar uma nova funcionalidade; remova código morto identificado e simplifique trechos de baixa legibilidade somente quando houver justificativa registrada no plano. Revise também as divergências acumuladas entre a implementação atual, `docs/` e `.ai/`, atualizando a documentação para representar corretamente a arquitetura, regras de negócio, perfis, DTOs, tratamento de erros, métricas e demais decisões efetivamente implementadas. Corrija inconsistências documentais já registradas, como a exposição indevida de `UsuarioAtual` na documentação OpenAPI, sem alterar o contrato REST real. Não introduza novas funcionalidades, dependências, padrões arquiteturais ou refatorações estruturais fora do escopo já identificado. Ao final, execute novamente as suítes completas e verifique que não restam testes `[DEFEITO CONHECIDO]`, apresentando os itens removidos ou simplificados, as divergências documentais corrigidas e os resultados finais da validação.

---

## Prompt 13 — Auditoria Pós-Refatoração

> Realize uma auditoria técnica final do HelpDesk Lite após a conclusão das nove fases de modernização. Repita, sempre que possível, os mesmos critérios e medições utilizados na auditoria inicial para permitir uma comparação objetiva entre o estado anterior e o atual. Reavalie Code Smells, princípios SOLID, Orientação a Objetos, arquitetura em camadas, dependências entre pacotes, complexidade ciclomática, tratamento de exceções, regras de negócio, concorrência, segurança e configuração, persistência e quantidade de consultas, frontend, observabilidade, documentação e testes. Verifique especificamente o destino dos achados registrados na auditoria inicial, classificando cada um como corrigido, mitigado, reclassificado por decisão arquitetural, mantido como dívida técnica ou fora do escopo. Confirme também se as refatorações introduziram novas violações, regressões, duplicações ou abstrações desnecessárias. Utilize somente evidências e métricas efetivamente verificadas, sem estimar percentuais de melhoria. Não altere código nesta etapa. Ao final, produza um comparativo técnico Antes × Depois e liste claramente a dívida técnica residual que permanece no projeto.

---

## Prompt 14 — Correção Crítica Pós-Auditoria

> Com base na auditoria pós-refatoração, realize uma correção pontual do N-01, que demonstrou que a invariante de existência de ao menos um administrador ativo ainda pode ser violada por requisições concorrentes. Analise a causa da condição de corrida e implemente uma solução compatível com a arquitetura e a persistência atuais que torne essa invariante segura sob concorrência, sem alterar o comportamento funcional válido nem introduzir novas funcionalidades. Verifique também se a mesma condição de corrida pode comprometer a proteção criada para atendentes com chamados em andamento (REG-02) e, caso seja comprovadamente aplicável, trate-a dentro da mesma correção. Adicione testes concorrentes determinísticos que demonstrem a falha no comportamento anterior e garantam a invariante após a correção. Não aproveite esta etapa para corrigir outras dívidas técnicas encontradas na auditoria final. Ao término, execute as suítes completas e apresente a estratégia adotada, os testes realizados e os resultados obtidos. Atualize a classificação dos achados afetados no relatório de modernização e na auditoria final, sem alterar as demais dívidas residuais.

---

## Prompt 15 — Consolidação do Relatório Final de Modernização

> Consolide o `04-relatorio-modernizacao.md` como o Relatório de Modernização final do HelpDesk Lite, utilizando exclusivamente as evidências produzidas durante a auditoria inicial, baseline de testes, plano de modernização, execução das Fases 1 a 9, auditoria pós-refatoração e correção pós-auditoria do N-01. Estruture o documento para apresentar o estado inicial do sistema, metodologia adotada, principais problemas encontrados, decisões de modernização, refatorações realizadas e um comparativo técnico Antes × Depois baseado somente em métricas efetivamente medidas. Inclua os resultados finais após a correção pós-auditoria, considerando 302 testes de backend, 86 de frontend, ausência de funções com complexidade ciclomática acima de 10 e as classificações atualizadas dos 46 achados iniciais. Diferencie claramente problemas corrigidos, mitigados, reclassificados, mantidos como dívida técnica e itens fora do escopo. Registre também os problemas encontrados pela auditoria pós-refatoração e a correção posterior do N-01, deixando explícito que a modernização não eliminou toda a dívida técnica. Não invente percentuais de melhoria, benefícios ou métricas que não tenham sido medidos e não altere código de produção. O relatório deve ser autocontido, técnico e adequado como entregável acadêmico da atividade de Auditoria e Refatoração de Arquitetura.

---

