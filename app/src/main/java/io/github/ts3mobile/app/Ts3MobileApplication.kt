package io.github.ts3mobile.app

import android.app.Application
import io.github.ts3mobile.app.identity.IdentityRepository
import io.github.ts3mobile.app.identity.IdentityVault

/** Explicit process container shared by all Android components, including replacement instances. */
class Ts3MobileApplication : Application() {
    internal val identities by lazy { IdentityRepository(IdentityVault(this).storage) }
}
