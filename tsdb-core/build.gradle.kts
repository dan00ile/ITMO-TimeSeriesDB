plugins {
    id("tsdb.java-conventions")
    `java-library`
}

dependencies {
    implementation(libs.jackson.databind)
}

// ./gradlew demo [-PdemoDir=data/demo]
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Демо: 1000 серий за 4 часа, сброс в блоки, запрос, сжатие"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.tsdb.Demo"
    // относительные пути, как и до разбиения на модули, — от корня репозитория
    workingDir = rootDir
    jvmArgs("-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")
    project.findProperty("demoDir")?.let { args(it.toString()) }
}

// ./gradlew bench [-PbenchArgs="hours=2 warmup=0"]
tasks.register<JavaExec>("bench") {
    group = "application"
    description = "Бенчмарк: 1000 серий × 15 с × 24 ч — запись, размер на диске, запрос"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.tsdb.bench.Bench"
    workingDir = rootDir
    jvmArgs("-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")
    maxHeapSize = "2g"
    project.findProperty("benchArgs")?.let { args(it.toString().split(" ").filter(String::isNotBlank)) }
}
