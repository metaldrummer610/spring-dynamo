package com.group1001.daap.dynamo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

@Suppress("UNCHECKED_CAST")
@UseExperimental(ExperimentalStdlibApi::class)
open class SimpleInMemoryRepository<T : Any, P> : SimpleKeyRepository<T, P> {
    private val storage = ConcurrentHashMap<P, T>()

    override fun findById(partition: P): T? = storage[partition]

    override fun findAllById(partition: P): List<T> = listOfNotNull(storage[partition])

    override fun findAll(): List<T> = storage.values.toList()

    override fun count(): Int = storage.size

    override fun deleteOne(partition: P) {
        storage.remove(partition)
    }

    override fun save(t: T) {
        val partitionKey = t.javaClass.kotlin.memberProperties.first { it.hasAnnotation<PartitionKey>() }
        storage[partitionKey.get(t) as P] = t
    }

    override fun <K : Any> asProjection(partition: P, projectionClass: KClass<K>): K? {
        val t = storage[partition] ?: return null
        return projectTo(projectionClass, t)
    }

    override fun deleteAll() {
        storage.clear()
    }
}

@Suppress("UNCHECKED_CAST")
@UseExperimental(ExperimentalStdlibApi::class)
class CompositeInMemoryRepository<T : Any, P, S : Comparable<S>> : CompositeKeyRepository<T, P, S> {
    private val storage: MutableMap<P, ConcurrentHashMap<S, T>> = ConcurrentHashMap()
    private val size = AtomicInteger()

    override fun findAllById(partition: P): List<T> = storage[partition]?.values?.toList() ?: emptyList()

    override fun findAll(): List<T> = storage.flatMap { it.value.values }

    override fun findById(partition: P, sort: S): T? = storage[partition]?.get(sort)

    override fun findLatest(partition: P, sort: S): T? = storage[partition]?.entries?.sortedBy { it.key }?.lastOrNull { it.key <= sort }?.value

    override fun findAll(partition: P): List<T> = storage[partition]?.values?.toList() ?: emptyList()

    override fun exists(partition: P, sort: S): Boolean = storage[partition]?.containsKey(sort) ?: false

    override fun findAllBetween(partition: P, start: S, end: S): List<T> {
        val map = storage[partition] ?: return emptyList()
        return map.filterKeys { it in start..end }.values.toList()
    }

    override fun deleteOne(partition: P, sort: S) {
        storage[partition]?.remove(sort)
        size.decrementAndGet()
    }

    override fun <K : Any> asProjection(partition: P, sort: S, projectionClass: KClass<K>): K? {
        val t = findById(partition, sort) ?: return null
        return projectTo(projectionClass, t)
    }

    override fun save(t: T) {
        val partitionKey = t.javaClass.kotlin.memberProperties.first { it.hasAnnotation<PartitionKey>() }
        val sortKey = t.javaClass.kotlin.memberProperties.first { it.hasAnnotation<SortKey>() }

        val map = storage[partitionKey.get(t) as P] ?: ConcurrentHashMap()
        map[sortKey.get(t) as S] = t
        storage[partitionKey.get(t) as P] = map
        size.incrementAndGet()
    }

    override fun count(): Int = size.get()

    override fun deleteAll() {
        storage.clear()
    }

    override fun findById(partition: P): T? = throw IllegalStateException("Cannot use Partition lookup when Sort key is defined!")

    override fun <K : Any> asProjection(partition: P, projectionClass: KClass<K>): K? = throw IllegalStateException("Cannot project using Partition when Sort key is defined!")

    override fun deleteOne(partition: P): Unit = throw IllegalStateException("Cannot use Partition deletion when Sort key is defined!")
}

private fun <K : Any, T : Any> projectTo(projectionClass: KClass<K>, t: T): K? {
    val projectionMembers = projectionClass.memberProperties
    val klassMembers = t.javaClass.kotlin.memberProperties

    val properties = mutableMapOf<String, Any?>()
    projectionMembers.map { it.name }.intersect(klassMembers.map { it.name }).forEach {
        val prop = klassMembers.first { p -> p.name == it }
        properties[it] = prop.get(t)
    }

    val ctor = projectionClass.primaryConstructor ?: return null
    val ctorArgs = ctor.parameters.associateWith { properties[it.name] }
    val k: K = ctor.callBy(ctorArgs)
    properties.forEach { (name, value) ->
        val prop = projectionMembers.first { it.name == name }
        prop.isAccessible = true
        prop.javaField!!.set(k, value)
    }

    return k
}
