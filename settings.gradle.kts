include(":app")
rootProject.name = "UmaAndroidAutomation"

//Automatic provisioning of a compatible JVM toolchain.
//Convention plugin fetches a jdk into the gradle home directory
//if it doesn't find any compatible ones in its canonical
//OS search paths.
plugins {
    //Settings plugins cannot be declared in version catalog
    id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}
