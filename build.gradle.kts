plugins {
    java
}

group = "cn.mcfhm"
version = "0.1.0"

repositories {
    maven("https://repo.purpurmc.org/snapshots")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

// 本地 API 路径放在 gradle.properties 里，仓库里不存具体路径
val localApi = providers.gradleProperty("purpurApiPath").orNull?.let { file(it) }
val localLibs = providers.gradleProperty("localLibsPath").orNull?.let { file(it) }

dependencies {
    if (localApi != null && localApi.isFile) {
        compileOnly(files(localApi))
        if (localLibs != null && localLibs.isDirectory) {
            compileOnly(
                fileTree(localLibs) {
                    include(
                        "net/kyori/**/*.jar",
                        "com/google/guava/**/*.jar",
                        "com/google/code/gson/**/*.jar",
                        "net/md-5/**/*.jar",
                        "org/jetbrains/**/*.jar",
                        "it/unimi/**/*.jar",
                        "com/mojang/**/*.jar"
                    )
                }
            )
        }
    } else {
        compileOnly("org.purpurmc.purpur:purpur-api:26.3.build.2660-stable")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName = "HotReload-${project.version}.jar"
    exclude("cn/mcfhm/hotreload/agent/**")
}

// 单独出一个 agent，用 -javaagent 挂载后能拿到 Instrumentation
val agentJar by tasks.registering(Jar::class) {
    archiveFileName = "hotreload-agent.jar"
    from(sourceSets.main.get().output) {
        include("cn/mcfhm/hotreload/agent/**")
    }
    manifest {
        attributes(
            "Premain-Class" to "cn.mcfhm.hotreload.agent.HotReloadAgent",
            "Agent-Class" to "cn.mcfhm.hotreload.agent.HotReloadAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

tasks.named("build") {
    dependsOn(agentJar)
}
