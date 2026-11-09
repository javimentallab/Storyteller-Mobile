import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.javimetallab.storyteller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.javimetallab.storyteller"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.3"
    }

    // The release key lives outside the repo; keystore.properties (gitignored)
    // points at it. Without that file, release builds come out unsigned.
    val keystoreProps = rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    // No encrypted dependency blob in the APK: F-Droid rejects it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The licences of the bundled libraries ask for their notices to travel with the binary:
// the root NOTICE goes into the APK as assets/NOTICE.
abstract class NoticeAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        notice.get().asFile.copyTo(outputDir.file("NOTICE").get().asFile, overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val task = tasks.register<NoticeAsset>("${variant.name}NoticeAsset") {
        notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task, NoticeAsset::outputDir)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.jsoup)
    // Media3 asks for an older Guava, with a known vulnerability; this version wins over it.
    implementation(libs.guava)
    // Bouncy Castle is 11 MB of jars, and only opens PDFs locked to a certificate.
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}

// On the JVM PdfBox looks for its data on the classpath, and an AAR's assets aren't there.
val pdfboxAar: Configuration by configurations.creating { isTransitive = false }
dependencies { pdfboxAar(libs.pdfbox.android) }
val pdfboxAssets = tasks.register<Sync>("pdfboxAssets") {
    from({ zipTree(pdfboxAar.singleFile) }) {
        include("assets/**")
        eachFile { path = path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("pdfboxAssets"))
}
tasks.withType<Test>().configureEach {
    dependsOn(pdfboxAssets)
    doFirst { classpath += files(layout.buildDirectory.dir("pdfboxAssets")) }
}
