plugins {
    id("agentic.jvm.library")
}

dependencies {
    // Domain is a LEAF: pure Kotlin, zero project dependencies. Ports in Ports.kt are
    // implemented by outer layers (network DTOs); the arrow always points inward.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
