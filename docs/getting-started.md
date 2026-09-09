# Primeiros passos

**Português (Brasil)** · [English](getting-started.en.md)

Do zero até o seu cliente de IA respondendo com o contexto do seu espaço de trabalho. Leva cerca de
quinze minutos. Cada passo diz **o que observar** — passo que não se pode conferir não é passo.

---

## 1 · O que você precisa

- IntelliJ IDEA **2026.1 ou mais novo**, Community ou Ultimate, com o plugin **MCP Server**
  habilitado — ele vem junto com a IDE desde a 2025.2
- O plugin do Prumo instalado
- Um cliente de IA que fale MCP: Claude, Codex, Gemini ou outro
- PostgreSQL, só se você for usar as ferramentas de banco

## 2 · Instalar o plugin

Ainda não há publicação no JetBrains Marketplace, então a instalação é a partir do `.zip`:

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. escolha o `.zip` do Prumo;
3. reinicie a IDE quando ela pedir.

Para gerar o `.zip` a partir do código, veja o [README](../README.md).

**Observe:** depois do reinício existe uma janela **Prumo MCP** na barra da direita. Se ela não
aparecer, o plugin não subiu — confira em **Settings → Plugins** se ele está habilitado.

## 3 · Ligar o servidor MCP da IDE

O Prumo **não sobe servidor próprio**. Ele contribui as ferramentas dele para o servidor MCP da
própria IDE, então é esse servidor que precisa estar ligado.

1. **Settings → Tools → MCP Server**
2. marque **Enable MCP Server**.

A tela tem duas seções que interessam:

- **Project Clients Auto-Configuration** — um botão por cliente conhecido, que escreve a
  configuração no arquivo do projeto sozinho. É o caminho mais curto para quem usa Claude Code ou
  Codex.
- **Manual Client Configuration** — os botões **Copy SSE Config**, **Copy HTTP Stream Config** e
  **Copy Stdio Config**, que copiam para a área de transferência o endereço e o formato que aquele
  transporte espera.

**Observe:** a tela mostra a porta em que a IDE publicou o servidor. O padrão é a `64342`, e o
endereço do transporte SSE fica `http://127.0.0.1:64342/sse`. A porta pertence à instalação, não ao
projeto, e muda quando há mais de uma IDE aberta — confira o número na tela antes de colá-lo em
qualquer lugar.

## 4 · Conectar o seu cliente de IA

Se o seu cliente aparece na lista de auto-configuração da IDE, clique no botão dele e pule para o
passo 5. As instruções abaixo são o caminho manual, e valem quando o cliente não está na lista ou
quando você quer a configuração no seu perfil, e não no projeto.

Em todas elas, `<URL>` é o endereço que a IDE copiou.

### Claude Code

```bash
claude mcp add --transport sse prumo <URL>
```

Troque `sse` por `http` se você copiou a configuração de HTTP Stream. Para valer em todos os seus
projetos, acrescente `--scope user`.

Confira com:

```bash
claude mcp list
```

### Codex

O Codex guarda os servidores em `~/.codex/config.toml`:

```toml
[mcp_servers.prumo]
url = "<URL>"
```

Versão antiga do Codex só enxerga servidor local, e precisa ligar o cliente remoto antes da entrada
do servidor:

```toml
[features]
rmcp_client = true
```

Em builds mais velhos ainda, essa chave se chamava `experimental_use_rmcp_client` e ficava no topo
do arquivo. Se o servidor não aparecer, atualizar o Codex resolve mais rápido que descobrir qual das
duas a sua versão lê.

### Gemini CLI

O Gemini lê `~/.gemini/settings.json`, ou `.gemini/settings.json` na raiz do projeto:

```json
{
  "mcpServers": {
    "prumo": {
      "httpUrl": "<URL>"
    }
  }
}
```

`httpUrl` é para o transporte de HTTP Stream. Se você copiou a configuração SSE, a chave é `url` no
lugar de `httpUrl`.

### Cliente que só aceita comando

Boa parte dos clientes não tem campo para URL: pede um comando para iniciar, uma lista de
argumentos, as variáveis de ambiente e o diretório de trabalho. Nesses, quem conversa com a IDE é a
ponte `mcp-remote`, que fala stdio de um lado e SSE do outro. Preencha assim:

| Campo | O que preencher |
|---|---|
| Comando para iniciar | `npx` |
| Argumentos | um por campo, nesta ordem: `-y`, `mcp-remote`, `http://127.0.0.1:64342/sse`, `--allow-http`, `--transport`, `sse-only` |
| Variáveis do ambiente | nada |
| Encaminhamento de variáveis do ambiente | nada |
| Diretório de trabalho | nada |

Troque a porta pela que a tela da IDE mostra. `--allow-http` existe porque o endereço é `http` e não
`https` — nada sai da sua máquina. `--transport sse-only` evita que a ponte tente primeiro o
transporte de HTTP Stream e demore a cair para o SSE. É preciso ter o Node instalado, porque o `npx`
vem com ele.

No cliente que aceita JSON em vez de campos, o mesmo fica assim:

```json
{
  "mcpServers": {
    "prumo": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:64342/sse",
        "--allow-http",
        "--transport",
        "sse-only"
      ]
    }
  }
}
```

### Outro cliente

Qualquer cliente que fale MCP serve. O que ele precisa saber é o transporte e o endereço — os dois
saem dos botões de cópia da tela da IDE. O Prumo não pede chave, token nem conta.

**Observe:** peça ao cliente a lista de ferramentas. As que começam com `prumo_` são deste plugin;
as demais são da própria JetBrains, e continuam existindo. Se nenhuma `prumo_` aparecer, o servidor
subiu sem o plugin — volte ao passo 2.

## 5 · Configurar o workspace

Sem workspace o Prumo não expõe nada, e diz isso a quem perguntar. É o comportamento de falhar
fechado, não um defeito.

1. Abra o projeto e, na barra da direita, a janela **Prumo MCP**.
2. Clique em **Configurar workspace**, dê um nome e escolha o tipo. O projeto aberto entra como
   repositório principal.
3. Clique em **Editar workspace** e acrescente o que pertence ao conjunto:
   - **outro repositório** — papel *Referência legada*, acesso *Somente leitura*, para ler o sistema
     antigo enquanto escreve o novo;
   - **documentação** — uma pasta de Markdown, uma especificação em PDF, uma planilha, um glossário;
   - **um banco PostgreSQL** — host, porta, banco, usuário e senha, com **Testar conexão** dizendo o
     que está errado antes que o seu cliente de IA descubra;
   - **os caminhos excluídos** de cada repositório, que o Prumo recusa ler, listar e varrer;
   - **as políticas**, todas negadas por padrão.

**Observe:** o vínculo novo nasce somente-leitura antes de você tocar em qualquer coisa, e a senha
vai para o cofre da IDE — o arquivo `datasources.json` do workspace nunca a contém.

Se você vai usar a busca por sentido na documentação, é também aqui que ela se habilita: na aba
**Conhecimento**, o botão que baixa o modelo local, cerca de 130 MB, e o mesmo lugar de onde ele se
remove. Sem o modelo a busca continua funcionando por palavra, e a resposta declara isso.

## 6 · O prompt da primeira interação

O servidor MCP não transmite texto de apresentação: entre a conexão e a primeira chamada, o cliente
recebe apenas nomes e descrições de ferramentas. Este prompt encurta a descoberta e fixa as regras
de trabalho. Cole-o na primeira mensagem da sessão, ou guarde-o no arquivo de instruções do seu
cliente.

```text
Você está conectado ao servidor MCP da minha IDE IntelliJ. As ferramentas com o prefixo prumo_ são
do plugin Prumo, que define a fronteira do que você pode enxergar neste sistema.

Antes de responder qualquer coisa sobre o projeto, faça nesta ordem:

1. prumo_diagnostics — confirme que o Prumo respondeu e a qual projeto a chamada resolveu.
2. prumo_workspace_prepare — veja se o espaço de trabalho está pronto e o que está com aviso.
3. prumo_workspace_get_context, get_policy, get_repositories e get_documentation_sources — a
   fronteira: o que existe, o que é somente leitura, o que está excluído e o que é permitido.

Regras para o resto da sessão:

- Enderece arquivo por repositoryId mais caminho relativo. Caminho absoluto é recusado.
- Antes de deduzir uma regra a partir do código, procure na documentação anexada com
  prumo_workspace_search_documentation e cite a coordenada que vier na resposta.
- Se essa busca devolver pendingSources maior que zero, o acervo ainda está sendo indexado: repita a
  busca antes de concluir que não há nada.
- Se semanticAvailable vier falso, a busca foi só por palavra — tente sinônimos antes de desistir.
- Não peça documento inteiro: procure a passagem e, se precisar de mais, leia a vizinhança pela
  linha que a resposta indicou.
- Consulte prumo_knowledge_recall antes de reler fonte cara, e grave com prumo_knowledge_remember o
  que você destilar, sempre nomeando a fonte de onde veio.
- Recusa por caminho excluído ou por política é a fronteira funcionando, não erro: não contorne, não
  tente outro caminho para o mesmo arquivo e me diga o que foi recusado.

Termine esta primeira resposta dizendo qual espaço de trabalho você enxergou, quantos repositórios e
fontes de documentação ele tem, e quais políticas estão concedidas.
```

**Observe:** a resposta precisa nomear o *seu* workspace. Se o cliente descrever outra coisa, ou
disser que não encontrou workspace nenhum, a chamada resolveu para outro projeto aberto — feche os
projetos que não interessam e repita.

## 7 · Conferir que funcionou

Peça ao cliente, em uma frase cada:

| Peça isto | O que confirma |
|---|---|
| chamar `prumo_diagnostics` | o plugin está vivo e o projeto foi identificado |
| chamar `prumo_workspace_get_context` | o workspace corrente, e apenas ele, está visível |
| ler um arquivo do repositório de referência | repositório que não está aberto na IDE responde |
| procurar um assunto na documentação | a passagem volta com a coordenada para citar |
| pedir um `UPDATE` no banco | a recusa chega como recusa, e entra na trilha como negada |

A aba **Atividade** da janela mostra a trilha: uma linha por chamada, com a ferramenta, o desfecho e
quem chamou. Conteúdo lido nunca entra ali.

## 8 · Gastar menos token

O Prumo existe também para a resposta caber. Contexto que não entra na janela do modelo não ajuda, e
o que entra é pago em token a cada sessão nova.

Três hábitos mudam a conta, e o prompt do passo 6 já os pede:

- **Procure a passagem antes de ler o documento.** O manual do eSocial tem 413 páginas, cerca de 246
  mil tokens de texto extraído. A resposta que interessa costuma caber em três parágrafos, e é isso
  que `prumo_workspace_search_documentation` devolve, com a coordenada para citar.
- **Leia a vizinhança, não o arquivo.** `prumo_workspace_read_documentation` e
  `prumo_repository_read_file` paginam por linha. Em formato binário, linhas vizinhas de mesma
  origem vêm agrupadas numa faixa só, para endereçar o trecho não custar mais que o trecho.
- **Não destile duas vezes.** O que custou caro para entender vai para a memória com
  `prumo_knowledge_remember` e volta na sessão seguinte pelo `recall`, que devolve a lista com
  procedência e frescor — o texto só vem quando você pede um registro pelo `read`.

O desperdício maior não está na chamada, e sim na fronteira grande demais: repositório que não
pertence ao trabalho, pasta de build vinculada, documentação que ninguém cita. Caminho excluído no
passo 5 é token que nunca chega a ser gasto.

## Quando não funciona

| Sintoma | Onde olhar |
|---|---|
| O cliente não lista nenhuma ferramenta | O servidor MCP está desligado em **Settings → Tools → MCP Server**, ou o cliente aponta para outra porta |
| Lista as ferramentas da JetBrains, mas nenhuma `prumo_` | O plugin não está habilitado, ou a IDE não foi reiniciada depois da instalação |
| "não foi possível determinar a qual projeto esta chamada se refere" | Há mais de um projeto aberto e o cliente não disse qual — nomeie o caminho do projeto na chamada |
| "este projeto não está vinculado a nenhum workspace" | Falta o passo 5, ou o projeto aberto não é o que você vinculou |
| A busca na documentação volta vazia | Confira `pendingSources` e `semanticAvailable` na própria resposta antes de concluir que não há nada |

---

Para o ciclo inteiro — fronteira, banco, pacote escrito por um cliente de IA e instalado por uma
pessoa — siga [docs/demo.md](demo.md). As ferramentas estão descritas uma a uma em
[docs/mcp-tools.md](mcp-tools.md).
