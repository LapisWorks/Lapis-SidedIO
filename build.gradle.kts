import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    kotlin("jvm") version "2.3.10"
    idea
    `maven-publish`
    id("com.gradleup.shadow") version "9.2.2"
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = project.properties["group"]!!

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://repo.xenondevs.xyz/releases") {
        name = "InvUI"
    }
    maven("https://repo.codemc.io/repository/maven-releases/") {
        name = "CodeMC"
    }
}

val rebarVersion = project.properties["rebar.version"] as String
val minecraftVersion = project.properties["minecraft.version"] as String

dependencies {
    // Paper 开发包（API + NMS）
    paperweight.paperDevBundle("$minecraftVersion.build.+")

    // Rebar：compileOnly，运行时由服务器上的 Rebar 插件提供
    compileOnly("io.github.pylonmc:rebar:$rebarVersion")

    // RebarWrench：软依赖，只在编译期提供 API 形状（见 WrenchBridge 的反射桥）。
    // 运行时由服务器上装不装扳手决定是否生效，所以这里不写进 depend。
    compileOnly("io.github.lijinhong11:RebarWrench:1.0.4")

    // 界面库。版本必须跟 Rebar 实际带的一致 —— InvUI 是 Rebar 的 paper-libraries
    // 依赖，运行时用的是它那一份。之前这里写 2.1.1 而 Rebar 带 2.3.0，
    // 编译期看到的是旧接口，容易踩"编译过了运行炸"的坑。
    // （Rebar 0.44.4-26.2 的 paper-libraries.json 里是 invui 2.3.0。）
    compileOnly("xyz.xenondevs.invui:invui:2.3.0")

    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 单元测试要能引用 LogisticGroup / LogisticSlot / LogisticGroupType 的类型。
    // 这几个类本身不碰 Bukkit（LogisticGroup 是个普通构造器，枚举是纯枚举），
    // 所以放在测试 classpath 上不需要起服务端。
    testImplementation("io.github.pylonmc:rebar:$rebarVersion")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("25"))
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier = ""
}

// shadowJar 的清空分类器让它和普通 jar 产物同名（都叫 Sided-IO-0.1.0.jar），
// 两者会互相覆盖、也会让 publish 报"看不见的依赖"。真正的交付物只有 shadowJar 那个，
// 所以把普通 jar 关掉。
tasks.jar {
    enabled = false
}

bukkit {
    name = project.name
    main = project.properties["main-class"] as String
    version = project.version.toString()
    apiVersion = "26.2"
    depend = listOf("Rebar")
    // 软依赖：装了 RebarWrench 就能拿扳手右键切面；没装就用 /sidedio 面板 + 机器自己的 GUI 按钮。
    // 故意不写成 depend —— RebarWrench 硬依赖 Pylon 且是 GPL-3.0，
    // Sided-IO 要保持"只依赖 Rebar"的定位（见 WrenchBridge 的类文档）。
    softDepend = listOf("RebarWrench")
    load = BukkitPluginDescription.PluginLoadOrder.STARTUP

    authors = listOf("mc506lw")

    // 面配置面板 / 查询
    commands {
        register("sidedio") {
            description = "打开面配置面板，或查看六个面的模式"
            usage = "/sidedio [gui|info|wrench|lang]"
            aliases = listOf("sio")
        }
    }
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier = "sources"
    from(sourceSets.main.get().allSource)
}

// 发布到本地 Maven（~/.m2）**和** GitHub Packages，供其它附属（Iron-Furnaces 等）compileOnly 依赖。
// 本机 Gradle 构建走 --offline，装到 mavenLocal 正好能被离线解析到。
//   ./gradlew publishToMavenLocal
publishing {
    publications {
        create<MavenPublication>("maven") {
            // shadowJar 的产物就是运行时要用的那个 jar（已把分类器清空）
            artifact(tasks.named("shadowJar")) {
                classifier = ""
            }
            // 源码一起发布：下游 IDE 里能直接跳进来读 Sided-IO 的代码
            artifact(tasks.named("sourcesJar"))
        }
    }

    // GitHub Packages —— 给 **CI 和其它仓库** 用（本机开发仍然走 mavenLocal）。
    //
    // 为什么不是 Maven Central：Central 要 Sonatype 账号 + 命名空间校验 + GPG 签名，
    // 这些凭据只有人有；GitHub Packages 用 Actions 自带的 `GITHUB_TOKEN` 就够，
    // 不引入任何新的密钥。
    //
    // ⚠️ 凭据拿不到时**整个仓库都不注册** —— 不然本机 `./gradlew build` 会因为
    // 一个空凭据的仓库配置直接报错（构建根本走不到编译）。
    repositories {
        val gprUser = System.getenv("GITHUB_ACTOR") ?: (findProperty("gpr.user") as String?)
        val gprKey = System.getenv("GITHUB_TOKEN") ?: (findProperty("gpr.key") as String?)
        if (gprUser != null && gprKey != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/LapisWorks/Lapis-SidedIO")
                credentials {
                    username = gprUser
                    password = gprKey
                }
            }
        }
    }
}
