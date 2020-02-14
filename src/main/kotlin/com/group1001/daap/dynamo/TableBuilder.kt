package com.group1001.daap.dynamo

import org.awaitility.Awaitility.await
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.lang.reflect.Type
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex as AwsGlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex as AwsLocalSecondaryIndex

/**
 * TableBuilder provides a method for creating a DynamoDB Table from a given Entity class
 */
@UseExperimental(ExperimentalStdlibApi::class)
object TableBuilder {
    inline fun <reified T : Any> tableForEntity(client: DynamoDbClient) = tableForEntity(client, DynamoProperties(), T::class)

    fun <T : Any> tableForEntity(client: DynamoDbClient, dynamoProperties: DynamoProperties, kClass: KClass<T>) {
        val table: DynamoTable = requireNotNull(kClass.findAnnotation()) { "Dynamo Classes require a DynamoTable Annotation" }
        val properties = kClass.memberProperties
        val hashKeyProperty = requireNotNull(properties.first { it.hasAnnotation<PartitionKey>() }) { "Dynamo Classes require a HashKey annotation" }
        val rangeKeyProperty = properties.firstOrNull { it.hasAnnotation<SortKey>() }
        val secondaryIndexes = properties.filter { it.hasAnnotation<LocalSecondaryIndex>() }
        val globalIndexes = properties.filter { it.hasAnnotation<GlobalSecondaryIndex>() }
        val globalIndexSortKeys = globalIndexes.map { it.findAnnotation<GlobalSecondaryIndex>()!!.sortKey }.filter { it != "" }.map { p -> properties.first { it.name == p } }

        val ttlProperty: KProperty1<T, *>? = if (properties.any { it.hasAnnotation<TTL>() }) {
            require(properties.filter { it.hasAnnotation<TTL>() }.size == 1) { "Cannot specify multiple TTL fields" }
            properties.first { it.hasAnnotation<TTL>() }
        } else {
            null
        }

        val createRequest = CreateTableRequest.builder()
            .tableName(kClass.simpleName)
            .billingMode(determineBillingMode(table.billingMode))
            .keySchema(makePrimaryKey(hashKeyProperty.name, rangeKeyProperty?.name))
            .attributeDefinitions(
                makeAttributes(listOfNotNull(hashKeyProperty, rangeKeyProperty, ttlProperty) + secondaryIndexes + globalIndexes + globalIndexSortKeys)
            )

        if (table.billingMode.type == BillingType.PROVISIONED) {
            require(table.billingMode.read > 0) { "The read capacity for a provisioned table cannot be less than 1. It was ${table.billingMode.read}" }
            require(table.billingMode.write > 0) { "The write capacity for a provisioned table cannot be less than 1. It was ${table.billingMode.write}" }

            createRequest.provisionedThroughput {
                it.readCapacityUnits(table.billingMode.read).writeCapacityUnits(table.billingMode.write)
            }
        }

        configureEncryption(createRequest, dynamoProperties, table)

        val localSecondaryIndexes = makeSecondaryIndexes(hashKeyProperty, secondaryIndexes)
        if (localSecondaryIndexes.isNotEmpty()) {
            createRequest.localSecondaryIndexes(localSecondaryIndexes)
        }

        val globalSecondaryIndexes = makeGlobalSecondaryIndexes(globalIndexes, table.billingMode.type)
        if (globalSecondaryIndexes.isNotEmpty()) {
            createRequest.globalSecondaryIndexes(globalSecondaryIndexes)
        }

        client.createTable(createRequest.build())

        await().pollInSameThread().atMost(10, TimeUnit.SECONDS).until {
            client.describeTable {
                it.tableName(kClass.simpleName)
            }
                .table()
                .tableStatus() == TableStatus.ACTIVE
        }

        if (ttlProperty != null) {
            client.updateTimeToLive {
                it.tableName(kClass.simpleName)
                    .timeToLiveSpecification { t ->
                        t.attributeName(ttlProperty.name).enabled(true)
                    }
            }
        }
    }

    /**
     * Configures the Server Side Encryption for this table.
     * The rules are as follows:
     * - Table specific annotations override global ones
     * - Global ones are applied iff encryption is enabled
     */
    private fun configureEncryption(createRequest: CreateTableRequest.Builder, dynamoProperties: DynamoProperties, table: DynamoTable) {
        val overrideGlobal = table.serverSideEncryption.type != SSEType.NONE

        val mode = (if (overrideGlobal) {
            determineSSEMode(table.serverSideEncryption.type)
        } else {
            determineSSEMode(dynamoProperties.sse.type)
        }) ?: return

        val key = if (overrideGlobal && table.serverSideEncryption.kmsMasterKey != "") {
            table.serverSideEncryption.kmsMasterKey
        } else if (dynamoProperties.sse.kmsMasterKey != "") {
            dynamoProperties.sse.kmsMasterKey
        } else {
            null
        }

        createRequest.sseSpecification {
            it.enabled(true).sseType(mode).kmsMasterKeyId(key)
        }
    }

    private fun determineBillingMode(billingMode: BillingMode): software.amazon.awssdk.services.dynamodb.model.BillingMode = when (billingMode.type) {
        BillingType.ON_DEMAND -> software.amazon.awssdk.services.dynamodb.model.BillingMode.PAY_PER_REQUEST
        BillingType.PROVISIONED -> software.amazon.awssdk.services.dynamodb.model.BillingMode.PROVISIONED
    }

    private fun determineSSEMode(type: SSEType): software.amazon.awssdk.services.dynamodb.model.SSEType? = when (type) {
        SSEType.NONE -> null
        SSEType.AES -> software.amazon.awssdk.services.dynamodb.model.SSEType.AES256
        SSEType.KMS -> software.amazon.awssdk.services.dynamodb.model.SSEType.KMS
    }

    private fun makePrimaryKey(hashName: String, rangeName: String?): List<KeySchemaElement> {
        val keys = mutableListOf(
            KeySchemaElement.builder().attributeName(hashName).keyType(KeyType.HASH).build()
        )

        if (rangeName != null) {
            keys.add(KeySchemaElement.builder().attributeName(rangeName).keyType(KeyType.RANGE).build())
        }

        return keys
    }

    private fun <T> makeSecondaryIndexes(hashKeyProperty: KProperty1<T, *>, secondaryIndexes: List<KProperty1<T, *>>): List<AwsLocalSecondaryIndex> =
        secondaryIndexes.map {
            AwsLocalSecondaryIndex.builder()
                .indexName(it.name)
                .keySchema(
                    KeySchemaElement.builder().attributeName(hashKeyProperty.name).keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName(it.name).keyType(KeyType.RANGE).build()
                )
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build()
        }

    private fun <T> makeGlobalSecondaryIndexes(indexes: List<KProperty1<T, *>>, billingType: BillingType): List<AwsGlobalSecondaryIndex> =
        indexes.map {
            val gsi = it.findAnnotation<GlobalSecondaryIndex>()!!
            val keys = mutableListOf(
                KeySchemaElement.builder().attributeName(it.name).keyType(KeyType.HASH).build()
            )

            if (gsi.sortKey.isNotBlank()) {
                keys.add(KeySchemaElement.builder().attributeName(gsi.sortKey).keyType(KeyType.RANGE).build())
            }

            val builder = AwsGlobalSecondaryIndex.builder()
                .indexName(it.name)
                .keySchema(keys)
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())

            if (billingType == BillingType.PROVISIONED) {
                require(gsi.read > 0) { "The read capacity for a Global Secondary Index cannot be less than 1. It was ${gsi.read}" }
                require(gsi.write > 0) { "The write capacity for a Global Secondary Index cannot be less than 1. It was ${gsi.write}" }

                builder.provisionedThroughput { pt -> pt.readCapacityUnits(gsi.read).writeCapacityUnits(gsi.write) }
            }

            builder.build()
        }

    private fun <T> makeAttributes(properties: List<KProperty1<T, *>>): List<AttributeDefinition> =
        properties.map {
            AttributeDefinition.builder()
                .attributeName(it.name)
                .attributeType(determineAttributeType(it.returnType.javaType))
                .build()
        }

    @Suppress("ComplexMethod")
    private fun determineAttributeType(type: Type): ScalarAttributeType = when (type) {
        String::class.java -> ScalarAttributeType.S
        LocalDate::class.java -> ScalarAttributeType.S
        LocalDateTime::class.java -> ScalarAttributeType.S
        OffsetDateTime::class.java -> ScalarAttributeType.S
        UUID::class.java -> ScalarAttributeType.S
        Int::class.java -> ScalarAttributeType.N
        Long::class.java -> ScalarAttributeType.N
        Double::class.java -> ScalarAttributeType.N
        Float::class.java -> ScalarAttributeType.N
        BigDecimal::class.java -> ScalarAttributeType.N
        Enum::class.java -> ScalarAttributeType.S
        else -> throw IllegalArgumentException("$type is not supported!")
    }
}
