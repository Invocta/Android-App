plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "com.github.Invocta.Android-App"
version = System.getenv("VERSION") ?: "0.1.0"

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
                    scm { url = "https://github.com/Invocta/Android-App" }
                }
            }
        }
    }
}
