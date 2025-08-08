import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import java.net.URI

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.12.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")
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

//Fetch the opencv subproject, and apply the kotlin-android plugin
//to guarantee the extension exists after evaluation.
subprojects.first { it.name == "opencv" }.run {
    plugins.apply("kotlin-android")
    afterEvaluate {
        //Sets its jvmToolchain explicitly.
        kotlinExtension.jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }
}

tasks.register("clean", Delete::class.java) {
    delete(layout.buildDirectory)
}