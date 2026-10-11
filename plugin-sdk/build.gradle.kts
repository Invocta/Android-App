plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "io.github.invocta"
version = "0.1.1"

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from("README.md")
}

android {
    namespace = "com.invocta.pluginsdk"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin { jvmToolchain(11) }

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "plugin-sdk"
                artifact(javadocJar)
                pom {
                    name = "Invocta Android Plugin SDK"
                    description = "Android bound-service protocol for apps that expose actions to Invocta"
                    url = "https://github.com/Invocta/Android-App"
                    licenses {
                        license {
                            name = "GNU General Public License v3.0 or later"
                            url = "https://www.gnu.org/licenses/gpl-3.0.html"
                        }
                    }
                    developers {
                        developer {
                            id = "kunalshah017"
                            name = "Kunal Shah"
                            email = "kunalmanishshah@gmail.com"
                        }
                    }
                    scm {
                        url = "https://github.com/Invocta/Android-App"
                        connection = "scm:git:https://github.com/Invocta/Android-App.git"
                        developerConnection = "scm:git:ssh://git@github.com/Invocta/Android-App.git"
                    }
                }
            }
        }
        repositories {
            maven {
                name = "centralStaging"
                url = uri(layout.buildDirectory.dir("central-staging"))
            }
        }
    }
}
