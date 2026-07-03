import org.gradle.api.JavaVersion

object Configs {
    const val compileSdk = 36
    const val minSdk = 35
    const val targetSdk = 36

    const val namespace = "com.eli.mfgx"
    const val applicationId = "com.eli.mfgx"
    const val versionCode = 220
    const val versionName = "0.2.20"

    val javaVersion = JavaVersion.VERSION_11
    const val jvmTarget = "11"
}
