plugins {
    id("tsdb.java-conventions")
}

dependencies {
    implementation(project(":tsdb-core"))
    implementation(project(":tsdb-proto"))
}