plugins {
    id("tsdb.java-conventions")
    application
}

dependencies {
    // tsdb-core — ради парсера селекторов org.tsdb.model.SelectorParser
    implementation(project(":tsdb-core"))
    implementation(project(":tsdb-proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.picocli)
}

application {
    mainClass = "org.tsdb.cli.Main"
}
