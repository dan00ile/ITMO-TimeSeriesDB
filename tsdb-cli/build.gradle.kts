plugins {
    id("tsdb.java-conventions")
    application
}

dependencies {
    // tsdb-core — ради SelectorParser и генератора данных org.tsdb.bench.MetricsGenerator
    implementation(project(":tsdb-core"))
    implementation(project(":tsdb-proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.picocli)
}

application {
    mainClass = "org.tsdb.cli.Main"
}
