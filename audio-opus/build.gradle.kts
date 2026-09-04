plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.ts3mobile.audio.opus"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Lock the resolved dependency graph of every configuration to the committed
// gradle.lockfile. The dependencyLocking block only sets policy;
// resolutionStrategy.activateDependencyLocking() is what attaches the lock
// state to each configuration. See docs/decisions/0004-take-ownership-of-ts3j-dependency.md.
dependencyLocking {
    lockMode.set(LockMode.STRICT)
}

configurations.all {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    implementation(project(":ts3-protocol"))
    implementation(libs.opus)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
