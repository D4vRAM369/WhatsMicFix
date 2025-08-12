plugins {
    id("com.android.application") version "8.12.0" apply false
    kotlin("android") version "1.9.24" apply false
}

tasks.register("cleanAll") {
    doLast { delete(rootProject.buildDir) }
}
