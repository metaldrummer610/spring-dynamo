package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Select
import java.net.URI
import java.time.LocalDate
import java.util.*

@SpringBootTest(classes = [DynamoDBConfig::class])
@Import(TestConfiguration::class)
class SpringConfigurationTest {
    @Autowired
    lateinit var testRepository: CompositeKeyRepository<TestEntity, UUID, LocalDate>

    @Autowired
    lateinit var fooRepository: SimpleKeyRepository<Foo, UUID>

    @Autowired
    lateinit var barRepository: BarRepository

    @Autowired
    lateinit var mvaRateRepository: CompositeKeyRepository<MvaRate, String, LocalDate>

    @Test
    fun listEntities() {
        assertThat(testRepository.findAll()).isEmpty()
        testRepository.save(testEntity)
        assertThat(testRepository.findAll()).containsOnly(testEntity)
        assertThat(testRepository.findById(testEntity.personId, testEntity.updatedOn))
            .isEqualToComparingFieldByField(testEntity)

        val foo = Foo()
        assertThat(fooRepository.findAll()).isEmpty()
        fooRepository.save(foo)
        assertThat(fooRepository.findAll()).containsOnly(foo)
        assertThat(fooRepository.findById(foo.id)).isEqualToComparingFieldByField(foo)
    }

    @Test
    fun `should return an empty optional when the item does not exist`() {
        val entity = fooRepository.findById(UUID.randomUUID())
        assertThat(entity).isNull()
    }

    @Test
    fun `should throw exception when only giving a hash key and a range key is required`() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            testRepository.findById(UUID.randomUUID())
        }
    }

    @Test
    fun `should find all entities of the same id`() {
        val id = UUID.randomUUID()
        for (i in 1..5) {
            val entity = TestEntity(id, LocalDate.now().minusDays(i.toLong()), emptyList(), emptyList(), 0)
            testRepository.save(entity)
        }

        assertThat(testRepository.findAllById(id)).hasSize(5)
    }

    @Test
    fun `custom repository should work nicely`() {
        val entity = Bar()
        barRepository.save(entity)

        assertThat(barRepository.findById(entity.id, entity.updatedOn)).isEqualToComparingFieldByField(entity)
        assertThat(barRepository.findByOther(entity.id, entity.other)).containsOnly(entity)
    }

    @Test
    fun `partition key should allow querying between two values`() {
        for (i in 1..5) {
            val entity = MvaRate("MOODYS", LocalDate.now().minusDays(i.toLong()), i.toDouble())
            mvaRateRepository.save(entity)
        }

        val rates = mvaRateRepository.findAllBetween("MOODYS", LocalDate.now().minusDays(5), LocalDate.now())
        assertThat(rates).hasSize(5)
    }
}

class BarRepository(dynamoClient: DynamoDbClient) : DefaultCompositeKeyRepository<Bar, UUID, LocalDate>(dynamoClient, Bar::class) {
    fun findByOther(id: UUID, i: Int): List<Bar> {
        val keys = mapOf(
            ":id" to AttributeValue.builder().s(id.toString()).build(),
            ":other" to AttributeValue.builder().n(i.toString()).build()
        )

        return db.query {
            it.tableName(tableName())
                .indexName("other")
                .keyConditionExpression("id = :id and #O = :other")
                .expressionAttributeNames(mapOf(
                    "#O" to "other"
                ))
                .expressionAttributeValues(keys)
                .select(Select.ALL_PROJECTED_ATTRIBUTES)
        }.items().mapNotNull {
            when {
                it.isEmpty() -> null
                else -> buildInstance(it, Bar::class)
            }
        }
    }
}

@Configuration
class TestConfiguration {
    @Bean
    @Primary
    fun dynamoClient(): DynamoDbClient = DynamoDbClient.builder()
        .endpointOverride(URI("http://localhost:8000"))
        .credentialsProvider {
            StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")).resolveCredentials()
        }
        .region(Region.US_EAST_1)
        .build()

    @Bean
    fun barRepository(dynamoClient: DynamoDbClient) = BarRepository(dynamoClient)
}
