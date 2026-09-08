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
Caminho absoluto e `..` são recusados. Arquivo binário é recusado com mensagem explícita. Pedir um
diretório é recusado dizendo que é diretório, e apontando a tool que o lista — não se diz que ele
não existe.

### `prumo_repository_search_text`
Busca literal dentro de um repositório vinculado, opcionalmente restrita a um subdiretório por
`scope`. Arquivo binário e `.git` nunca são lidos. `scope` que não existe é recusado, e não devolve
busca vazia: escopo digitado errado e busca que percorreu tudo sem achar levam a conclusões opostas.
`scope` dentro de área excluída continua respondendo que é excluído, para que a recusa não vire um
oráculo de existência sobre o que o workspace decidiu não mostrar.

### `prumo_repository_get_structure`
Diretórios e arquivos de um repositório vinculado. Serve para descobrir o layout de um repositório
que **não** é o projeto aberto — para o projeto aberto as ferramentas da própria IDE já respondem.

---

## IDE

### `prumo_ide_get_current_context`
Onde o desenvolvedor está agora: o arquivo como `repositoryId` mais caminho relativo, linha e coluna
do cursor, intervalo selecionado, a cadeia de símbolos que contém o cursor, linguagem e módulo. Se o
arquivo aberto não está ao alcance, a resposta é `insideWorkspace: false` — nomeá-lo já seria
contar sobre ele —, acompanhada de `reason`: `NO_FILE_OPEN` quando nenhum editor está selecionado,
`FILE_NOT_ON_DISK` quando o que está aberto vive num jar, num scratch ou em sistema remoto, e
`OUT_OF_REACH` quando o arquivo não está ao alcance do workspace. Fora dos repositórios vinculados e
dentro de caminho excluído devolvem o mesmo `OUT_OF_REACH`, de propósito: distinguir os dois diria
que existe algo escondido ali.

---

## Qualidade

### `prumo_quality_list_inspections`
O catálogo de inspeções registradas e habilitadas no perfil corrente do projeto aberto — o que esta
IDE sabe procurar. **Não executa inspeção nenhuma e não lê arquivo.** Aceita recorte por linguagem,
por severidade e por grupo, e devolve as contagens do recorte inteiro antes da janela de resultados.

Três limites que a descrição declara ao cliente e que valem aqui:

- estar registrada e habilitada **não é** o mesmo que ser aplicável: o catálogo não diz o que
  rodaria sobre um arquivo dado;
- os números são desta instalação e dos plugins nela instalados, não do produto — mudam com a edição
  da IDE e com o que estiver instalado;
- `shortName` é estável e sempre em inglês, `displayName` acompanha o idioma da IDE, e a severidade
  não é vocabulário fechado: um plugin pode registrar a sua.

A resposta abre dizendo **de qual perfil ela veio**. `profileScope` é `PROJECT` quando existe
perfil gravado em `.idea/inspectionProfiles` e é ele que responde — o conjunto de regras que aquele
projeto combinou, e que vale para quem clonar o repositório. É `APPLICATION` quando não existe perfil
versionado: a resposta é a configuração daquela instalação da IDE, e outro desenvolvedor pode ver
outra coisa.

A distinção precisa do arquivo porque **a IDE materializa um perfil `Project Default` no projeto
mesmo quando o projeto não traz nenhum** — ele nasce copiado do perfil da aplicação. Perguntar à
plataforma quem administra o perfil corrente responde "o projeto" nos dois casos; só o arquivo
versionado separa regra combinada de cópia local. Projeto no formato antigo, de arquivo `.ipr`
único, não tem esse diretório e é lido como `APPLICATION`.

Filtro com valor que o catálogo não conhece volta em `unknownFilters` — assim `severity: "WARNIG"`
não se confunde com um recorte que legitimamente não casou nada. Os valores aceitos vêm em
`knownValues`, e só para `severity` e `language`: grupo fica de fora porque uma instalação tem
centenas deles, e quem errou o grupo descobre os válidos chamando sem filtro e lendo `byGroup`.

`maxResults` vale 50 por padrão e no máximo 200; pedido fora dessa faixa é puxado para dentro dela,
sem erro. **Não há paginação:** com 1.577 regras registradas numa instalação comum, o caminho para
alcançar o resto é estreitar o recorte, não pedir a página seguinte — e `matchCount` continua
dizendo o tamanho real do que casou.

Encontrar os problemas de um arquivo específico é outro trabalho, e quem o faz é a
`get_file_problems`, do próprio servidor MCP da IDE. O Prumo não a substitui.

---

## Banco de dados

### `prumo_database_list_available`
Os bancos vinculados a este workspace: id, nome, motor, modo de acesso, schema padrão, a descrição
que o desenvolvedor escreveu e se a ofuscação de dado pessoal está ligada, com uma frase sobre o que
esperar dali. **Nunca** host, porta, usuário, nome do banco ou credencial.

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

## Memória da IA

Base própria dos clientes de IA. O que entra aqui é **destilado por eles** a partir de fontes que já
estavam ao alcance deste workspace, e serve para não reler na sessão seguinte o que é caro de ler.
Não é portável: vive na máquina, no workspace, e é reconstruível a partir das fontes.

Diferente dos pacotes em dois pontos: os pacotes trazem conhecimento de fora e exigem o consentimento
do desenvolvedor; a memória deriva do que já está dentro da fronteira e a IA escreve sozinha.

**O que o Prumo garante sobre um registro:** de onde ele veio, se a fonte mudou desde então, qual
cliente o escreveu e quando. **O que ele não garante:** que o resumo é fiel à fonte, e que ele
substitui a fonte. O registro cita; não substitui.

Toda leitura devolve o veredicto de frescor ao lado do conteúdo:

| | |
|---|---|
| `FRESH` | a fonte está como estava quando o registro nasceu |
| `STALE` | a fonte mudou, e o registro pode estar errado |
| `ORPHAN` | a fonte não existe mais ao alcance do workspace |

`FRESH` fala dos **bytes da fonte**, não do registro: quer dizer que ninguém editou aquele arquivo,
nunca que o Prumo conferiu o texto contra ele. Um registro apontando para um PDF — cujo conteúdo o
Prumo declara não conseguir extrair — também volta `FRESH`, porque o arquivo não mudou.

O veredicto é recalculado a cada chamada, comparando tamanho, data de modificação e resumo SHA-256
do arquivo. Divergir em qualquer um dos três basta para o registro ser `STALE` — inclusive quando a
mudança foi em outra parte do arquivo. É deliberado: um falso `STALE` custa uma redestilação, um
falso `FRESH` entrega conteúdo errado como se fosse bom.

### `prumo_knowledge_remember`
Guarda um trecho destilado, dizendo de que fonte ele veio. **O carimbo da fonte é calculado pelo
Prumo**, nunca aceito do cliente: um carimbo informado pela IA provaria apenas o que ela disse.
O que é recusado é o registro que **nomeia uma fonte inalcançável** — id que não existe no
workspace, caminho que não existe dentro dela, ou caminho excluído pelo desenvolvedor, e as três
recusas chegam distintas. O Prumo carimba de onde o texto veio; ele **não** confere que o texto
decorre dali, e esse juízo continua sendo de quem escreve.

### `prumo_knowledge_recall`
Procura o que já foi destilado, por texto, por etiqueta, por fonte ou por frescor. Não devolve o
texto: devolve a lista com procedência e veredicto.

A procura por texto é **por relevância**, sobre título, etiquetas e corpo. A consulta é reduzida a
radicais antes da busca, então flexão diferente da escrita encontra o registro: quem procura
"pagamentos" acha "pagamento", e quem procura "payments" acha "payment". Português e inglês são
cobertos ao mesmo tempo, porque nada no registro declara em que língua ele foi escrito — cada
registro é indexado nas duas. Palavra muito comum é descartada nos dois idiomas: procurar "de" ou
"the" não devolve nada.

**Continua determinística e explicável**: é casamento de palavra com stemming e ranqueamento BM25,
nunca um vetor e nunca um modelo decidindo o que é parecido. O peso de cada campo — título acima de
etiqueta, etiqueta acima de corpo — inclina o resultado, mas não o decide: um termo raro no corpo
pode superar um comum no título. Etiqueta, fonte e frescor seguem sendo recortes exatos.

Consulta de texto que não casa nada volta com `searchedTerms`, e ele separa duas situações que
pareciam a mesma: lista **vazia** diz que a consulta inteira era palavra comum e não sobrou nada para
procurar; lista **preenchida** diz que se procurou por aqueles radicais e nenhum registro os tem. No
primeiro caso o remédio é reescrever a consulta; no segundo, aceitar que a memória não sabe daquilo.

Cada resultado vem com o `score` que o colocou ali, comparável apenas com os outros da mesma
resposta — é posição relativa, não nota. Empate é desfeito pelo identificador, então a mesma consulta
devolve sempre a mesma ordem. Busca sem texto não traz `score`: não há o que pontuar.

O índice nasce e morre dentro da chamada. Não há segunda cópia do dado para divergir do que está
guardado.

### `prumo_knowledge_read`
O texto completo de um registro, com a procedência e o frescor ao lado. Um registro `STALE` continua
sendo devolvido — o que ele diz pode continuar útil —, mas a fonte é a verdade.

### `prumo_knowledge_forget`
Remove um registro. Imediato, sem perguntar ao desenvolvedor. A fonte não é tocada: sai apenas o que
foi destilado dela.

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
