package com.group1001.daap.dynamo

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Select
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
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
     * Finds a specific entity with a [partition] key and projects the attributes
     * into the given [projectionClass]
     */
    fun <K : Any> asProjection(partition: P, projectionClass: KClass<K>): K?

    /**
     * Saves a single entity into the repository
     */
    fun save(t: T)

    /**
     * Gets the list of entities in this repository
     */
    fun count(): Int

    /**
     * Deletes a single record identified by [partition] key
     */
    fun deleteOne(partition: P)

    /**
     * USE AT YOUR OWN RISK - THIS IS EXPENSIVE AND ONLY MEANT FOR TESTS!
     */
    fun deleteAll()
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
    fun findLatest(partition: P, sort: S): T?

    /**
     * Finds all the entities for this [partition]
     */
    fun findAll(partition: P): List<T>

    /**
     * Finds a specific entity with [partition] and [sort] keys and projects the attributes
     * into the given [projectionClass]
     */
    fun <K : Any> asProjection(partition: P, sort: S, projectionClass: KClass<K>): K?

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
@Suppress("UNCHECKED_CAST", "ReturnCount", "ComplexMethod", "TooManyFunctions")
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

    override fun <K : Any> asProjection(partition: P, projectionClass: KClass<K>): K? {
        val keys = mapOf(partitionKeyProperty.name to propertyToAttribute(partition))

        return findByProjection(keys, projectionClass)
    }

    override fun save(t: T) {
        db.putItem { it.tableName(tableName()).item(buildProperties(t)) }
    }

    override fun count(): Int = db.scan { it.tableName(tableName()).select(Select.COUNT) }.count() ?: 0

    override fun deleteOne(partition: P) {
        db.deleteItem {
            it.tableName(tableName()).key(mapOf(partitionKeyProperty.name to propertyToAttribute(partition)))
        }
    }

    override fun deleteAll() {
        db.scan { it.tableName(tableName()) }.items().forEach {
            val partition = attributeToProperty(it[partitionKeyProperty.name]!!, partitionKeyProperty.returnType.javaType) as P
            deleteOne(partition)
        }
    }

    protected fun tableName() = klass.simpleName

    protected fun findByKeys(keys: Map<String, AttributeValue>): T? =
        db.getItem { it.tableName(tableName()).key(keys) }.item()?.let { buildInstance(it, klass) }

    /**
     * Takes an object [r] and converts it into a [Map] of Dynamo values
     */
    private fun <R : Any> buildProperties(r: R): Map<String, AttributeValue> {
        return r::class.memberProperties.associate {
            it as KProperty1<R, *>
            val value = it.get(r)
            it.name to propertyToAttribute(value)
        }.plus("_type" to AttributeValue.builder().s(r::class.qualifiedName).build())
    }

    /**
     * Builds an instance of a [klass] based on the contents of the [item] map
     */
    protected fun <R : Any> buildInstance(item: Map<String, AttributeValue>, kClass: KClass<R>): R? {
        // If we have an empty map, assume it's null
        if (item.isEmpty()) {
            return null
        }

        val klass: KClass<R> = if (item.containsKey("_type")) {
            Class.forName(item.getValue("_type").s()).kotlin as KClass<R>
        } else {
            kClass
        }

        // Deserialize all the fields and store them for future processing
        val fields = mutableMapOf<String, Any?>() // Field name -> value
        item.filterNot { it.key == "_type" }.forEach { (key, value) ->
            val prop = klass.memberProperties.first {
                it.findAnnotation<Alias>()?.name == key || it.name == key
            }
            val name: String = if (prop.hasAnnotation<Alias>()) {
                prop.name
            } else {
                key
            }

            fields[name] = attributeToProperty(value, prop.returnType.javaType)
        }

        // Figure out what kind of constructor we have
        // If we have a no-arg constructor, go ahead and create an instance of this type using it
        // If we have a constructor with arguments and no no-arg, use it
        // If we don't have any constructors for whatever reason, throw an exception because you probably screwed something up
        val noArgConstructor = klass.constructors.firstOrNull { it.parameters.isEmpty() }
        val withArgConstructor = klass.constructors.firstOrNull { it.parameters.isNotEmpty() }
        val instance: R = when {
            noArgConstructor != null -> {
                noArgConstructor.call()
            }
            withArgConstructor == null -> throw IllegalStateException("Cannot create an instance of $klass when it does not have either a no-arg constructor, or a single arg'd constructor!")
            else -> {
                // Iterate over the constructors arguments, finding the field from above and grabbing it's value
                val args = linkedMapOf<KParameter, Any?>()
                withArgConstructor.parameters.forEach {
                    args[it] = fields[it.name]
                }

                // Then call the constructor with the arguments
                withArgConstructor.callBy(args)
            }
        }

        // Finally we set all the fields on the instance of the class
        fields.forEach { (name, value) ->
            val prop = klass.memberProperties.first { it.name == name }
            prop.isAccessible = true
            prop.javaField!!.set(instance, value)
        }
        return instance
    }

    /**
     * Serializes a property into a Dynamo property
     */
    protected fun propertyToAttribute(value: Any?): AttributeValue {
        val builder = AttributeValue.builder()
        return when (value) {
            // Thanks Amazon... for not supporting a damn empty string as a value since 2012
            is String -> {
                when (value) {
                    null -> builder.m(mapOf("null" to AttributeValue.builder().nul(true).build())).build()
                    "" -> builder.m(mapOf("empty" to AttributeValue.builder().nul(true).build())).build()
                    else -> builder.s(value).build()
                }
            }
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
    protected fun attributeToProperty(attr: AttributeValue, prop: Type): Any? {
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

        // Thanks Amazon... for not supporting a damn empty string as a value since 2012
        if (String::class.java == prop) {
            val m = attr.m()
            return when {
                m.containsKey("null") -> null
                m.containsKey("empty") -> ""
                else -> attr.s()
            }
        }

        // Otherwise check the registry for a converter and process it
        val converter = TypeRegistry.findConverter(prop)
        if (converter != null) {
            return converter.deserialize(attr)
        }

        return buildInstance(attr.m(), prop.kotlin)
    }

    /**
     * Helper function that finds a single document by it's [keys] and projects it into the [projectionClass]
     */
    protected fun <K : Any> findByProjection(keys: Map<String, AttributeValue>, projectionClass: KClass<K>): K? {
        val projectionMembers = projectionClass.memberProperties.map {
            val name = if (it.hasAnnotation<Alias>()) {
                it.findAnnotation<Alias>()!!.name
            } else {
                it.name
            }

            Pair(name, it.returnType)
        }
        val klassMembers = klass.memberProperties.map { Pair(it.name, it.returnType) }

        val projectionExpression: MutableList<String> = mutableListOf()
        val attributeNames: MutableMap<String, String> = mutableMapOf()
        projectionMembers.intersect(klassMembers).forEach {
            projectionExpression.add("#${it.first.toUpperCase()}")
            attributeNames["#${it.first.toUpperCase()}"] = it.first
        }

        if (projectionExpression.isEmpty()) {
            throw IllegalStateException("Cannot project $klass to $projectionClass because they do not share any properties!")
        }

        return db.getItem {
            it.tableName(tableName())
                .key(keys)
                .projectionExpression(projectionExpression.joinToString())
                .expressionAttributeNames(attributeNames)
        }.item()?.let { buildInstance(it, projectionClass) }
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

    override fun findLatest(partition: P, sort: S): T? = db.query {
        it.tableName(tableName())
            .select(Select.ALL_ATTRIBUTES)
            .limit(1)
            .scanIndexForward(false)
            .keyConditionExpression("#PARTITION = :partition and #SORT <= :sort")
            .expressionAttributeNames(
                mapOf(
                    "#PARTITION" to partitionKeyProperty.name,
                    "#SORT" to sortKeyProperty.name
                )
            )
            .expressionAttributeValues(
                mapOf(
                    ":partition" to propertyToAttribute(partition),
                    ":sort" to propertyToAttribute(sort)
                )
            )
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

    override fun <K : Any> asProjection(partition: P, sort: S, projectionClass: KClass<K>): K? {
        val keys = mapOf(
            partitionKeyProperty.name to propertyToAttribute(partition),
            sortKeyProperty.name to propertyToAttribute(sort)
        )

        return findByProjection(keys, projectionClass)
    }

    override fun <K : Any> asProjection(partition: P, projectionClass: KClass<K>): K? {
        throw IllegalStateException("Cannot project using Partition when Sort key is defined!")
    }

    override fun exists(partition: P, sort: S): Boolean = db.getItem {
        it.tableName(tableName())
            .key(
                mapOf(
                    partitionKeyProperty.name to propertyToAttribute(partition),
                    sortKeyProperty.name to propertyToAttribute(sort)
                )
            )
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

    override fun deleteAll() {
        db.scan { it.tableName(tableName()) }.items().forEach {
            val partition = attributeToProperty(it[partitionKeyProperty.name]!!, partitionKeyProperty.returnType.javaType) as P
            val sort = attributeToProperty(it[sortKeyProperty.name]!!, sortKeyProperty.returnType.javaType) as S
            deleteOne(partition, sort)
        }
    }
}
