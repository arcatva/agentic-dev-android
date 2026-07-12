plugins {
    id("agentic.android.library")
    // SessionUiStore persists @Serializable SessionUi as JSON — without this plugin the annotation is
    // inert (no generated serializer) and encode/decode throws "Serializer not found" at runtime.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.agentic.core.data"
}

dependencies {
    // api: repository public signatures expose network wire types (Session, Outcome, ...) and
    // domain types (Node, ...) — consumers must see them without redeclaring the modules.
    api(project(":core:model"))
    api(project(":core:network"))
    // Correct clean-architecture edge: data consumes domain vocabulary (Status.TERMINAL, ...).
    api(project(":core:domain"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // AuthRepository registers/queries the FCM device token.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
