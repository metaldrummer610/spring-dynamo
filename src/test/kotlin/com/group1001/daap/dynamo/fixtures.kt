package com.group1001.daap.dynamo

import org.springframework.boot.autoconfigure.SpringBootApplication
import java.time.LocalDate
import java.util.*

@SpringBootApplication
class App

fun testEntity(
    id: UUID = UUID.randomUUID(),
    localDate: LocalDate = LocalDate.now(),
    nullable: String? = null,
    mapping: Map<String, TestEntity.TestAddress> = emptyMap()
) = TestEntity(
    id,
    localDate,
    listOf("foo", "bar"),
    listOf(TestEntity.TestAddress("123"), TestEntity.TestAddress("234")),
    12345,
    nullable,
    mapping
)

@DynamoTable
data class TestEntity(
    @PartitionKey val personId: UUID,
    @SortKey val updatedOn: LocalDate,
    val petNames: List<String>,
    val addresses: List<TestAddress>,
    @LocalSecondaryIndex val other: Int,
    val nullable: String? = null,
    val mapping: Map<String, TestAddress> = emptyMap()
) {
    data class TestAddress(val street: String)

    data class PetAddressProjection(val petNames: List<String> = emptyList(), val addresses: List<TestAddress> = emptyList())
    data class AliasProjection(@Alias("petNames") val names: List<String>, val missingField: String?)
}

@DynamoTable(BillingMode(BillingType.PROVISIONED, 10, 10))
data class Foo(@PartitionKey val id: UUID = UUID.randomUUID(), val name: String = "") {
    data class FooProjection(val name: String)
}

@DynamoTable
data class Bar(
    @PartitionKey val id: UUID = UUID.randomUUID(),
    @SortKey val updatedOn: LocalDate = LocalDate.now(),
    @LocalSecondaryIndex val other: Int = 0,
    val mapping: Map<String, String>? = null,
    val list: List<String>? = null,
    val set: Set<String>? = null
)

@DynamoTable
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

@DynamoTable
data class User(
    @PartitionKey val id: UUID,
    @GlobalSecondaryIndex(10, 10) val otherId: UUID,
    @GlobalSecondaryIndex(10, 10, "name") val thirdId: UUID,
    val name: String
)
