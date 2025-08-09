import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import java.net.URI

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.androidGradleBuildTools)
        classpath(libs.kotlinGradlePlugin)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()

        maven {
            url = URI("https://jitpack.io")
        }
    }
}


tasks.register("clean", Delete::class.java) {
    delete(layout.buildDirectory)
}