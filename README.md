# Prumo MCP

**Português (Brasil)** · [English](README.en.md)

[![CI](https://github.com/gilmardeveloper/prumo/actions/workflows/ci.yml/badge.svg)](https://github.com/gilmardeveloper/prumo/actions/workflows/ci.yml)
[![Licença](https://img.shields.io/badge/licen%C3%A7a-Apache--2.0-blue.svg)](LICENSE)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2026.1%2B-000?logo=intellijidea)](https://www.jetbrains.com/idea/)

**Transforma a sua IDE numa fonte de contexto padronizada, isolada e auditável para clientes de IA
que falam MCP — Claude, Codex, Gemini e qualquer outro que use o Model Context Protocol.**

O Prumo não é uma IA. É a camada determinística entre a sua IDE, o seu código, o seu Git, a sua
documentação, os seus bancos, as suas políticas — e o cliente de IA. Ele define a fronteira dentro
da qual o assistente pode trabalhar, e impõe essa fronteira em código, não em prompt.

---

## O problema

Um assistente de IA que ajuda num sistema real precisa de contexto. Hoje esse contexto é improvisado:

- **O contexto é improvisado.** `CLAUDE.md`, `AGENTS.md`, uma pasta de anotações, um schema colado à
  mão. Cada pessoa monta o seu, e nada disso sobrevive a uma troca de máquina nem chega ao colega.
- **A fronteira é um pedido, não uma regra.** "Não mexa no repositório legado" é uma frase num
  prompt. Nada impede uma ferramenta de ler o legado, e nada avisa depois que ela leu.
- **O banco é a parte perigosa.** Entregar uma string de conexão a um assistente é fácil. Dar acesso
  somente-leitura a exatamente um banco, com segredo mascarado, teto de linhas e trilha de
  auditoria, é trabalho que ninguém faz duas vezes.
- **Trabalho com vários repositórios vaza.** Modernizar um sistema legado significa ler um
  repositório enquanto se escreve outro. A IDE conhece o projeto que você abriu, e nada além dele.

## O que o Prumo faz

O Prumo introduz o **espaço de trabalho**: uma fronteira explícita que diz quais repositórios, qual
documentação e quais bancos pertencem um ao outro, e o que é permitido dentro deles.

- **Isolamento por construção.** Um espaço de trabalho nunca referencia outro. Não existe consulta
  que, partindo de um, alcance o conteúdo do outro — nem caminho, nem documento, nem banco, nem
  trilha de auditoria.
- **Somente-leitura que é imposta.** Repositório vinculado como `READ_ONLY` continua somente-leitura
  para toda ferramenta. Banco vinculado como `READ_ONLY` recusa escrita no driver, na sessão e na
  classificação do statement — e a credencial de leitura que você configura continua sendo a
  barreira principal.
- **Nada é escrito dentro dos seus repositórios.** Todo byte que o Prumo produz fica num diretório
  do sistema operacional, nunca no seu projeto.
- **A documentação responde por passagem.** Especificação grande, PDF, Word, Excel e PowerPoint
  entram pela busca: o que volta é o trecho verbatim com a coordenada para citar — a página, a aba
  e a linha, o parágrafo, o slide. Com o modelo local instalado, a busca também acha por sentido, e
  a inferência acontece na sua máquina.
- **Tudo é auditado, nada é copiado.** A trilha registra o que aconteceu — ferramenta, espaço de
  trabalho, banco, tipo do statement, contagem de linhas — e nunca o dado em si.
- **Conhecimento de equipe que viaja.** Um **Prumo Pack** leva documentação, consultas salvas e
  scripts entre máquinas e colegas, e só é instalado depois de uma tela de consentimento que mostra
  as capacidades que ele pede, os trechos de risco encontrados e os scripts por inteiro.

## O que você precisa

- IntelliJ IDEA **2026.1 ou mais novo**, Community ou Ultimate, com o plugin **MCP Server** (que já
  vem junto desde a 2025.2) habilitado
- JDK 21 ou mais novo para rodar a IDE; JDK 25 para compilar o plugin a partir do código
- Windows ou Linux
- PostgreSQL, se você for usar as ferramentas de banco

## Instalação

Ainda não há publicação no JetBrains Marketplace. A instalação é a partir do código:

```bash
git clone https://github.com/gilmardeveloper/prumo.git
cd prumo
./gradlew buildPlugin
```

O pacote aparece em `build/distributions/` como um arquivo `.zip`.

Na IDE:

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. escolha o `.zip` que acabou de ser gerado;
3. reinicie a IDE quando ela pedir.

> Se o `./gradlew` falhar dizendo que a versão do Java não serve, aponte o `JAVA_HOME` para um JDK
> 25 antes de compilar.

### Ligar o servidor MCP

O Prumo não sobe servidor próprio: ele contribui as ferramentas dele para o servidor MCP da própria
IDE.

1. **Settings → Tools → MCP Server**, marque a opção de habilitar.
2. Aponte o seu cliente de IA para o servidor. A mesma tela auto-configura os clientes que ela
   conhece e copia o endereço para os demais.
3. Peça ao cliente a lista de ferramentas: as que começam com `prumo_` são as deste plugin.

O passo a passo por cliente — Claude, Codex e Gemini —, a configuração do espaço de trabalho e o
prompt que faz o cliente encontrar tudo já na primeira interação estão em
[docs/getting-started.md](docs/getting-started.md).

## O seu primeiro espaço de trabalho

1. Abra um projeto. Na barra da direita, abra **Prumo MCP**.
2. Clique em **Configurar workspace**, dê um nome e escolha o tipo. O projeto aberto entra como
   repositório principal.
3. Clique em **Editar workspace** para acrescentar o que pertence ao conjunto:
   - outro repositório como `LEGACY_REFERENCE` em `READ_ONLY`;
   - documentação — uma pasta de Markdown, uma especificação, um glossário;
   - um banco PostgreSQL, com a senha indo para o cofre da IDE e o **Testar conexão** avisando o que
     está errado antes que o seu cliente de IA descubra;
   - as políticas, todas negadas por padrão.
4. Peça ao seu cliente de IA para chamar `prumo_workspace_get_context`. Ele recebe o espaço de
   trabalho atual, e apenas ele.

O painel mostra o espaço de trabalho, os repositórios com papel e modo de acesso, e as políticas —
de modo que a fronteira fica à vista enquanto você trabalha, e não escondida num arquivo de
configuração.

### Idioma da interface

As telas acompanham o idioma da IDE. Para deixar o Prumo em português com a IDE em inglês, use
**Settings → Tools → Prumo MCP**. Nome de ferramenta, descrição e mensagem devolvida ao cliente de
IA seguem sempre em inglês: são contrato lido por uma máquina, não texto de tela.

## As ferramentas MCP

Trinta e duas ferramentas, documentadas uma a uma em [docs/mcp-tools.md](docs/mcp-tools.md):

| Grupo | O que responde |
|---|---|
| `prumo_workspace_*` | Qual é o espaço de trabalho atual, o que ele permite, o que pertence a ele, se está pronto — e a passagem da documentação que responde à pergunta |
| `prumo_repository_*` | Estado do Git, branch e diff; ler arquivo, buscar texto, listar estrutura — **inclusive de repositórios vinculados que não estão abertos na IDE** |
| `prumo_ide_get_current_context` | Onde a pessoa está agora: arquivo, cursor, seleção, símbolos que a contêm, módulo |
| `prumo_quality_list_inspections` | O que esta IDE sabe procurar: as inspeções registradas e habilitadas no perfil corrente |
| `prumo_database_*` | Quais bancos existem, seus schemas e tabelas, e um statement de leitura por vez |
| `prumo_knowledge_*` | A memória da IA: guardar o que foi destilado, procurar, ler e apagar, com procedência e veredicto de frescor |
| `prumo_pack_*` | Packs instalados, o conhecimento deles, e o ciclo de autoria que um cliente de IA usa para propor novos |

O Prumo não repete o que o servidor MCP da IDE já faz — busca de símbolo, inspeção sobre um arquivo,
build, testes, refatoração, depurador. Ele acrescenta o que as ferramentas nativas não fazem: a
fronteira do espaço de trabalho, repositórios que não são o projeto aberto, estado do Git, bancos
isolados e conhecimento de equipe portátil.

## Segurança

As premissas estão escritas em [docs/security.md](docs/security.md), inclusive o que o Prumo **não**
protege. Em resumo:

- as credenciais ficam no cofre da IDE, nunca num arquivo, num log, numa entrada de auditoria ou
  numa mensagem de erro;
- todo caminho vindo de um cliente é `repositoryId` mais caminho relativo, normalizado e conferido
  contra a raiz do repositório — caminho absoluto e `..` são recusados;
- o SQL é classificado por lista de permissão sobre o statement analisado, e roda dentro de uma
  transação somente-leitura que termina em rollback;
- coluna cujo nome anuncia segredo volta mascarada, sem precisar configurar nada;
- script de pack roda com diretório de trabalho confinado, tempo máximo obrigatório, ambiente mínimo
  que não herda as variáveis da IDE, e saída limitada;
- pack proposto por um cliente de IA nunca fica ativo antes de uma pessoa aceitar na tela de
  consentimento.

Cada um desses pontos é um teste nomeado na bateria de segurança: `./gradlew test -PsecurityOnly`.

## Limitações honestas

- **Só PostgreSQL.** A arquitetura aceita outros bancos sem reescrita; o produto entrega um.
- **Análise estática é detecção de sinal, não prova.** O classificador de risco encontra padrões
  destrutivos conhecidos. Um script escrito para esconder o que faz passa. As barreiras reais são as
  capacidades concedidas, o confinamento e a sua leitura da tela de consentimento.
- **Confinamento de script não é sandbox do sistema operacional.** O diretório de trabalho e o
  ambiente são controlados; o processo ainda roda com o seu usuário. Um comando com caminho absoluto
  alcança o disco inteiro.
- **Formato binário é extraído, não interpretado.** De PDF, Word, Excel e PowerPoint sai o texto e
  nada além dele: estilo, tema, propriedade do documento, relação e desenho ficam no arquivo. PDF
  digitalizado, cujas páginas são imagem, é recusado em vez de devolvido vazio.
- **O modelo local ordena, nunca redige.** Ele decide qual trecho aparece; o trecho continua sendo
  o texto verbatim do documento. Sem o modelo, a busca é só por palavra, e a resposta declara isso.
- **Validado primeiro em Windows.** A paridade com Linux é requisito de projeto, coberta por testes
  e pela integração contínua, mas o roteiro de ponta a ponta foi executado em Windows.
- **Interface em português e inglês.** A superfície MCP fica em inglês de propósito: nome e
  descrição de ferramenta são contrato lido por uma IA, não texto de interface.

## Próximos passos

Registrados, não implementados: outros bancos, MCP remoto/corporativo, um registro central de
espaços de trabalho, ferramental mais rico para packs. Nada disso está pela metade no código.

## Experimentar o ciclo inteiro

[docs/demo.md](docs/demo.md) percorre o ciclo completo — espaço de trabalho, fronteira, banco, um
pack escrito por um cliente de IA e instalado por uma pessoa — dizendo o que observar em cada passo.

## Contribuir

Leia [CONTRIBUTING.md](CONTRIBUTING.md). Em resumo: o fluxo é investigar → planejar → implementar →
revisar, a bateria de segurança bloqueia a entrega, e teste que falha nunca vira item pendente.

## Licença

[Apache License 2.0](LICENSE).
