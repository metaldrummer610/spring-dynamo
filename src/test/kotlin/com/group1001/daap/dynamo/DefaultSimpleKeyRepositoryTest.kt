package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.*

@Disabled
class DefaultSimpleKeyRepositoryTest {
    private val repo = DefaultCompositeKeyRepository<TestEntity, UUID, LocalDate>(dbClient, TestEntity::class)

    @BeforeEach
    fun before() {
        setupDB()
    }

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
        val entity = testEntity(mapping = mapOf("foo" to TestEntity.TestAddress("123"), "bar" to TestEntity.TestAddress("234")))

        repo.save(entity)
        val foundEntity = repo.findById(entity.personId, entity.updatedOn)
        assertThat(foundEntity).isEqualToComparingFieldByField(entity)
    }
}
