# Modelo de segurança

**Português (Brasil)** · [English](security.en.md)

Este documento diz o que o Prumo protege, como, e — igualmente importante — contra o que ele **não**
protege. Afirmação de segurança sem os seus limites é propaganda.

## Modelo de ameaça

O Prumo fica entre um cliente de IA e a máquina de quem desenvolve. Os atores são:

| Ator | Presumido como | Ameaça considerada |
|---|---|---|
| O cliente de IA | Não malicioso, mas **imprevisível**: compõe chamadas, tenta de novo e segue texto que leu em algum lugar | Ler ou alterar o que não devia; vazar conteúdo de um workspace para dentro de outro |
| Conteúdo de prompt e de repositório | **Entrada não confiável** | Instrução embutida num arquivo, numa tabela ou num documento tentando redirecionar o assistente |
| Um pacote do Prumo vindo de um colega | **Código não confiável**, instalado deliberadamente | Comando destrutivo, roubo de credencial, alcance a outro workspace, baixar-e-executar |
| A pessoa que desenvolve | Confiável, mas humana | Aceitar algo que não leu, configurar mal uma fronteira |

Fora de escopo: um atacante com controle da máquina, um plugin malicioso rodando ao lado do Prumo, ou
uma distribuição comprometida da JetBrains. Se qualquer um desses for verdade, nada neste documento
ajuda.

## Garantias, e como cada uma é imposta

**Isolamento de workspace.** Um workspace nunca referencia outro. O armazenamento é endereçado por
identificador de workspace, então não existe consulta que caminhe de um workspace até os
repositórios, a documentação, os bancos, os pacotes ou a auditoria de outro. O isolamento nasce do
formato do acesso, não de uma verificação que alguém possa esquecer de chamar.

**Contenção de caminho.** O cliente nunca envia caminho absoluto: envia `repositoryId` mais um
caminho relativo. A resolução é validada duas vezes — sobre o caminho normalizado e, quando o alvo
existe, sobre o caminho real no sistema de arquivos — porque normalizar sozinho não enxerga link
simbólico, junction nem ponto de montagem. O diretório `.git` nunca é lido como conteúdo do projeto.

**Repositórios somente-leitura.** `READ_ONLY` é propriedade do vínculo, conferida pelo motor de
políticas antes de qualquer operação. A superfície de repositório do MVP é inteira de leitura, e um
teste fixa os nomes das tools registradas, de modo que acrescentar uma tool de escrita seja uma
mudança visível.

**Bancos somente-leitura, em camadas.** Na ordem: (1) a credencial de leitura que você configura no
PostgreSQL — a barreira de verdade; (2) a conexão JDBC e a sessão marcadas como somente-leitura; (3)
um `SET TRANSACTION READ ONLY` explícito, aplicado mesmo quando o banco está vinculado como
`READ_WRITE`, porque `execute_readonly` é de leitura por definição; (4) classificação do statement
sobre a árvore sintática, aceitando só `SELECT`, `WITH … SELECT` e `EXPLAIN` sem `ANALYZE`; (5) teto
de linhas e tempo máximo de consulta; (6) registro de auditoria saneado. A transação sempre termina
em rollback.

**Proteção da credencial.** As senhas ficam no cofre da IDE, endereçadas por workspace e banco.
Trafegam em `CharArray` e são zeradas depois do uso. Nunca aparecem num perfil, num arquivo JSON,
numa URL JDBC, numa linha de log, num registro de auditoria ou numa mensagem de erro. O único momento
em que uma senha vira `String` é o mapa `Properties` que a API JDBC exige, dentro de uma função que o
limpa.

**Segredo em resultado de consulta.** Coluna cujo nome anuncia segredo — `password`, `senha`,
`token`, `api_key`, entre outros — volta mascarada **sem configuração alguma**. Você pode mascarar
mais colunas; não pode desmascarar essas.

**Nada escrito dentro dos seus repositórios.** Todo artefato que o Prumo produz vai para um diretório
do sistema operacional. Um teste prova que instalar um pacote não escreve nada dentro do diretório de
um projeto.

**Consentimento para pacote.** Um pacote nunca fica ativo antes de uma pessoa aceitar. A tela de
consentimento mostra origem, versão, checksum, as capacidades em linguagem simples, cada achado com o
trecho exato que o produziu, e os scripts por inteiro. Achado destrutivo exige reconhecimento item a
item, nunca pré-marcado. Pacote `BLOCKED` não tem botão de aceitar nem parâmetro que o instale.

**Confinamento de script.** Os comandos são listas de argumentos, nunca strings de shell. O diretório
de trabalho fica dentro do pacote. O ambiente não é herdado da IDE — um `PATH` mínimo mais o que o
pacote declarou. A entrada padrão é fechada, a saída tem teto, e o tempo máximo mata a árvore de
processos.

**Auditoria sem duplicação.** A trilha registra tool, workspace, repositório, banco, pacote, tipo de
statement, contagem de linhas, desfecho e duração. Nunca registra conteúdo de arquivo, resultado de
consulta, texto SQL ou valor de parâmetro: auditoria que copia o dado vira uma segunda cópia daquilo
que ela deveria proteger.

**A IA escreve na memória, e só nela.** Desde a 0.5.0 existe uma base de conhecimento por
workspace em que os clientes de IA gravam o que destilaram — sem consentimento por item, ao contrário
dos pacotes. O que sustenta isso não é confiança no conteúdo: é a **procedência**. Só é aceito o
registro que aponta para uma fonte já ao alcance daquele workspace, o carimbo dessa fonte é calculado
pelo Prumo e nunca aceito do cliente, e toda leitura devolve o veredicto de frescor ao lado do
conteúdo. A base não toca os repositórios do usuário, não é portável e é reconstruível a partir das
fontes — se ela sumir, nada de original se perde. **O Prumo não garante que o destilado é fiel à
fonte**: destilação por IA perde informação, e ele não julga conteúdo. Garante de onde veio, se a
fonte mudou, quem escreveu e quando. O desenvolvedor vê tudo o que foi gravado na aba *Memória da IA*
e pode apagar qualquer registro.

## O que o Prumo governa, e o que ele não governa

O Prumo impõe fronteira às **ferramentas do Prumo**. O servidor MCP da IDE não é dele: é da
plataforma, e serve outras famílias de ferramentas ao mesmo cliente, com fronteiras próprias e mais
largas.

Medido em campo, contra uma instalação real: as ferramentas da própria IDE recusam por **destino** —
o que está fora de "project, library, and SDK roots" —, e raiz de biblioteca e de SDK ficam fora do
projeto. Elas devolveram conteúdo de `C:\Program Files\Java\jdk-25` e do repositório Maven do
usuário por caminhos com `..`, que o Prumo recusa por **forma**, antes de olhar o destino. Na mesma
lista há ferramenta de executar comando de terminal.

O alcance efetivo de um cliente conectado é, portanto, a **união** das famílias, não a interseção, e
quem define o teto é a mais permissiva. O que o Prumo garante é que **através das tools dele** nada
fora do workspace é alcançado, e que repositório de referência, documentação e banco só existem por
ele. Quem precisa de um limite para a sessão inteira precisa desligar as outras famílias no cliente
de IA, e isso está fora do alcance deste plugin.

## O limite do mascaramento

A máscara decide pela **coluna de origem**, lida do metadado do driver, e não pelo apelido que o
cliente escolheu: `SELECT senha AS num_matricula` volta mascarado. Coluna calculada não tem coluna de
origem no metadado; nesse caso o Prumo lê a lista de seleção e mascara a coluna cuja própria
expressão encosta em nome sensível. Quando a lista não pode ser mapeada com segurança — expansão por
`*`, statement que não é um `SELECT` simples —, todas as calculadas são mascaradas: sobra máscara, e
essa é a direção aceita.

## Ofuscação de dado pessoal

Segredo de autenticação é substituído por inteiro. **Dado pessoal é parcialmente escondido**, o
suficiente para não ser reconstruído e pouco o bastante para a IA continuar entendendo o campo: CPF
sai como `123.***.789-**`, telefone como `(85) ****-4321`, nome como `Maria S. S.`, e-mail preserva
o domínio, e data de nascimento preserva o ano — faixa etária e regra por idade continuam
analisáveis.

Três decisões sustentam isso:

- **Dígito verificador nunca é exibido.** Ele é função dos demais dígitos: não acrescenta informação
  de negócio, e permite conferir um palpite vindo de outra fonte.
- **A coluna é reconhecida por todos os nomes que a identificam** — o rótulo da consulta, a coluna
  de origem e, na coluna calculada, os identificadores da própria expressão. Um apelido não desliga
  a proteção.
- **Ofusca-se o dado, nunca o metadado.** Nome de coluna, tipo, comentário, constraint e índice saem
  íntegros. A IA precisa entender a estrutura por inteiro para escrever consulta correta; o que ela
  não precisa é do documento da pessoa.
- **A ofuscação acontece na saída**, depois de o banco ter resolvido a consulta. Junção, agrupamento,
  filtro e ordenação continuam operando sobre o valor real.

O interruptor fica no vínculo do banco, na tela de cadastro, e **nasce ligado**: um banco
recém-vinculado protege sem depender de alguém lembrar de ativar. Desligado, o dado pessoal é
entregue à IA exatamente como está no banco — a máscara de senha e token continua valendo, por
ser outra garantia.

Agregação que produz um número sobre o conjunto — `count`, `sum`, `avg`, `length` — não é ofuscada:
não carrega documento de ninguém, e escondê-la inutiliza a análise sem proteger nada. `min`, `max`,
`string_agg` e `array_agg` continuam ofuscados, porque devolvem valores das linhas. **A linha que
importa é outra: estatística se responde, lista nominal não.**

Valor derivado de expressão — `substr`, `string_agg`, concatenação — é escondido por inteiro, e não
pela janela: a janela cairia sobre o recorte, e iterar o recorte reconstruiria o documento. Onde o
valor não tem o formato da categoria, esconde-se tudo. A regra é falhar fechado.

O limite é conhecido: valor ofuscado **não é chave**. Dois valores que diferem apenas nos dígitos
escondidos saem iguais, e igualdade na saída não prova igualdade na origem.

O que a máscara **não** faz é impedir inferência. O predicado de `WHERE`, `HAVING` e `ORDER BY`
é avaliado pelo banco sobre o valor real, antes de qualquer máscara: `WHERE senha = 'tentativa'`
devolve linhas ou não devolve, e isso confirma o valor sem nunca exibi-lo.

**A contagem completa esse canal.** Agregação sobre coluna pessoal volta com o número real, porque
esconder uma contagem não protege ninguém e inviabiliza análise legítima. Mas predicado e contagem,
juntos, formam um oráculo: `WHERE substr(num_cpf, 4, 1) = '9'` com `count(*)` responde, dígito a
dígito, o que a janela esconde. Dez perguntas por caractere reconstroem um documento — e a resposta
de cada uma é um número legítimo sobre uma pergunta legítima.

Isso é limite conhecido e é uma escolha: a alternativa seria esconder contagens, que foi o
comportamento anterior e custou três a quatro tentativas por consulta em qualquer análise real. A
barreira contra reconstrução dirigida é a credencial somente-leitura com alcance mínimo, não o
Prumo. Mascarar é sobre o que sai, não sobre o que se deduz.

## A orientação entregue à IA

A resposta de uma consulta que tocou dado pessoal carrega uma frase dizendo como trabalhar com o que
veio: que agregação volta completa, e que valor escondido **não é chave** — dois registros com o
mesmo valor escondido podem ser pessoas diferentes. A listagem de bancos diz, antes da primeira
consulta, se aquele banco protege ou não.

Duas decisões sustentam isso:

- **A orientação é gerada a partir da lista que decide o comportamento**, não escrita ao lado dela.
  Acrescentar uma função à lista muda o texto junto. Neste produto uma descrição de tool já afirmou
  uma garantia que o código não cumpria, e essa é a defesa contra repetir.
- **A orientação diz o que fazer, nunca o que evitar.** Enumerar as construções em que a proteção é
  mais restritiva entregaria o caminho de contorno a quem não o tivesse procurado.

**Orientação é complemento, nunca garantia.** Este projeto já constatou em campo que texto em prosa é
pedido, não regra: uma IA respeitou uma exclusão escrita na descrição de um repositório, outra
ignorou e listou tudo. Toda proteção descrita aqui vive em código e está coberta por teste.

## Contra o que o Prumo **não** protege

- **Análise estática não é prova.** O classificador de risco detecta padrões destrutivos conhecidos.
  Um script escrito para ofuscar o que faz vai passar. Trate-o como holofote, não como muro.
- **O confinamento de script não é uma sandbox do sistema operacional.** O processo roda como o seu
  usuário, com as suas permissões. Um comando com caminho absoluto alcança o disco inteiro. Sandbox
  portátil de sistema de arquivos não existe na JVM; a barreira anterior a isso é a sua leitura da
  tela de consentimento.
- **Uma credencial somente-leitura é responsabilidade sua.** As camadas do Prumo reduzem o alcance do
  estrago de um engano; não transformam uma conexão de superusuário numa conexão segura.
- **O cliente de IA continua sujeito a engenharia social** pelo conteúdo que lê. O Prumo limita *o
  que* ele alcança, não o que ele conclui.
- **PDF é catalogado, não interpretado.** Nada dentro de um PDF é analisado.
- **Chamada vinda de projeto sem workspace vinculado não entra na trilha.** Ela é recusada antes de
  qualquer acesso, e a trilha é gravada por workspace: sem workspace resolvido não há arquivo onde
  registrar. A recusa fica no log da IDE, que não é consultável por tool.

## Relatar uma vulnerabilidade

Veja o [SECURITY.md](../SECURITY.md).

## Conferir as afirmações

Cada garantia acima é um teste com nome:

```bash
./gradlew test -PsecurityOnly
```

O `SecurityCoverageTest` mapeia cada princípio aos testes que o sustentam e falha se um deles for
removido. Teste de segurança vermelho bloqueia a entrega; nunca é registrado como pendência.
