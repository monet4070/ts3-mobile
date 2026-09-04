import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Lock the resolved dependency graph of every configuration in this module to
// the committed gradle.lockfile. The dependencyLocking block alone only sets
// policy; resolutionStrategy.activateDependencyLocking() is what actually
// attaches a lock state to each configuration — without it no lockfile is
// written and STRICT mode is never enforced. See
// docs/decisions/0004-take-ownership-of-ts3j-dependency.md (Step 2).
dependencyLocking {
    lockMode.set(LockMode.STRICT)
}

configurations.all {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    implementation(libs.ts3j)
    implementation(libs.kotlinx.coroutines.core)

    // Pin the exact resolved versions of ts3j's transitive dependencies so a
    // consumer, BOM, or upstream ts3j bump cannot silently move them. ts3j
    // declares these as compile dependencies; the constraints below cap each
    // one at the currently-resolved version, so any drift surfaces here as a
    // deliberate constraint update rather than an unnoticed resolution to a
    // higher version. The committed gradle.lockfile (see the
    // dependencyLocking block below) locks the full resolution graph as a
    // second layer; these constraints remain as readable, review-visible
    // intent for the four artifacts ts3j pulls in. See
    // docs/decisions/0004-take-ownership-of-ts3j-dependency.md.
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15on:1.67")
        implementation("commons-lang:commons-lang:2.6")
        implementation("dnsjava:dnsjava:2.1.8")
        implementation("org.ini4j:ini4j:0.5.1")
    }

    testImplementation(libs.junit)
}
