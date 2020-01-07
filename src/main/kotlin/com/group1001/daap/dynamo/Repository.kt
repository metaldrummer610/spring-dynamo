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

    /**
     * Finds all the entities that have this [partition] key.
     * For Simple keys, this only returns a single record.
     * For Composite keys, this returns all the records for this key.
     */
    fun findAllById(partition: P): List<T>

    /**
     * Finds all the entities in the repository
     */
    fun findAll(): List<T>

    /**
     * Saves a single entity into the repository
     */
    fun save(t: T)

    /**
     * Gets the list of entities in this repository
     */
    fun count(): Int?

    /**
     * Deletes a single record identified by [partition] key
     */
    fun deleteOne(partition: P)
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
     * Finds the latest entity by [sort] key for this particular [partition]
     */
    fun findLatest(partition: P): T?

    /**
     * Finds all the entities for this [partition]
     */
    fun findAll(partition: P): List<T>

    /**
     * Checks the repository for the existence of a entity
     */
    fun exists(partition: P, sort: S): Boolean

    /**
     * Finds all the entities that fall in the range provided by
     * [start] and [end] for a particular [partition] key
     */
    fun findAllBetween(partition: P, start: S, end: S): List<T>

    /**
     * Deletes a single record identified by [partition] and [sort] keys
     */
    fun deleteOne(partition: P, sort: S)
}

/**
 * Default implementation of a Dynamo repository
 */
@UseExperimental(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST", "ReturnCount")
open class DefaultSimpleKeyRepository<T : Any, P>(protected val db: DynamoDbClient, protected val klass: KClass<T>) : SimpleKeyRepository<T, P> {
    // Precompute the hash/range keys so we don't have to constantly find them at runtime
    protected val partitionKeyProperty: KProperty1<T, *> = klass.memberProperties.first { it.hasAnnotation<PartitionKey>() }

    override fun findById(partition: P): T? {
        val keys = mapOf(
            partitionKeyProperty.name to propertyToAttribute(partition)
        )

        return findByKeys(keys)
    }

    override fun findAllById(partition: P): List<T> = db.query {
        it.tableName(tableName())
            .select(Select.ALL_ATTRIBUTES)
            .keyConditionExpression("#PARTITION = :KEY")
            .expressionAttributeNames(mapOf("#PARTITION" to partitionKeyProperty.name))
            .expressionAttributeValues(mapOf(":KEY" to propertyToAttribute(partition)))
    }.items().mapNotNull { buildInstance(it, klass) }

    override fun findAll(): List<T> = db.scan { it.tableName(tableName()) }.items().mapNotNull { buildInstance(it, klass) }

    override fun save(t: T) {
        db.putItem { it.tableName(tableName()).item(buildProperties(t)) }
    }

    override fun count(): Int? = db.scan { it.tableName(tableName()).select(Select.COUNT) }.count()

    override fun deleteOne(partition: P) {
        db.deleteItem {
            it.tableName(tableName()).key(mapOf(partitionKeyProperty.name to propertyToAttribute(partition)))
        }
    }

    protected fun tableName() = klass.simpleName

    protected fun findByKeys(keys: Map<String, AttributeValue>): T? = db.getItem { it.tableName(tableName()).key(keys) }.item()?.let { buildInstance(it, klass) }

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
    protected fun <R : Any> buildInstance(item: Map<String, AttributeValue>, klass: KClass<R>): R? {
        if (item.isEmpty()) {
            return null
        }

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
            is Enum<*> -> builder.s(value.name).build()
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

        if (prop.isEnum) {
            prop as Class<Enum<*>>
            return prop.enumConstants.firstOrNull { it.name == attr.s() }
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

    override fun findLatest(partition: P): T? = db.query {
        it.tableName(tableName())
            .select(Select.ALL_ATTRIBUTES)
            .limit(1)
            .scanIndexForward(false)
            .keyConditionExpression("#PARTITION = :partition")
            .expressionAttributeNames(mapOf("#PARTITION" to partitionKeyProperty.name))
            .expressionAttributeValues(mapOf(":partition" to propertyToAttribute(partition)))
    }.items().firstOrNull()?.let { buildInstance(it, klass) }

    override fun findAll(partition: P): List<T> = db.query {
        it.tableName(tableName())
            .select(Select.ALL_ATTRIBUTES)
            .keyConditionExpression("#PARTITION = :partition")
            .expressionAttributeNames(mapOf("#PARTITION" to partitionKeyProperty.name))
            .expressionAttributeValues(mapOf(":partition" to propertyToAttribute(partition)))
    }.items().mapNotNull { buildInstance(it, klass) }

    override fun findAllBetween(partition: P, start: S, end: S): List<T> = db.query {
        it.tableName(tableName())
            .select(Select.ALL_ATTRIBUTES)
            .keyConditionExpression("#PARTITION = :partition and #SORT between :start and :end")
            .expressionAttributeNames(
                mapOf(
                    "#PARTITION" to partitionKeyProperty.name,
                    "#SORT" to sortKeyProperty.name
                )
            )
            .expressionAttributeValues(
                mapOf(
                    ":partition" to propertyToAttribute(partition),
                    ":start" to propertyToAttribute(start),
                    ":end" to propertyToAttribute(end)
                )
            )
    }.items().mapNotNull { buildInstance(it, klass) }

    override fun exists(partition: P, sort: S): Boolean = db.getItem {
        it.tableName(tableName())
            .key(mapOf(
                partitionKeyProperty.name to propertyToAttribute(partition),
                sortKeyProperty.name to propertyToAttribute(sort)
            ))
        }.item().isNotEmpty()

    override fun deleteOne(partition: P) {
        throw IllegalStateException("Cannot use Partition deletion when Sort key is defined!")
    }

    override fun deleteOne(partition: P, sort: S) {
        db.deleteItem {
            it.tableName(tableName())
                .key(
                    mapOf(
                        partitionKeyProperty.name to propertyToAttribute(partition),
                        sortKeyProperty.name to propertyToAttribute(sort)
                    )
                )
        }
    }
}
