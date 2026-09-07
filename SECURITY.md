# Política de segurança

**Português (Brasil)** · [English](SECURITY.en.md)

## Versões cobertas

O Prumo está antes da 1.0. As correções de segurança entram na `main` e na versão seguinte; builds
anteriores não recebem correção.

## Como relatar uma vulnerabilidade

**Não abra uma issue pública.** Envie o relato para **gilmarsilva.developer@gmail.com** com:

- o que a vulnerabilidade permite a um atacante (ou a um cliente de IA) fazer;
- os passos para reproduzi-la, incluindo a configuração de workspace envolvida;
- a versão do plugin, a versão da IDE e o sistema operacional.

Você recebe confirmação em até **cinco dias úteis** e uma decisão — correção, mitigação ou "funciona
como projetado, com este limite documentado" — em até **trinta dias**. Se o relato levar a uma
correção, seu crédito vai para o changelog, salvo se você pedir o contrário.

## O que conta como vulnerabilidade aqui

As garantias que o Prumo oferece estão em [docs/security.md](docs/security.md). Um relato está no
escopo quando quebra uma delas, por exemplo:

- alcançar o conteúdo, a configuração, as credenciais ou a trilha de auditoria de outro workspace;
- escapar da raiz de um repositório por caminho, link simbólico, junction ou ponto de montagem;
- escrever através de um repositório ou banco marcado como `READ_ONLY`;
- extrair uma credencial de qualquer artefato que o Prumo grave, registre ou devolva;
- instalar ou ativar um pacote sem a tela de consentimento;
- fazer o Prumo escrever dentro do repositório de alguém.

## O que é limite documentado, e não vulnerabilidade

Estes pontos estão declarados no documento de segurança e não são aceitos como relato:

- script de pacote ofuscado passando pelo classificador de risco — análise estática é detecção de
  sinal, não prova;
- script de pacote alcançando o sistema de arquivos por caminho absoluto — o confinamento é o
  diretório de trabalho e o ambiente, não uma sandbox do sistema operacional;
- credencial de banco permissiva concedendo mais do que se pretendia — a credencial somente-leitura é
  responsabilidade de quem configura, e as camadas do Prumo reduzem o alcance do estrago, não
  substituem essa responsabilidade.
