package com.github.metaldrummer610.springdynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDate
import java.util.*

@SpringBootTest
class ComplexKeyRepositoryTest {
    @Autowired
    lateinit var repo: CompositeKeyRepository<TestEntity, UUID, LocalDate>

    @Test
    fun `should save an entity`() {
        val testEntity = testEntity()
        repo.save(testEntity)
        repo.save(testEntity.copy(personId = UUID.randomUUID(), nullable = "not null"))
    }

    @Test
    fun `should save and find an entity`() {
        val testEntity = testEntity()
        repo.save(testEntity)
        val foundEntity = repo.findById(testEntity.personId, testEntity.updatedOn)
        assertThat(foundEntity).isEqualToComparingFieldByField(testEntity)
    }

    @Test
    fun `should save a nullable field and find an entity`() {
        val entity = testEntity(nullable = "foo")

        repo.save(entity)
        val foundEntity = repo.findById(entity.personId, entity.updatedOn)
        assertThat(foundEntity).isEqualToComparingFieldByField(entity)
    }

    @Test
    fun `should save an entity with a map and find it`() {
        val entity = testEntity(
            mapping = mapOf(
                "foo" to TestEntity.TestAddress("123"),
                "bar" to TestEntity.TestAddress("234")
            )
        )

        repo.save(entity)
        val foundEntity = repo.findById(entity.personId, entity.updatedOn)
        assertThat(foundEntity).isEqualToComparingFieldByField(entity)
    }
}
