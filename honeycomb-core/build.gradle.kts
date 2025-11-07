plugins {
    `java-library`
    application
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass = "com.honeycomb.core.HoneycombCLI"
}
