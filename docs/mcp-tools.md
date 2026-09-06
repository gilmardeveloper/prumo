# Tools MCP

**Português (Brasil)** · [English](mcp-tools.en.md)

Toda tool do Prumo segue o mesmo contrato:

1. contexto resolvido só pelo `CurrentWorkspaceContextService`;
2. motor de políticas consultado antes de agir;
3. endereçamento por `repositoryId` / `datasourceId` / `packId` mais um caminho relativo — **nunca um
   caminho absoluto vindo do cliente**;
4. saída estruturada, sem segredo e sem caminho absoluto desnecessário;
5. registro de auditoria saneado;
6. erro explícito e acionável diante de ambiguidade ou violação.

Os nomes usam `_` porque é o formato das próprias tools da plataforma e o que os clientes de IA
aceitam. O nome canônico com ponto (`workspace.get_context`) é o que aparece na trilha de auditoria.

Os nomes de tool, os parâmetros e as frases devolvidas ao cliente permanecem em inglês: são contrato
lido por uma IA. Este documento é traduzido; o contrato citado nele, não.

---

## Diagnóstico

### `prumo_diagnostics`
Confirma que o Prumo está ativo, para qual projeto a chamada resolveu, e se esse projeto está
vinculado a exatamente um workspace. Use antes de qualquer coisa quando um cliente se comportar de
forma estranha.

---

## Workspace

### `prumo_workspace_get_context`
O workspace corrente: id, nome, tipo, o repositório a que o projeto aberto pertence, e quantos
repositórios e fontes de documentação ele tem. Só o workspace corrente é visível, sempre.

### `prumo_workspace_get_policy`
O que este workspace permite, decidido pelo motor de políticas — não uma cópia das flags de
configuração. As ações de banco são avaliadas sem um banco específico; a justificativa explica isso.

### `prumo_workspace_get_repositories`
Os repositórios vinculados com papel, modo de acesso e identidade normalizada do remote
(`host/org/nome`), mais a `description` em texto livre que o desenvolvedor escreveu sobre cada um: o
que é, para que serve e quais regras dele importam. Os papéis são `PRIMARY` (o que está sendo
construído, incluindo o projeto aberto), `REFERENCE`, `LEGACY_REFERENCE` e `RELATED_COMPONENT`; eles
descrevem, não concedem — quem concede é o modo de acesso.
Traz também `excludedPaths`: os caminhos que o Prumo recusa ler, listar e varrer naquele repositório.
Não é pedido — as três tools de repositório impõem. O `.git` está sempre nessa lista, mesmo sem
configuração.
Nunca um caminho local, nunca a URL crua do remote — uma URL pode carregar token embutido.

### `prumo_workspace_get_documentation_sources`
A documentação anexada, com nível de autoridade e se o Prumo consegue lê-la como texto. PDF é
reportado como catalogado e não extraível.

### `prumo_workspace_read_documentation`
Lê o conteúdo de uma fonte de documentação, endereçada pelo `documentationId`. Quando a fonte é uma
pasta, recebe também o caminho de um arquivo dentro dela. Pagina por linha. Caminho absoluto,
travessia e qualquer caminho que escape da raiz cadastrada são recusados; formato apenas catalogado,
como PDF, é recusado com a explicação.

### `prumo_workspace_prepare`
Valida o workspace e devolve `READY`, `WARNING` ou `ERROR`, com uma verificação por repositório e por
fonte de documentação. Fonte cujo conteúdo o Prumo não lê vira `WARNING`. **Somente leitura**: nunca
roda `git pull`, `checkout`, `reset` nem qualquer mutação.

---

## Repositório

Todas aceitam um `repositoryId` e, por padrão, usam o repositório do projeto aberto. Leem do disco,
então alterações não salvas no editor não entram — para essas, use as ferramentas da própria IDE.

### `prumo_repository_get_status`
Branch, upstream, contadores de adiantamento e atraso, e caminhos alterados, a partir de
`git status --porcelain=v2`.

### `prumo_repository_get_branch`
Branch corrente com seu upstream e a distância, mais a lista de branches locais.

### `prumo_repository_get_diff`
Contagem de linhas acrescentadas e removidas por arquivo e, quando um `path` é informado, o patch
unificado daquele arquivo. Aceita `staged`. Nunca roda comando Git que altere estado.

### `prumo_repository_read_file`
Lê um arquivo de texto por `path` relativo à raiz do repositório, com `firstLine` e `maxLines`.
Caminho absoluto e `..` são recusados. Arquivo binário é recusado com mensagem explícita.

### `prumo_repository_search_text`
Busca literal dentro de um repositório vinculado, opcionalmente restrita a um subdiretório. Arquivo
binário e `.git` nunca são lidos.

### `prumo_repository_get_structure`
Diretórios e arquivos de um repositório vinculado. Serve para descobrir o layout de um repositório
que **não** é o projeto aberto — para o projeto aberto as ferramentas da própria IDE já respondem.

---

## IDE

### `prumo_ide_get_current_context`
Onde o desenvolvedor está agora: o arquivo como `repositoryId` mais caminho relativo, linha e coluna
do cursor, intervalo selecionado, a cadeia de símbolos que contém o cursor, linguagem e módulo. Se o
arquivo aberto não pertence a repositório algum deste workspace, a resposta é só
`insideWorkspace: false` — nomear um arquivo fora da fronteira já seria contar sobre ele.

---

## Banco de dados

### `prumo_database_list_available`
Os bancos vinculados a este workspace: id, nome, motor, modo de acesso e schema padrão. **Nunca**
host, porta, usuário, nome do banco ou credencial.

### `prumo_database_get_schema`
Schemas com quantas tabelas e views cada um guarda. Schemas de sistema ficam de fora.

### `prumo_database_list_tables`
Tabelas, views e views materializadas, opcionalmente restritas a um schema. A contagem de linhas é a
estimativa do planejador, declarada como tal.

### `prumo_database_describe_table`
Colunas com tipo, nulabilidade, padrão e comentário; restrições e índices como o próprio PostgreSQL
os apresenta. Só estrutura — nenhuma linha é lida.

### `prumo_database_execute_readonly`
Um statement de leitura. Aceita `SELECT`, `WITH … SELECT` e `EXPLAIN` sem `ANALYZE`; recusa DML, DDL,
DCL, `CALL`, `COPY`, múltiplos statements e qualquer coisa que o parser não conseguiu ler. Roda numa
transação somente-leitura encerrada em rollback. `maxRows` começa em 100, com teto de 1000, e a
resposta traz `truncated`. Coluna cujo nome anuncia segredo volta mascarada.
Statement que o próprio servidor recusa — coluna ou tabela inexistente, `GROUP BY` faltando,
privilégio negado — volta com a mensagem do servidor, nomeando o que foi recusado.

---

## Pacotes

### `prumo_pack_list`
Os pacotes instalados com as capacidades que cada um declarou. Toda resposta vem marcada com
`thirdParty: true`. Aceita uma tag `language` para títulos e descrições.

### `prumo_pack_search_knowledge`
Busca determinística sobre o conhecimento do pacote — texto, título e tags. Sem embeddings, sem
ranqueamento por modelo: a mesma pergunta devolve sempre o mesmo resultado, e o modelo o interpreta.

### `prumo_pack_get_knowledge`
O texto completo de um item de conhecimento, endereçado por `packId` e `itemId`. Caminho de arquivo
nunca é aceito do cliente.

### `prumo_pack_run_tool`
Roda uma ferramenta de um pacote instalado: uma consulta salva de leitura ou um script confinado. As
consultas passam pelas mesmas camadas do `execute_readonly`; os scripts rodam confinados, exigindo ao
mesmo tempo a política do workspace e a capacidade declarada.

---

## Autoria de pacote

### `prumo_pack_get_authoring_spec`
Tudo o que é preciso para escrever um pacote que o Prumo aceite: esquema, catálogo de capacidades,
regras de segurança, limites, onde ele é instalado, três exemplos completos e os motivos mais comuns
de recusa. Leia antes de escrever um pacote.

### `prumo_pack_validate`
Confere um rascunho e devolve o que está errado, onde, e como corrigir. Sem efeito colateral — pode
ser chamada até o rascunho ficar válido.

### `prumo_pack_submit`
Põe o rascunho na fila de aprovação, na janela do Prumo. **Não instala, não ativa e não roda nada.**
Só o desenvolvedor, na tela de consentimento, ativa um pacote.
