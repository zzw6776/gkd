package li.gkd.gradle

import com.android.build.api.dsl.ApkSigningConfig
import java.io.File
import java.util.Properties
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project

// Keep the result independent of AGP's runtime classloader as well.
class GkdSigningConfigs<T>(val gkd: T, val play: T)

// Inline keeps AGP type access in the caller's plugin classloader.
@Suppress("NOTHING_TO_INLINE")
inline fun Project.getOrCreateSigningConfigs(
    configs: NamedDomainObjectContainer<out ApkSigningConfig>,
): GkdSigningConfigs<ApkSigningConfig> {
    val signingPropertiesFile =
        buildProperty("GKD_SIGNING_PROPERTIES").orNull?.let(rootProject::file)
            ?: rootProject.file("signing.properties").takeIf(File::isFile)
    val signingProperties =
        Properties().apply {
            signingPropertiesFile?.inputStream()?.use { load(it) }
        }
    // Preserve each signing property's existing relative-path base.
    val gkd =
        resolveSigningConfig(
            configs,
            "gkd",
            "GKD",
            rootProject.file("gkd-android"),
            configs.getByName("debug"),
            signingPropertiesFile,
            signingProperties,
        )
    val play =
        resolveSigningConfig(
            configs,
            "play",
            "PLAY",
            projectDir,
            gkd,
            null,
            Properties(),
        )
    return GkdSigningConfigs(gkd, play)
}

@PublishedApi
@Suppress("NOTHING_TO_INLINE")
internal inline fun Project.resolveSigningConfig(
    configs: NamedDomainObjectContainer<out ApkSigningConfig>,
    name: String,
    prefix: String,
    baseDir: File,
    fallback: ApkSigningConfig,
    signingPropertiesFile: File?,
    signingProperties: Properties,
): ApkSigningConfig {
    configs.findByName(name)?.let {
        return it
    }
    val configuredPath = buildProperty("${prefix}_STORE_FILE").orNull
    val propertiesPath = signingProperties.firstValue("keystore.file")
    val storeFile =
        when {
            configuredPath != null -> baseDir.resolve(configuredPath)
            propertiesPath != null -> signingPropertiesFile?.parentFile?.resolve(propertiesPath)
            else -> null
        } ?: return fallback
    return configs.create(name).apply {
        this.storeFile = storeFile
        storePassword =
            buildProperty("${prefix}_STORE_PASSWORD").orNull
                ?: signingProperties.firstValue(
                    "keystore.password",
                    "storePassword",
                    "store.password",
                )
        keyAlias =
            buildProperty("${prefix}_KEY_ALIAS").orNull
                ?: signingProperties.firstValue("key.alias", "keyAlias")
        keyPassword =
            buildProperty("${prefix}_KEY_PASSWORD").orNull
                ?: signingProperties.firstValue("key.password", "keyPassword")
    }
}

@PublishedApi
internal fun Properties.firstValue(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
    }
