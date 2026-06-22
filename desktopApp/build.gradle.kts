import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "tim.private_sshs.MainKt"

        nativeDistributions {
            // Форматы, которые мы хотим собрать
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PrivateSshTerminal"
            packageVersion = "1.0.3"
            vendor = "Tim Private Software"

            // ВАЖНО: Лицензия указывается прямо здесь, на верхнем уровне nativeDistributions!
            licenseFile.set(project.file("license"))

            // Специфичные настройки только для операционной системы Windows
            windows {
                iconFile.set(project.file("launcher.ico"))
                shortcut = true // Ярлык на Рабочем столе
                menu = true     // Ярлык в меню Пуск
            }
        }
    }
}
