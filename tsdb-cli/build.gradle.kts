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

    // сквозные тесты: команды CLI против настоящего TimeSeriesServiceImpl в той же JVM
    testImplementation(project(":tsdb-server"))
    testImplementation(libs.grpc.inprocess)
}

application {
    mainClass = "org.tsdb.cli.Main"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")
}
