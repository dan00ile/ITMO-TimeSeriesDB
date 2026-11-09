plugins {
    id("tsdb.java-conventions")
    `java-library`
}

dependencies {
    api(project(":tsdb-core"))
    api(project(":tsdb-proto"))
}
