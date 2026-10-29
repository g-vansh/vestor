# VestorOS updates

This branch is written by `vestoros/publish.sh` on the `lucent` branch and replaced on every
publish. Vestor TVs read `vestoros/update.json` and download the APK it names. They install it
only after checking its size, SHA-256, package, version and signing key, and only when you
confirm on the TV. The APK before the current one is kept for going back a version.

The source of these builds is the `lucent` branch of this repository (`vestoros/`), under the
same GPL-3.0 license as the rest of it.
