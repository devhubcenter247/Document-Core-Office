plugins {
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

// Coordinates of the SDK as integrators add it: implementation("com.editor:docsdk:<version>")
group = "com.editor"
version = "1.1.8"

android {
    namespace = "com.wxiwei.office"
    compileSdk = 37
    ndkVersion = "29.0.13846066"

    defaultConfig {
        minSdk = 26
        // apps using the SDK need compileSdk 36 (what its AndroidX dependencies ask), not 37
        aarMetadata {
            minCompileSdk = 36
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        // release: what the app of this project builds against; the app's R8 shrinks it with consumer-rules.pro
        // publish: the AAR integrators get, with obfuscated classes: only the API in com.editor.docsdk keeps its names
        create("publish") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "consumer-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir("src/main/jni/lib")
    }
    publishing {
        // no sources and no Javadoc jar: integrators get the compiled, obfuscated AAR only
        singleVariant("publish")
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "docsdk"
            afterEvaluate { from(components["publish"]) }
            pom {
                name.set("DocSDK")
                description.set("View PDF, Word, Excel and PowerPoint documents and process PDFs on Android.")
            }
        }
    }
    repositories {
        // ./gradlew :android_document:publishReleasePublicationToSdkRepository → <project>/sdk-repo
        maven {
            name = "sdk"
            url = uri(rootProject.layout.projectDirectory.dir("sdk-repo"))
        }
        // GitHub Packages repository (publish with: -Pdocsdk.githubRepo=<owner>/<repo>, GITHUB_ACTOR and GITHUB_TOKEN)
        maven {
            name = "github"
            val repo = (findProperty("docsdk.githubRepo") as String?).takeIf { !it.isNullOrBlank() }
                ?: System.getenv("GITHUB_REPOSITORY")
                ?: "owner/repo"
            url = uri("https://maven.pkg.github.com/$repo")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (repository.name == "github") {
        doFirst {
            val repoProp = (findProperty("docsdk.githubRepo") as String?).takeIf { !it.isNullOrBlank() }
                ?: System.getenv("GITHUB_REPOSITORY")
            if (repoProp == null) {
                error("Missing docsdk.githubRepo property or GITHUB_REPOSITORY environment variable. Example: ./gradlew :android_document:publishReleasePublicationToGithubRepository -Pdocsdk.githubRepo=owner/repo")
            }
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // the published SDK must be readable by apps on Kotlin 2.3.20 and later
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        // Java classes of the engine implement Kotlin interfaces with default methods
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
    }
    coreLibrariesVersion = "2.3.20"
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    // api: DocumentTools has suspend functions that integrators call from coroutines
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // OCR through Google Play services: its model is downloaded by the device, not packaged in the app
    api("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    testImplementation(libs.junit)
    // reads the encrypted copies of PdfEncryptorTest back, independently of pdfium
    testImplementation("org.apache.pdfbox:pdfbox:3.0.5")
    androidTestImplementation(libs.androidx.junit)
    // builds the form PDFs of the instrumented tests
    androidTestImplementation("com.tom-roush:pdfbox-android:2.0.27.0")
    androidTestImplementation(libs.androidx.espresso.core)
}
