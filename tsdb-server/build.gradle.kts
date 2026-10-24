plugins {
    id("tsdb.java-conventions")
    application
}

dependencies {
    implementation(project(":tsdb-core"))
    implementation(project(":tsdb-proto"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.slf4j.jdk.platform.logging)

    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
}

// installDist собирает build/install/tsdb-server: bin/ со скриптом запуска и lib/ — его копирует Docker-образ
application {
    mainClass = "org.tsdb.server.Main"
}
