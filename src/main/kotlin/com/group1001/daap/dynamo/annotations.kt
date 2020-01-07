package com.group1001.daap.dynamo

/**
 * Required annotation that denotes the class is a Dynamo Table
 * @param read The read units for this table
 * @param write The write units for this table
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Throughput(val read: Long, val write: Long)

/**
 * Denotes the Primary Partition key for this table.
 * When defined alone (no [SortKey] is present), then this defines a
 * Primary Key that contains _only_ this field
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PartitionKey

/**
 * Denotes the Sort key for this table. This along with the [PartitionKey]
 * makes up a Composite Primary Key for the table.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SortKey

/**
 * Denotes a field that should be a Secondary Sort column in a
 * "Local Secondary Index" for this table, along with the [PartitionKey]
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class LocalSecondaryIndex
