plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ITMO-TimeSeriesDB"

// tsdb-cluster появится в лабе 4
include("tsdb-core", "tsdb-proto", "tsdb-server", "tsdb-cli")
