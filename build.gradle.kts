// SPDX-License-Identifier: GPL-3.0-or-later
// Versions are pinned to a known-good combination. Android Studio may offer upgrades later.
// Do those as a separate step, not in the middle of a milestone.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
