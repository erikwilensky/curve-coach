plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Android ships org.json, so the app gets it from the phone. Plain JVM builds need the jar.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Real-road checks need the internet; run them with -PliveRoads.
    if (!project.hasProperty("liveRoads")) exclude("**/LiveRoadsTest*")
    testLogging { events("failed"); showStandardStreams = project.hasProperty("liveRoads") }
}
