import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // useInstaller = false resolve a plataforma pelo artefato Maven do repositorio JetBrains,
        // em vez do instalador por sistema operacional: mesma resolucao em Windows, Linux e CI.
        create {
            type = providers.gradleProperty("platformType").map { IntelliJPlatformType.fromCode(it) }
            version = providers.gradleProperty("platformVersion")
            useInstaller = false
        }
        // O Prumo contribui tools ao MCP Server nativo da IDE (bundled desde 2025.2) em vez de
        // subir um servidor MCP paralelo.
        bundledPlugin("com.intellij.mcpServer")

        // Estado do Git vem do plugin Git bundled (presente em Community e Ultimate), que usa o
        // executavel de Git ja configurado pelo usuario. Evita assumir `git` no PATH e evita
        // empacotar uma segunda implementacao de Git dentro do plugin.
        bundledPlugin("Git4Idea")

        // Armazenamento da base de conhecimento destilado. O MVStore do H2 vem com a IDE e
        // traz transacao MVCC: nada e empacotado, e a escrita concorrente deixa de depender
        // de um lock que o produto nao tem.
        bundledModule("intellij.libraries.mvstore")

        // Recuperacao por relevancia sobre a memoria da IA. O Lucene vem com a IDE, com stemming
        // de portugues e ingles e ranqueamento BM25: nada e empacotado, e a busca deixa de ser
        // casamento literal de substring.
        bundledModule("intellij.libraries.lucene.common")
    }

    // A plataforma IntelliJ ja fornece kotlinx-serialization em runtime. Empacotar uma segunda
    // copia geraria classes duplicadas no classloader do plugin.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Inferencia local para a busca por sentido. O runtime e codigo, entao vem empacotado e
    // versionado com o plugin; o modelo e dado, e e baixado na primeira execucao. Carregar jar de
    // terceiro em tempo de execucao trocaria erro de build por erro na maquina do usuario.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")

    // Tokenizador do mesmo modelo. Sem ele o texto nao vira a sequencia de ids que a rede espera, e
    // escrever o proprio tokenizador de vocabulario multilingue e o mesmo erro de escrever o proprio
    // leitor de PDF.
    implementation("ai.djl.huggingface:tokenizers:0.37.0")

    // Extracao de texto de PDF. A IDE nao traz biblioteca de PDF na Community, e o PDFBox que existe
    // na Ultimate e privado do plugin Jupyter: depender dele deixaria o Prumo instalavel so na
    // Ultimate. Escrever o proprio leitor esta fora de questao — o texto vem em indice de glifo, e
    // so o mapa ToUnicode de cada fonte embutida o traduz de volta.
    implementation("org.apache.pdfbox:pdfbox:3.0.8") {
        // O Prumo le texto: nada de assinatura digital, criptografia de documento nem imagem.
        exclude(group = "org.bouncycastle")
    }

    // Classificacao de statement SQL (camada 3 da secao 9). Parser proprio e vetado pelo escopo:
    // reconhecer SQL por expressao regular e como a maioria dos bypass de seguranca acontece.
    implementation("com.github.jsqlparser:jsqlparser:5.3") {
        // O JSqlParser declara o JMH como dependencia de compilacao. Sem excluir, o plugin sairia
        // com 2,8 MB de biblioteca de benchmark dentro, que nada carrega em runtime.
        exclude(group = "org.openjdk.jmh")
        exclude(group = "net.sf.jopt-simple")
        exclude(group = "org.apache.commons", module = "commons-math3")
    }

    // Driver PostgreSQL empacotado com o plugin: o Prumo nao depende do Database Tools, que so
    // existe no Ultimate (F-007). Vai como implementation para ser carregado pelo classloader do
    // plugin em Community e Ultimate.
    implementation("org.postgresql:postgresql:42.7.13") {
        // Anotacoes de analise estatica do Checker Framework: existem so em tempo de compilacao e
        // acrescentariam 240 KB ao plugin sem serem carregadas em runtime.
        exclude(group = "org.checkerframework", module = "checker-qual")
    }

    // compileOnly na producao (a plataforma fornece), mas os testes rodam fora da IDE.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // PostgreSQL de verdade para os testes de banco. Sem Docker na maquina, esses testes se
    // declaram pulados em vez de falharem: o resto da suite continua valendo.
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

// O JDK que compila (toolchain) e o bytecode gerado sao deliberadamente diferentes: a plataforma
// IntelliJ 2026.1 e distribuida em bytecode Java 21, ainda que a JBR seja 25.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(
            providers.gradleProperty("javaToolchainVersion").get().toInt(),
        )
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(providers.gradleProperty("javaTargetVersion").get())
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = providers.gradleProperty("javaTargetVersion").get().toInt()
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // O produto declara suporte a Community e Ultimate (decisao fundacional): a verificacao
    // estatica roda contra as duas edicoes na versao alvo.
    pluginVerification {
        ides {
            val targetVersion = providers.gradleProperty("platformVersion")
            create(IntelliJPlatformType.IntellijIdeaCommunity, targetVersion) {
                useInstaller = false
            }
            create(IntelliJPlatformType.IntellijIdeaUltimate, targetVersion) {
                useInstaller = false
            }
        }
    }
}

// A sandbox de desenvolvimento nao deve parar em dialogos de consentimento a cada execucao.
tasks.runIde {
    // -PsandboxProject=<caminho> abre a sandbox ja com um projeto: o MCP Server da IDE so inicia
    // quando ha projeto aberto.
    providers.gradleProperty("sandboxProject").orNull?.let { args(it) }

    jvmArgs(
        "-Djb.privacy.policy.text=<!--999.999-->",
        "-Djb.consents.confirmation.enabled=false",
        "-Didea.suppress.statistics.report=true",
    )
}

tasks.test {
    // `-PsecurityOnly` roda so a suite de seguranca, que e como o CI a mostra separada: quando ela
    // quebra, o que quebrou e um principio inviolavel, e isso nao pode aparecer diluido entre os
    // demais testes. A tarefa e a mesma que o plugin da plataforma configura, porque so ela tem o
    // classpath da IDE.
    val securityOnly = providers.gradleProperty("securityOnly").isPresent

    useJUnitPlatform {
        if (securityOnly) {
            includeTags("security")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
