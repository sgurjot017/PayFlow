plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.payflow.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.payflow.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "PAYFLOW_API_BASE_URL",
            "\"${providers.gradleProperty("PAYFLOW_API_BASE_URL").orElse("https://api.payflow.example/").get()}\"",
        )
        buildConfigField(
            "String",
            "STRIPE_PUBLISHABLE_KEY",
            "\"${providers.gradleProperty("STRIPE_PUBLISHABLE_KEY").orElse("").get()}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "PAYFLOW_DEV_BEARER_TOKEN",
                "\"${providers.gradleProperty("PAYFLOW_DEV_BEARER_TOKEN").orElse("").get()}\"",
            )
        }
        release {
            buildConfigField("String", "PAYFLOW_DEV_BEARER_TOKEN", "\"\"")
        }
    }

}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.stripe.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
