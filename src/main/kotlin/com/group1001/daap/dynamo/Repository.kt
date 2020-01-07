package com.group1001.daap.dynamo

import software.amazon.awssdk.core.util.DefaultSdkAutoConstructMap
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Select
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/**
 * Basic CRUD interface for a Dynamo repository
 */
interface DynamoRepository<T : Any> {
    /**
     * Finds an entity based only on it's [hash] key
     */
    fun <HASH : Any> findById(hash: HASH): T?

    /**
     * Finds an entity based on the [hash] key and it's [range] key
     */
    fun <HASH : Any, RANGE : Any> findById(hash: HASH, range: RANGE): T?

    fun <HASH: Any> findAllById(hash: HASH): List<T>

    /**
     * Finds all the entities in the repository
     */
    fun findAll(): List<T>

    /**
     * Saves a single entity into the repository
     */
    fun save(t: T)

    /**
     * Checks the repository for the existence of a entity
     */
    fun <HASH, RANGE> exists(hash: HASH, range: RANGE? = null): Boolean

    fun count(): Long // ?
}

/**
 * Default implementation of a Dynamo repository
 */
@UseExperimental(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST", "ReturnCount")
open class SimpleDynamoRepository<T : Any>(protected val db: DynamoDbClient, private val klass: KClass<T>) : DynamoRepository<T> {
    // Precompute the hash/range keys so we don't have to constantly find them at runtime
    private val hashKeyProperty: KProperty1<T, *> = klass.memberProperties.first { it.hasAnnotation<HashKey>() }
    private val rangeKeyProperty: KProperty1<T, *>? = klass.memberProperties.firstOrNull { it.hasAnnotation<RangeKey>() }

    override fun <HASH : Any> findById(hash: HASH): T? {
        check(rangeKeyProperty == null) { "Cannot search by hash key when both hash and range keys are defined" }

        val keys = mapOf(
            hashKeyProperty.name to propertyToAttribute(hash)
        )

        return findByKeys(keys)
    }

    override fun <HASH : Any, RANGE : Any> findById(hash: HASH, range: RANGE): T? {
        checkNotNull(rangeKeyProperty) { "Cannot search by hash and range keys without a range key defined" }

        val keys = mapOf(
            hashKeyProperty.name to propertyToAttribute(hash),
            rangeKeyProperty.name to propertyToAttribute(range)
        )

        return findByKeys(keys)
    }

    override fun <HASH : Any> findAllById(hash: HASH): List<T> {
        return db.query {
            it.tableName(tableName())
                .select(Select.ALL_ATTRIBUTES)
                .keyConditionExpression("#K1 = :KEY")
                .expressionAttributeNames(mapOf("#K1" to hashKeyProperty.name))
                .expressionAttributeValues(mapOf(":KEY" to propertyToAttribute(hash)))
        }.items().mapNotNull {
            when {
                it.isEmpty() -> null
                else -> buildInstance(it, klass)
            }
        }
    }

    override fun findAll(): List<T> =
        db.scan { it.tableName(tableName()) }.items().map { buildInstance(it, klass) }

    override fun save(t: T) {
        db.putItem { it.tableName(tableName()).item(buildProperties(t)) }
    }

    override fun <HASH, RANGE> exists(hash: HASH, range: RANGE?): Boolean {
        TODO("not implemented")
    }

    override fun count(): Long {
        TODO("not implemented")
    }

    protected fun tableName() = klass.simpleName

    private fun findByKeys(keys: Map<String, AttributeValue>): T? {
        val item = db.getItem { it.tableName(tableName()).key(keys) }.item()
        return when {
            item.isEmpty() -> null
            else -> buildInstance(item, klass)
        }
    }

    /**
     * Takes an object [r] and converts it into a [Map] of Dynamo values
     */
    private fun <R : Any> buildProperties(r: R): Map<String, AttributeValue> {
        return r::class.memberProperties.associate {
            it as KProperty1<R, *>
            val value = it.get(r)
            it.name to propertyToAttribute(value)
        }
    }

    /**
     * Builds an instance of a [klass] based on the contents of the [item] map
     */
    protected fun <R : Any> buildInstance(item: Map<String, AttributeValue>, klass: KClass<R>): R {
        val instance = klass.createInstance()
        item.forEach { (key, value) ->
            val prop = klass.memberProperties.first { it.name == key }
            prop.isAccessible = true
            prop.javaField!!.set(instance, attributeToProperty(value, prop.returnType.javaType))
        }

        return instance
    }

    /**
     * Serializes a property into a Dynamo property
     */
    private fun propertyToAttribute(value: Any?): AttributeValue {
        val builder = AttributeValue.builder()
        return when (value) {
            null -> builder.nul(true).build()
            is List<*> -> builder.l(value.map { propertyToAttribute(it) }).build()
            is Map<*, *> -> builder.m(value.map { it.key.toString() to propertyToAttribute(it.value) }.associate { it }).build()
            else -> {
                val converter = TypeRegistry.findConverter(value.javaClass) as Converter<Any>?
                if (converter != null) {
                    return converter.serialize(value)
                }

                builder.m(buildProperties(value)).build()
            }
        }
    }

    /**
     * Deserializes a Dynamo property and converts it into something useful
     */
    private fun attributeToProperty(attr: AttributeValue, prop: Type): Any? {
        if (attr.isNull()) {
            return null
        }

        if (prop is ParameterizedType) {
            return when(prop.rawType) {
                Map::class.java -> attr.m().mapValues {
                    attributeToProperty(it.value, prop.actualTypeArguments[1])
                }
                List::class.java -> attr.l().map {
                    attributeToProperty(it, prop.actualTypeArguments[0])
                }
                else -> null
            }
        } else if (prop !is Class<*>) {
            return null
        }

        // Otherwise check the registry for a converter and process it
        val converter = TypeRegistry.findConverter(prop)
        if (converter != null) {
            return converter.deserialize(attr)
        }

        return buildInstance(attr.m(), prop.kotlin)
    }
}
