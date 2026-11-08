plugins {
    id("tsdb.java-conventions")
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    // grpc-protobuf тянет protobuf-java 3.x, а код от protoc 4.x требует runtime той же версии
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    // @javax.annotation.Generated в сгенерированном коде на Java 9+
    compileOnly(libs.tomcat.annotations)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        // java-классы сообщений генерируются по умолчанию, grpc добавляет stub'ы сервиса
        all().configureEach { plugins { create("grpc") } }
    }
}

// сгенерированный код не проходит наш doclint и сыпет -Xlint-предупреждениями
tasks.javadoc { enabled = false }
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.remove("-Xlint:all") }
