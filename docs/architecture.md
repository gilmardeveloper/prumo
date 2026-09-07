# Arquitetura

**Português (Brasil)** · [English](architecture.en.md)

O Prumo é um plugin da plataforma IntelliJ que contribui ferramentas para o servidor MCP embutido na
IDE. Tem uma regra de arquitetura que explica quase todo o resto: **a parte que decide o que um
cliente de IA pode enxergar não depende da IDE**, e por isso pode ser testada sem uma.

## Camadas

```
┌──────────────────────────────────────────────────────────────────────────┐
│ toolsets/            Adaptador MCP — fino. Resolve o projeto e delega.    │
├──────────────────────────────────────────────────────────────────────────┤
│ ui/                  Janela do Prumo, editor de workspace, consentimento. │
├──────────────────────────────────────────────────────────────────────────┤
│ ide/                 O único lugar que toca PSI e Git4Idea.               │
├──────────────────────────────────────────────────────────────────────────┤
│ workspace/ policy/ repository/ datasource/ pack/ audit/ storage/ quality/ │
│ knowledge/           O núcleo determinístico. Nenhum tipo do IntelliJ.    │
├──────────────────────────────────────────────────────────────────────────┤
│ platform/            Sistema operacional, diretórios, execução de script. │
└──────────────────────────────────────────────────────────────────────────┘
```

Tudo abaixo de `ide/` é Kotlin puro. É por isso que a suíte roda em segundos sem subir uma IDE, e é
por isso que as garantias de fronteira são verificáveis em vez de argumentadas.

## O caminho único até o contexto

Toda tool passa pelo mesmo contrato, em `toolsets/PrumoToolCall.kt`:

1. **Resolver o projeto.** O servidor MCP da IDE é um só para a aplicação inteira e atende vários
   projetos abertos. Escolher um projeto por conveniência quando essa identificação falha seria
   expor um workspace no lugar de outro, então a ausência de projeto é sempre erro.
2. **Resolver o workspace**, pelo `CurrentWorkspaceContextService` — o resolvedor único. Não
   configurado é erro; vinculado a mais de um workspace é erro. O Prumo não escolhe.
3. **Resolver o alvo** — um repositório ou um banco — *dentro* do workspace. Identificador que não
   pertence a este workspace é recusado, não procurado em outro lugar.
4. **Consultar o `PolicyEngine`.** Nenhuma tool decide permissão por conta própria, e nenhuma
   instrução dada ao modelo substitui essa avaliação.
5. **Fazer o trabalho** e então **registrar a auditoria** com o desfecho real.
6. **Falhar fechado e explícito.** Ambiguidade, violação e entrada que o parser não reconhece
   terminam em erro acionável, nunca em palpite.

Como o contrato vive numa função só, uma tool nova não consegue implementar metade dele por descuido.

## Como um projeto vira um workspace

Um repositório é identificado por **fingerprint**, não pelo caminho: o remote Git normalizado quando
existe, o nome do diretório quando não. Quem move `C:\repos` para `D:\workspace`, ou clona o mesmo
repositório em outra máquina, mantém o vínculo. A alternativa — gravar um arquivo marcador dentro do
repositório — é proibida pela regra de nunca escrever dentro dos seus repositórios.

Dois workspaces reivindicando o mesmo projeto é erro de configuração, e o Prumo diz isso em vez de
escolher um.

## Onde as coisas ficam

| O quê | Onde |
|---|---|
| Workspaces, pacotes, auditoria | `%LOCALAPPDATA%\PrumoMCP\` no Windows, `$XDG_DATA_HOME/prumo-mcp/` no Linux |
| Credenciais | O cofre de senhas da IDE, endereçado por workspace + banco |
| O seu projeto | **Nada.** Nem um byte. |

## Decisões, com data

| Data | Decisão | Por quê |
|---|---|---|
| 2026-09-04 | Contribuir para o servidor MCP da IDE em vez de subir um próprio | Um servidor, um consentimento, um lugar só para o usuário configurar |
| 2026-09-04 | Ler `.git/config` diretamente para remote e raiz | Mesmo comportamento no Windows e no Linux, testável sem a IDE |
| 2026-09-04 | Nome de tool com `_`, não com `.` | As próprias tools da plataforma usam, e clientes validam nome contra `[A-Za-z0-9_-]` |
| 2026-09-05 | Empacotar o driver do PostgreSQL | O Database Tools só existe no Ultimate; o Prumo precisa funcionar no Community |
| 2026-09-05 | Estado do Git pelo plugin Git embutido | Usa o executável que o usuário já configurou; sem uma segunda implementação de Git dentro do plugin |
| 2026-09-05 | Sem pool de conexão | Pool mantém conexão autenticada viva entre chamadas: segredo mais tempo em memória e estado de sessão sobrevivendo à consulta |
| 2026-09-05 | JSqlParser para classificar o statement | Java puro, Apache-2.0/LGPL, 1,2 MB. Escrever o próprio reconhecedor de SQL é como a maioria dos bypass acontece |
| 2026-09-05 | Cache de build do Gradle desligado | Ele restaurou saída de teste obsoleta depois de uma mudança de ABI: 34 testes falharam contra bytecode velho e, pior, alguns passaram |
| 2026-09-05 | Interface bilíngue, superfície MCP só em inglês | Nome e descrição de tool são contrato lido por uma IA; texto de interface é para gente |
| 2026-09-05 | Idioma da interface resolvido pelo Prumo, não só pela IDE | O idioma da IDE é o padrão; forçar um exige carregar o bundle para um locale explícito, porque o `<resource-bundle>` da plataforma e o `getResourceBundleLocalized` interno seguem a IDE |
| 2026-09-05 | Papel de repositório com serializador próprio | Papel removido do enum tornaria ilegível o arquivo inteiro do workspace; o serializador resolve valor desconhecido em vez de derrubar repositórios, documentação, bancos e políticas |

## Estratégia de teste

- O núcleo determinístico é testado direto, sem IDE.
- As camadas de banco são testadas contra um PostgreSQL real por Testcontainers. Sem Docker, esses
  testes se declaram pulados — o resto da suíte continua significando alguma coisa.
- O confinamento de script é testado contra o sistema operacional real, com comandos escolhidos por
  sistema, para que as mesmas garantias sejam conferidas no Windows e no Linux.
- Três testes leem o código-fonte do próprio projeto: um fixa os nomes das tools MCP registradas,
  outro prova que o toolset de autoria não contém caminho de instalação, e o `CoreIndependenceTest`
  varre os pacotes e reprova qualquer tipo da IDE que atravesse a fronteira do núcleo.
- O `SecurityCoverageTest` mapeia cada princípio inviolável ao teste que o sustenta, e falha quando
  um deles desaparece.
