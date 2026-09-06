# Como contribuir

**Português (Brasil)** · [English](CONTRIBUTING.en.md)

Obrigado por considerar contribuir. Este projeto é deliberadamente rigoroso quanto ao processo,
porque o argumento do produto é o rigor: um repositório desleixado o invalidaria.

## Antes de escrever código

Abra uma issue descrevendo o problema antes da solução. Uma mudança que chega sem problema declarado
é difícil de revisar e mais difícil ainda de manter.

## Compilar e testar

```bash
# JAVA_HOME precisa apontar para uma instalação do JDK 25
./gradlew build              # compila, roda os testes e empacota o plugin
./gradlew test               # a suíte completa
./gradlew test -PsecurityOnly # só os testes que sustentam os princípios invioláveis
./gradlew verifyPlugin       # verificação estática contra IDEA Community e Ultimate
./gradlew runIde -PsandboxProject="<caminho de um projeto>"   # IDE de sandbox com um projeto aberto
```

Os testes de banco sobem um PostgreSQL real por Testcontainers. Sem Docker eles se declaram pulados,
e o resto da suíte continua valendo.

## As regras que não se negociam

- **Teste de segurança vermelho bloqueia a entrega.** Nunca vira pendência, e nunca é enfraquecido
  para deixar um build verde.
- **Nada é escrito dentro do repositório de quem usa.** Nunca.
- **Segredo nenhum em código, log, registro de auditoria, mensagem de erro ou fixture de teste.**
- **Falhar fechado.** Contexto ambíguo, workspace não resolvido ou entrada que o parser não reconhece
  terminam em erro explícito, não em palpite.
- **A superfície MCP permanece em inglês.** Nome de tool, descrição e erro devolvido ao cliente são
  contrato lido por uma IA. O texto de interface é traduzido; o contrato não.
- **Análise estática não é vendida como prova.** Ao estender o classificador de risco, mantenha a
  documentação honesta sobre o que ele não alcança.

## Documentação em duas línguas

O português é o padrão de todo documento do repositório; o inglês fica ao lado, no mesmo nome com o
sufixo `.en.md`. As duas versões trazem, logo abaixo do título, o link recíproco de idioma. Alteração
numa língua acontece na outra no mesmo pull request — documento traduzido pela metade é pior que
documento numa língua só.

## Estilo de código

- Kotlin, quatro espaços, a nomenclatura e as convenções de camada do código vizinho.
- Comentário explica **por quê**, não o quê. Se uma linha precisa de comentário para dizer o que faz,
  reescreva a linha.
- Sem código morto, sem `TODO` solto, sem trecho comentado.
- Todo comportamento novo que não dependa da IDE vem com teste que também não dependa.

## Commits e pull requests

- Mensagem em português, uma linha, direta: o mínimo para entender o que foi feito. Sem prefixo de
  convenção (`feat:`, `fix:`, `chore:`) e sem corpo elaborado.
- Bom: `Cria o painel do Prumo na IDE` · `Apaga a senha da memória depois de usar`.
- Sem commit `wip` nem `ajustes` no histórico.
- O pull request descreve o problema, a decisão tomada e como foi verificada. Se você mexeu em algo
  que afeta segurança, diga qual teste prova.
