package com.group1001.daap.dynamo

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import java.time.LocalDate
import java.util.*

val dbClient: DynamoDbClient = DynamoDbClient.builder()
    .endpointOverride(URI("http://localhost:8000"))
    .credentialsProvider { AwsBasicCredentials.create("local", "local") }
    .region(Region.US_EAST_1)
    .build()

val testEntity = TestEntity(
    UUID.randomUUID(),
    LocalDate.now(),
    listOf("foo", "bar"),
    listOf(TestAddress("123"), TestAddress("234")),
    12345
)

fun setupDB() {
    dbClient.listTables().tableNames().forEach {
        dbClient.deleteTable { builder -> builder.tableName(it) }
    }

    TableBuilder.tableForEntity<TestEntity>(dbClient)
}

data class TestAddress(val street: String = "")

@Throughput(10, 10)
data class TestEntity(
    @PartitionKey val personId: UUID,
    @SortKey val updatedOn: LocalDate,
    val petNames: List<String>,
    val addresses: List<TestAddress>,
    @LocalSecondaryIndex val other: Int,
    val nullable: String? = null,
    val mapping: Map<String, TestAddress> = emptyMap()
) {
    constructor() : this(UUID.randomUUID(), LocalDate.now(), emptyList(), emptyList(), 0)
}

@Throughput(10, 10)
data class Foo(@PartitionKey val id: UUID = UUID.randomUUID())

@Throughput(10, 10)
data class Bar(
    @PartitionKey val id: UUID = UUID.randomUUID(),
    @SortKey val updatedOn: LocalDate = LocalDate.now(),
    @LocalSecondaryIndex val other: Int = 0,
    val mapping: Map<String, String>? = null,
    val list: List<String>? = null
)

@Throughput(10, 10)
data class MvaRate(
    @PartitionKey val security: String = "",
    @SortKey val date: LocalDate = LocalDate.now(),
    val rate: Double = 0.0,
    val source: RateSource = RateSource.MOODYS
)

enum class RateSource {
    MOODYS,
    CUSTOM
}
