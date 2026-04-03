plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
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
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.terminal")
        instrumentationTools()
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.12"
    }

    // Build webview-ui before processing resources
    val buildWebview by registering(Exec::class) {
        workingDir = file("webview-ui")
        commandLine("npm", "run", "build")
        inputs.dir("webview-ui/src")
        inputs.file("webview-ui/package.json")
        inputs.file("webview-ui/vite.config.ts")
        outputs.dir("dist/webview")
    }

    val copyWebview by registering(Copy::class) {
        dependsOn(buildWebview)
        from("dist/webview")
        into("src/main/resources/webview")
    }

    processResources {
        dependsOn(copyWebview)
    }
}
