package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.OffsetDateTime
import java.util.*

@SpringBootTest(classes = [DynamoDBConfig::class])
@Import(TestConfiguration::class)
class InheritanceTest {
    @Autowired
    lateinit var baseRepository: CompositeKeyRepository<Base, UUID, OffsetDateTime>

    @Autowired
    lateinit var animalRepository: SimpleKeyRepository<Animal, String>

    @Test
    fun `should be able to save classes from their base interface`() {
        val c1 = Child("blah", UUID.randomUUID(), OffsetDateTime.now())
        val c2 = OtherChild("meh", UUID.randomUUID(), OffsetDateTime.now())

        baseRepository.save(c1)
        baseRepository.save(c2)

        assertThat(baseRepository.findById(c1.id, c1.asOf)).isEqualToComparingFieldByField(c1)
        assertThat(baseRepository.findById(c2.id, c2.asOf)).isEqualToComparingFieldByField(c2)
    }

    @Test
    fun `should be able to save classes with a base class`() {
        val cat = Cat(4, "bob")
        val dog = Dog(true, "jim")

        animalRepository.save(cat)
        animalRepository.save(dog)

        assertThat(animalRepository.findById(cat.name)).isEqualToComparingFieldByField(cat)
        assertThat(animalRepository.findById(dog.name)).isEqualToComparingFieldByField(dog)
    }
}

@Throughput(1, 1)
interface Base {
    @PartitionKey val id: UUID
    @SortKey val asOf: OffsetDateTime
}

data class Child(
    val foo: String,
    override val id: UUID,
    override val asOf: OffsetDateTime
): Base

data class OtherChild(
    val bar: String,
    override val id: UUID,
    override val asOf: OffsetDateTime
): Base

@Throughput(1, 1)
open class Animal(
    @PartitionKey val name: String
)

class Cat(val legs: Int, name: String): Animal(name)
class Dog(val bark: Boolean, name: String): Animal(name)
