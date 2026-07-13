package com.chattriggers.ctjs.internal.utils

import com.chattriggers.ctjs.internal.launch.Descriptor
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.reflect.KClass

data class ModVersion(
    val majorVersion: Int,
    val minorVersion: Int,
    val patchLevel: Int,
) : Comparable<ModVersion> {
    override fun compareTo(other: ModVersion) = compareValuesBy(this, other, ModVersion::majorVersion, ModVersion::minorVersion, ModVersion::patchLevel)
}

fun String.toVersion(): ModVersion {
    val semver = substringBefore('-')
    val split = semver.split(".").map(String::toInt)
    return ModVersion(split.getOrElse(0) { 0 }, split.getOrElse(1) { 0 }, split.getOrElse(2) { 0 })
}

fun String.toIdentifier(): Identifier {
    return Identifier.parse(if (':' in this) this else "minecraft:$this")
}

fun String.urlEncode() = URLEncoder.encode(this, Charset.defaultCharset())

// A helper function that makes the intent explicit and reduces parens
inline fun <reified T> Any.asMixin() = this as T

inline fun <reified T> NativeObject?.get(key: String): T? {
    return this?.get(key) as? T
}

inline fun <reified T> NativeObject?.getOption(key: String, default: T): T {
    return this?.get(key) as? T ?: default
}

// Note: getOrDefault<Number>(...).toInt/Double/Float() should be preferred
//       over getOrDefault<Int/Double/Float>(...), as the exact numeric type
//       of numeric properties depends on Rhino internals
inline fun <reified T> NativeObject?.getOrDefault(key: String, default: T): T {
    return this?.get(key) as? T ?: default
}

fun NativeObject?.getOrNull(key: String): Any? {
    return this?.get(key).takeIf { it != Scriptable.NOT_FOUND }
}

fun Double.toRadians() = this * Mth.DEG_TO_RAD
fun Float.toRadians() = this * Mth.DEG_TO_RAD
fun Double.toDegrees() = this * Mth.RAD_TO_DEG
fun Float.toDegrees() = this * Mth.RAD_TO_DEG

fun KClass<*>.descriptorString(): String = java.descriptorString()
fun KClass<*>.descriptor() = Descriptor.Object(descriptorString())
