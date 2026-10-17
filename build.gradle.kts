plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.diffplug.spotless") version "7.2.1"
}
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint("1.7.1")
    }
}
val pmdRuntime by configurations.creating
dependencies {
    pmdRuntime("net.sourceforge.pmd:pmd-cli:7.28.0")
    pmdRuntime("net.sourceforge.pmd:pmd-kotlin:7.28.0")
}
tasks.register<JavaExec>("pmdKotlin") {
    group = "verification"
    description = "PMD 7.28 Kotlin rules on all product Kotlin sources; violations/parser errors fail."
    classpath = pmdRuntime
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    val sources = fileTree("app/src") { include("**/*.kt") }
    inputs.files(sources)
    inputs.file("config/pmd/kotlin.xml")
    val report = layout.buildDirectory.file("reports/pmd/kotlin.xml")
    outputs.file(report)
    doFirst {
        check(!sources.isEmpty) { "No Kotlin product sources" }
        report
            .get()
            .asFile.parentFile
            .mkdirs()
    }
    args(
        "check",
        "--dir",
        "app/src",
        "--rulesets",
        "config/pmd/kotlin.xml",
        "--format",
        "xml",
        "--report-file",
        report.get().asFile.path,
        "--no-cache",
        "--fail-on-violation=true",
        "--fail-on-error=true",
    )
}
