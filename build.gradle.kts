plugins {
    java
}

group = "org.tsdb"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    // битые @link ломают сборку, отсутствующие комментарии — нет
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ./gradlew demo [-PdemoDir=data/demo]
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Демо: 1000 серий за 4 часа, сброс в блоки, запрос, сжатие"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.tsdb.Demo"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")
    project.findProperty("demoDir")?.let { args(it.toString()) }
}

// ./gradlew bench [-PbenchArgs="hours=2 warmup=0"]
tasks.register<JavaExec>("bench") {
    group = "application"
    description = "Бенчмарк: 1000 серий × 15 с × 24 ч — запись, размер на диске, запрос"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.tsdb.bench.Bench"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")
    maxHeapSize = "2g"
    project.findProperty("benchArgs")?.let { args(it.toString().split(" ").filter(String::isNotBlank)) }
}
