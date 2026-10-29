plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    implementation("net.sf.saxon:Saxon-HE:13.0")
    implementation("io.javalin:javalin:7.2.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "eldamo.Main"
    applicationDefaultJvmArgs = listOf("-Xmx1024m")
}

tasks.register<JavaExec>("release") {
    group = "application"
    description = "Writes the published site to build/site"
    mainClass = application.mainClass
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = application.applicationDefaultJvmArgs.toList()
    args("release")
}
