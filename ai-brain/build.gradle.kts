import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}

// Optional live API smoke test (needs EVE_*_API_KEY env vars; never runs as part of `test`).
tasks.register<JavaExec>("liveSmoke") {
    group = "verification"
    description = "Calls the real Claude/GPT/Gemini APIs with keys from EVE_*_API_KEY environment variables."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.eve.core.live.LiveSmokeKt")
}
