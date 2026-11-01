import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 发布签名 ---------------------------------------------------------------
// android/keystore.properties（gitignore）里放 storeFile / storePassword /
// keyAlias / keyPassword。文件不存在时（比如别人 clone 下来只跑 debug）就退化成
// 不签名的 release——assembleRelease 仍能出包，只是不能直接装。
// 之所以不硬编码：签名材料一旦进仓库，等于把应用的所有权送人。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.divelog.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.divelog.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        ndk {
            abiFilters += listOf("arm64-v8a")   // 只编真机架构，加快构建
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有 keystore.properties 时保持不签名（assembleRelease 仍能出包）
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // 便于观察日志
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // 单元测试可访问 android.jar（Robolectric 需要真实资源）
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ---- 示例数据只进 debug 包 ---------------------------------------------------
// 仓库根 data/ 里放着 253 条真实潜水记录（含 3 MB 逐点剖面）。那是个人潜水轨迹，
// **发布包不能带**：release 变体压根不挂载这份数据，装出来是空库，
// 靠蓝牙同步或导入 .bin 自己积累。
//
// 做法是"构建时从 data/ 拷一份"而不是"在模块里存一份副本"，避免两份 CSV 各自漂移。
// samples.csv 不入库，clone 下来没有它 → 这里拷不到就只带 dives.csv（没曲线）。
abstract class StageDemoAssets : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = output.get().asFile
        out.mkdirs()
        // 清掉上一轮残留：改了 data/ 之后不能留着旧样本
        out.listFiles()?.forEach { it.delete() }
        sources.files.filter { it.isFile }.forEach {
            it.copyTo(File(out, it.name), overwrite = true)
        }
    }
}

val stageDemoAssets = tasks.register<StageDemoAssets>("stageDemoAssets") {
    description = "把仓库根 data/*.csv 拷进 debug 包的 assets（release 不用）"
    sources.from(fileTree(rootProject.projectDir.resolve("../data")) { include("*.csv") })
    output.set(layout.buildDirectory.dir("generated/demoAssets"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageDemoAssets, StageDemoAssets::output)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- L1: JVM 单元测试（无需手机，秒级）----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ---- L2: 仪器测试（真机/模拟器，自动装 APK + 抓 logcat 崩溃）----
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // Compose 测试版本要由 BOM 统一管理（AndroidTest classpath 不继承 main 的 BOM）
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// connectedAndroidTest 的输出目录由设备名命名（如 "SM-S9110 - 16"），
// Gradle 8 会对任务输出做快照哈希，在这类目录上偶发
// "Failed to create MD5 hash for file content" 导致构建失败（非测试本身失败）。
// 按官方建议把该任务标记为不追踪状态。
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }
    .configureEach {
        doNotTrackState("真机测试输出依赖设备状态，不适合做增量快照")
    }
