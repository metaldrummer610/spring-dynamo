package com.group1001.daap.dynamo

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Select
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/**
 * CRUD interface for an Entity that only has a Partition Key [P]
 */
interface SimpleKeyRepository<T, P> {
    /**
     * Finds an entity based only on it's partition key
     */
    fun findById(partition: P): T?

    fun findAllById(partition: P): List<T>

    fun findAllBetween(start: P, end: P): List<T>

    /**
     * Finds all the entities in the repository
     */
    fun findAll(): List<T>

    /**
     * Saves a single entity into the repository
     */
    fun save(t: T)

    fun count(): Long // ?
}

/**
 * Extension interface for Entities that have both a Partition Key [P] and Sort Key [S]
 */
interface CompositeKeyRepository<T, P, S> : SimpleKeyRepository<T, P> {
    /**
     * Finds an entity based on the [partition] key and it's [sort] key
     */
    fun findById(partition: P, sort: S): T?

    /**
     * Checks the repository for the existence of a entity
     */
    fun exists(partition: P, sort: S? = null): Boolean

    fun findAllBetween(partition: P, start: S, end: S): List<T>
}

/**
 * Default implementation of a Dynamo repository
 */
@UseExperimental(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST", "ReturnCount")
open class DefaultSimpleKeyRepository<T : Any, P>(protected val db: DynamoDbClient, private val klass: KClass<T>) : SimpleKeyRepository<T, P> {
    // Precompute the hash/range keys so we don't have to constantly find them at runtime
    protected val partitionKeyProperty: KProperty1<T, *> = klass.memberProperties.first { it.hasAnnotation<PartitionKey>() }

    override fun findById(partition: P): T? {
        val keys = mapOf(
            partitionKeyProperty.name to propertyToAttribute(partition)
        )

        return findByKeys(keys)
    }

    override fun findAllById(partition: P): List<T> {
        return db.query {
            it.tableName(tableName())
                .select(Select.ALL_ATTRIBUTES)
                .keyConditionExpression("#K1 = :KEY")
                .expressionAttributeNames(mapOf("#K1" to partitionKeyProperty.name))
                .expressionAttributeValues(mapOf(":KEY" to propertyToAttribute(partition)))
        }.items().mapNotNull {
            when {
                it.isEmpty() -> null
                else -> buildInstance(it, klass)
            }
        }
    }

    override fun findAllBetween(start: P, end: P): List<T> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun findAll(): List<T> =
        db.scan { it.tableName(tableName()) }.items().map { buildInstance(it, klass) }

    override fun save(t: T) {
        db.putItem { it.tableName(tableName()).item(buildProperties(t)) }
    }

    override fun count(): Long {
        TODO("not implemented")
    }

    protected fun tableName() = klass.simpleName

    protected fun findByKeys(keys: Map<String, AttributeValue>): T? {
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
    protected fun propertyToAttribute(value: Any?): AttributeValue {
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
            return when (prop.rawType) {
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

@UseExperimental(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
open class DefaultCompositeKeyRepository<T : Any, P, S>(db: DynamoDbClient, klass: KClass<T>) : DefaultSimpleKeyRepository<T, P>(db, klass), CompositeKeyRepository<T, P, S> {
    private val sortKeyProperty: KProperty1<T, *> = klass.memberProperties.first { it.hasAnnotation<SortKey>() }

    override fun findById(partition: P): T? {
        throw IllegalStateException("Cannot use Partition lookup when Sort key is defined!")
    }

    override fun findById(partition: P, sort: S): T? {
        val keys = mapOf(
            partitionKeyProperty.name to propertyToAttribute(partition),
            sortKeyProperty.name to propertyToAttribute(sort)
        )

        return findByKeys(keys)
    }

    override fun findAllBetween(partition: P, start: S, end: S): List<T> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun exists(partition: P, sort: S?): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
