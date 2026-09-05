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

    }

    // A plataforma IntelliJ ja fornece kotlinx-serialization em runtime. Empacotar uma segunda
    // copia geraria classes duplicadas no classloader do plugin.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // compileOnly na producao (a plataforma fornece), mas os testes rodam fora da IDE.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
