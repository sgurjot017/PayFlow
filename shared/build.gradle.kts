plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val enableIosTargets = providers.gradleProperty("PAYFLOW_ENABLE_IOS_TARGETS")
    .map(String::toBoolean)
    .getOrElse(false)

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.payflow.shared"
        compileSdk = 37
        minSdk = 24
        withHostTestBuilder {}
    }

    jvm()

    if (enableIosTargets) {
        listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
            target.binaries.framework {
                baseName = "PayFlowShared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
