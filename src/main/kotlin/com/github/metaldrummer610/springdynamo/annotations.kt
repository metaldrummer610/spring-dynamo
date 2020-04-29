package com.github.metaldrummer610.springdynamo

/**
 * Required annotation that denotes the class is a Dynamo Table
 * @param read The read units for this table
 * @param write The write units for this table
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Deprecated(
    "Annotation is replaced/renamed by @DynamoTable",
    ReplaceWith("DynamoTable(BillingMode(BillingType.PROVISIONED, read, write))"),
    DeprecationLevel.ERROR
)
annotation class Throughput(val read: Long, val write: Long)

/**
 * Required annotation that denotes the class is a Dynamo Table
 * @param billingMode Details how this table should be configured in terms of billing
 * @param serverSideEncryption Details if Dynamo's SSE should be enabled or not
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class DynamoTable(
    val billingMode: BillingMode = BillingMode(BillingType.ON_DEMAND),
    val serverSideEncryption: SSE = SSE()
)

/**
 * The Server Side Encryption settings for this table. By default,
 * SSE is turned *off* for a table. However, through these settings,
 * it can be turned on and configured either per table, or use global
 * settings for all tables in an application.
 *
 * @param type The type of SSE to use
 * @param kmsMasterKey Optionally specify a different Master Key for Encryption.
 * The value would be a key ID, Amazon Resource Name (ARN), alias name, or alias ARN.
 */
annotation class SSE(
    val type: SSEType = SSEType.NONE,
    val kmsMasterKey: String = ""
)

/**
 * What type of Server Side Encryption should this table use, if any.
 * Defaults to [NONE].
 *
 * Alternatively, [KMS] or [AES] can be specified if the specific table
 * requires a different configuration from the others.
 */
enum class SSEType {
    NONE,
    AES,
    KMS
}

/**
 * Configures how this table should be billed. If the [type] is set to
 * [BillingType.ON_DEMAND], [read] and [write] are ignored. However,
 * if [BillingType.PROVISIONED] is selected, [read] and [write] are
 * *required* to be positive non-zero values.
 */
annotation class BillingMode(
    val type: BillingType,
    val read: Long = 0,
    val write: Long = 0
)

/**
 * The billing type this table should use
 */
enum class BillingType {
    ON_DEMAND,
    PROVISIONED
}

/**
 * Marks the Primary Partition key for this table.
 * When defined alone (no [SortKey] is present), then this defines a
 * Primary Key that contains _only_ this field
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PartitionKey

/**
 * Marks the Sort key for this table. This along with the [PartitionKey]
 * makes up a Composite Primary Key for the table.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SortKey

/**
 * Marks a field that should be a Secondary Sort column in a
 * "Local Secondary Index" for this table, along with the [PartitionKey]
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class LocalSecondaryIndex

/**
 * Marks a field that is the Partition Key for a Global Secondary Index for this table.
 * If the table has been marked as [BillingType.PROVISIONED], then [read] and [write]
 * are *required* fields. If a Sort Key is desired, [sortKey] must be set to the name
 * of another field in this class that will act as the Sort Key.
 *
 * @param read The optional read capacity for this index
 * @param write The optional write capacity for this index
 * @param sortKey The optional sort key for this index
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GlobalSecondaryIndex(
    val read: Long = 0,
    val write: Long = 0,
    val sortKey: String = ""
)

/**
 * Used to change the name of a field in a projection from the original name
 * @param name The field name to change the source field to
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Alias(val name: String)

/**
 * Marks a field as this table's Time To Live specification.
 * The field *MUST* be a timestamp field of some sort.
 * For example, a Long/DateTime/OffsetDateTime.
 *
 * This field *CANNOT* be a String containing a timestamp in an ISO format
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class TTL
