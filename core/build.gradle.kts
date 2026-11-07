plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)

    jvm()

    // iOS 目标只做声明，本机（Windows）不参与编译；iOS 侧直接消费同一份 commonMain 代码。
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
