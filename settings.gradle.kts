plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ITMO-TimeSeriesDB"

// Лабы 1-2 — один модуль, с лабы 3:
// include("tsdb-core", "tsdb-proto", "tsdb-server", "tsdb-cluster", "tsdb-cli")
