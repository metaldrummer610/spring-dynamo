package com.group1001.daap.dynamo

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.reflect.KClass

/**
 * Converter interface used to convert one type to another and back
 */
interface Converter<FROM : Any> {
    /**
     * Serialize from the type to a Dynamo type
     */
    fun serialize(from: FROM): AttributeValue

    /**
     * Deserializes from Dynamo to the type
     */
    fun deserialize(attr: AttributeValue): FROM?

    /**
     * Checks the given [klass] against our type to see if they are compatible
     */
    fun supports(klass: Type): Boolean
}

/**
 * Base implementation of a [Converter] that sets up type checking
 */
abstract class BaseConverter<FROM : Any>(private val fromClass: KClass<FROM>) : Converter<FROM> {
    final override fun serialize(from: FROM): AttributeValue {
        val builder = AttributeValue.builder()
        serializeInternal(from, builder)
        return builder.build()
    }

    final override fun deserialize(attr: AttributeValue): FROM? {
        // Have to do this weird around about way of checking if there's a NULL because of Kotlin's typing system
        if (attr.getValueForField("NUL", Boolean::class.javaObjectType).orElse(false)) {
            return null
        }

        return deserializeInternal(attr)
    }

    protected abstract fun serializeInternal(from: FROM, builder: AttributeValue.Builder)

    protected abstract fun deserializeInternal(attr: AttributeValue): FROM

    override fun supports(klass: Type): Boolean = when (klass) {
        // This is done so that we can support boxed and unboxed primitives
        fromClass.javaObjectType, fromClass.javaPrimitiveType -> true

        // This allows us to check the inner types of typed classes (eg List or Map)
        is ParameterizedType -> klass.actualTypeArguments.contains(fromClass.java)
        else -> false
    }
}

/**
 * Base implementation for all [Number] types. Instances of this simply pass in a function that converts from a String back to the appropriate type
 */
class NumberConverter<T : Number>(
    fromClass: KClass<T>,
    private val f: (to: AttributeValue) -> T
) : BaseConverter<T>(fromClass) {
    override fun deserializeInternal(attr: AttributeValue): T = f(attr)

    override fun serializeInternal(from: T, builder: AttributeValue.Builder) {
        builder.n(from.toString())
    }
}

/**
 * A simple converter that takes in a serialization and deserialization function
 */
class SimpleConverter<FROM : Any>(
    fromClass: KClass<FROM>,
    private val ser: (from: FROM, builder: AttributeValue.Builder) -> Unit,
    private val deser: (attr: AttributeValue) -> FROM
) : BaseConverter<FROM>(fromClass) {
    override fun deserializeInternal(attr: AttributeValue): FROM = deser(attr)
    override fun serializeInternal(from: FROM, builder: AttributeValue.Builder) = ser(from, builder)
}

/**
 * Singleton that contains all the types converters that have been registered with the system
 */
object TypeRegistry {
    // The collection of registered converters available to the system
    private val converters: MutableList<Converter<*>> = mutableListOf()

    fun <T : Any> addConverter(converter: Converter<T>) = converters.add(converter)

    /**
     * Helper that adds a [NumberConverter]
     */
    private inline fun <reified T : Number> addNumberConverter(noinline f: (to: AttributeValue) -> T) =
        addConverter(NumberConverter(T::class, f))

    /**
     * Helper that adds a [SimpleConverter]
     */
    inline fun <reified T : Any> addSimpleConverter(
        noinline ser: (t: T, b: AttributeValue.Builder) -> Unit,
        noinline deser: (attr: AttributeValue) -> T
    ) = addConverter(SimpleConverter(T::class, ser, deser))

    /**
     * Attempts to find a [Converter] for the given [Type]
     */
    fun findConverter(type: Type) = converters.firstOrNull { it.supports(type) }

    init {
        // Add default converters
        addNumberConverter { it.n().toInt() }
        addNumberConverter { it.n().toDouble() }
        addNumberConverter { it.n().toLong() }
        addNumberConverter { it.n().toBigDecimal() }
        addNumberConverter { it.n().toFloat() }
        addSimpleConverter<UUID>({ id, b -> b.s(id.toString()) }, { UUID.fromString(it.s()) })
        addSimpleConverter<String>({ s, b -> b.s(s) }, { it.s() })
        addSimpleConverter<ByteArray>({ bs, b -> b.bs(SdkBytes.fromByteArray(bs)) }, { it.b().asByteArray() })
        addSimpleConverter<LocalDate>({ date, b -> b.s(date.toString()) }, { LocalDate.parse(it.s()) })
        addSimpleConverter<LocalDateTime>({ date, b -> b.s(date.toString()) }, { LocalDateTime.parse(it.s()) })
    }
}
