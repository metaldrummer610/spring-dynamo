package com.group1001.daap.dynamo

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

@Suppress("UNCHECKED_CAST")
@UseExperimental(ExperimentalStdlibApi::class)
open class SimpleInMemoryRepository<T : Any, P> : SimpleKeyRepository<T, P> {
    private val storage = mutableMapOf<P, T>()

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
}

@Suppress("UNCHECKED_CAST")
@UseExperimental(ExperimentalStdlibApi::class)
class CompositeInMemoryRepository<T : Any, P, S : Comparable<S>>(private val klass: KClass<T>): CompositeKeyRepository<T, P, S> {
    private val storage: MutableMap<P, MutableList<T>> = mutableMapOf()
    private val sortKey: KProperty1<T, S> = klass.memberProperties.first { it.hasAnnotation<SortKey>() } as KProperty1<T, S>

    override fun findAllById(partition: P): List<T> = storage[partition] ?: emptyList()

    override fun findAll(): List<T> = storage.flatMap { it.value }

    override fun findById(partition: P, sort: S): T? = storage[partition]?.first { sortKey.get(it) == sort }

    override fun findLatest(partition: P): T? = storage[partition]?.maxBy { sortKey.get(it) }

    override fun findAll(partition: P): List<T> = storage[partition] ?: emptyList()

    override fun exists(partition: P, sort: S): Boolean {
        val list = storage[partition] ?: return false
        return list.firstOrNull { sortKey.get(it) == sort } == null
    }

    override fun findAllBetween(partition: P, start: S, end: S): List<T> {
        val list = storage[partition] ?: return emptyList()
        return list.filter { sortKey.get(it) in start..end }
    }

    override fun deleteOne(partition: P, sort: S) {
        val list = storage[partition] ?: return
        list.removeIf { sortKey.get(it) == sort }
    }

    override fun <K : Any> asProjection(partition: P, sort: S, projectionClass: KClass<K>): K? {
        val t = findById(partition, sort) ?: return null
        return projectTo(projectionClass, t)
    }

    override fun save(t: T) {
        val partitionKey = t.javaClass.kotlin.memberProperties.first { it.hasAnnotation<PartitionKey>() }

        val list = storage[partitionKey.get(t) as P] ?: mutableListOf()
        list.add(t)
        storage[partitionKey.get(t) as P] = list
    }

    override fun count(): Int = storage.flatMap { it.value }.size

    override fun findById(partition: P): T? = throw IllegalStateException("Cannot use Partition lookup when Sort key is defined!")

    override fun <K : Any> asProjection(partition: P, projectionClass: KClass<K>): K? = throw IllegalStateException("Cannot project using Partition when Sort key is defined!")

    override fun deleteOne(partition: P): Unit = throw IllegalStateException("Cannot use Partition deletion when Sort key is defined!")
}

private fun <K : Any, T: Any> projectTo(projectionClass: KClass<K>, t: T): K? {
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
