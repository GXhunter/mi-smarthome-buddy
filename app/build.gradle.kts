plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.hunter.mihomepurifier"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.hunter.mihomepurifier"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.6.3"
    }

    buildFeatures.buildConfig = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.merges += "META-INF/xposed/*"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}

val compilePatchCheck by tasks.registering(JavaCompile::class) {
    source("src/main/java/io/github/hunter/mihomepurifier/CookerBundlePatch.java", "src/test/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("patch-check"))
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("verifyPatch") {
    dependsOn(compilePatchCheck)
    classpath = files(compilePatchCheck.flatMap { it.destinationDirectory })
    mainClass.set("io.github.hunter.mihomepurifier.CookerBundlePatchTest")
    enableAssertions = true
    providers.gradleProperty("cookerBundle").orNull?.let { args(it) }
}
