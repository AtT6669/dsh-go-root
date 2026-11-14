package io.github.ccch1mneyyy.dsh_app.root

import io.github.ccch1mneyyy.dsh_app.BuildConfig

/** Build-flavor switch. The root fork sets ROOT_RUNTIME=true and has no non-root fallback. */
object RootBuild {
    val required: Boolean = BuildConfig.ROOT_RUNTIME
    const val APPLICATION_ID = "io.github.ccch1mneyyy.dsh_root"
}
