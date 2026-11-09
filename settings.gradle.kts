plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ITMO-TimeSeriesDB"

// tsdb-cluster — репликация из лабы 4
include("tsdb-core", "tsdb-proto", "tsdb-server", "tsdb-cli", "tsdb-cluster")
