package com.group1001.daap.dynamo

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.lang.reflect.Type
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*
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
    inline fun <reified T : Any> tableForEntity(client: DynamoDbClient) = tableForEntity(client, T::class)

    fun <T : Any> tableForEntity(client: DynamoDbClient, kClass: KClass<T>) {
        val throughput: Throughput = requireNotNull(kClass.findAnnotation()) { "Dynamo Classes require a Throughput Annotation" }
        val properties = kClass.memberProperties
        val hashKeyProperty = requireNotNull(properties.first { it.hasAnnotation<PartitionKey>() }) { "Dynamo Classes require a HashKey annotation" }
        val rangeKeyProperty = properties.firstOrNull { it.hasAnnotation<SortKey>() }
        val secondaryIndexes = properties.filter { it.hasAnnotation<LocalSecondaryIndex>() }
        val globalIndexes = properties.filter { it.hasAnnotation<GlobalSecondaryIndex>() }

        val createRequest = CreateTableRequest.builder()
            .provisionedThroughput { it.readCapacityUnits(throughput.read).writeCapacityUnits(throughput.write) }
            .tableName(kClass.simpleName)
            .keySchema(makePrimaryKey(hashKeyProperty.name, rangeKeyProperty?.name))
            .attributeDefinitions(makeAttributes(hashKeyProperty, rangeKeyProperty, secondaryIndexes, globalIndexes))

        val localSecondaryIndexes = makeSecondaryIndexes(hashKeyProperty, secondaryIndexes)
        if (localSecondaryIndexes.isNotEmpty()) {
            createRequest.localSecondaryIndexes(localSecondaryIndexes)
        }

        val globalSecondaryIndexes = makeGlobalSecondaryIndexes(globalIndexes)
        if (globalSecondaryIndexes.isNotEmpty()) {
            createRequest.globalSecondaryIndexes(globalSecondaryIndexes)
        }

        client.createTable(createRequest.build())
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

    private fun <T> makeSecondaryIndexes(
        hashKeyProperty: KProperty1<T, *>,
        secondaryIndexes: List<KProperty1<T, *>>
    ): List<AwsLocalSecondaryIndex> = secondaryIndexes.map {
        AwsLocalSecondaryIndex.builder()
            .indexName(it.name)
            .keySchema(
                KeySchemaElement.builder().attributeName(hashKeyProperty.name).keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName(it.name).keyType(KeyType.RANGE).build()
            )
            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
            .build()
    }

    private fun <T> makeGlobalSecondaryIndexes(
        indexes: List<KProperty1<T, *>>
    ): List<AwsGlobalSecondaryIndex> = indexes.map {
        val gsi = it.findAnnotation<GlobalSecondaryIndex>()!!
        val keys = mutableListOf(
            KeySchemaElement.builder().attributeName(it.name).keyType(KeyType.HASH).build()
        )

        if (gsi.sortKey.isNotBlank()) {
            keys.add(KeySchemaElement.builder().attributeName(gsi.sortKey).keyType(KeyType.RANGE).build())
        }

        AwsGlobalSecondaryIndex.builder()
            .indexName(it.name)
            .keySchema(keys)
            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
            .provisionedThroughput { pt -> pt.readCapacityUnits(gsi.read).writeCapacityUnits(gsi.write) }
            .build()
    }

    private fun <T> makeAttributes(
        hashKeyProperty: KProperty1<T, *>,
        rangeKeyProperty: KProperty1<T, *>?,
        secondaryIndexes: List<KProperty1<T, *>>,
        globalSecondaryIndexes: List<KProperty1<T, *>>
    ): List<AttributeDefinition> {
        fun makeAttribute(name: String, type: Type) =
            AttributeDefinition.builder().attributeName(name).attributeType(determineAttributeType(type)).build()

        val attributes = mutableListOf(
            makeAttribute(hashKeyProperty.name, hashKeyProperty.returnType.javaType)
        )

        if (rangeKeyProperty != null) {
            attributes.add(makeAttribute(rangeKeyProperty.name, rangeKeyProperty.returnType.javaType))
        }

        secondaryIndexes.forEach {
            attributes.add(makeAttribute(it.name, it.returnType.javaType))
        }

        globalSecondaryIndexes.forEach {
            attributes.add(makeAttribute(it.name, it.returnType.javaType))
        }

        return attributes
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
