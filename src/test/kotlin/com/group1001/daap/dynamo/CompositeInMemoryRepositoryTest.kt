package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.*

class CompositeInMemoryRepositoryTest {
    lateinit var repo: CompositeInMemoryRepository<TestEntity, UUID, LocalDate>

    @BeforeEach
    fun setUp() {
        repo = CompositeInMemoryRepository(TestEntity::class)
    }

    @Test
    fun `should be able to add and remove`() {
        val test = testEntity()
        repo.save(test)
        assertThat(repo.findById(test.personId, test.updatedOn)).isEqualToComparingFieldByField(test)
        assertThat(repo.findAll()).containsOnly(test)
        assertThat(repo.findAll(test.personId)).containsOnly(test)

        repo.deleteOne(test.personId, test.updatedOn)
        assertThat(repo.findAll()).isEmpty()
        assertThat(repo.findAll(test.personId)).isEmpty()
    }

    @Test
    fun `should support multiple entities of the same key`() {
        val test = testEntity()
        val another = testEntity(id = test.personId, localDate = test.updatedOn.plusDays(1))
        val third = testEntity()

        repo.save(test)
        repo.save(another)
        repo.save(third)

        assertThat(repo.findAll()).containsOnly(test, another, third)
        assertThat(repo.findAll(test.personId)).containsOnly(test, another)
        assertThat(repo.findAll(another.personId)).containsOnly(test, another)
        assertThat(repo.findAll(third.personId)).containsOnly(third)

        assertThat(repo.findLatest(test.personId)).isEqualToComparingFieldByField(another)

        repo.deleteOne(test.personId, test.updatedOn)
        assertThat(repo.findAll()).containsOnly(another, third)
        assertThat(repo.findAll(test.personId)).containsOnly(another)
    }

    @Test
    fun `should find in a range`() {
        val id = UUID.randomUUID()
        val start = LocalDate.now().minusDays(3)
        val end = LocalDate.now().minusDays(1)

        for (i in 1..5) {
            repo.save(testEntity(id, LocalDate.now().minusDays(i.toLong())))
        }

        assertThat(repo.findAllBetween(id, start, end)).hasSize(3)
            .extracting("updatedOn", LocalDate::class.java)
            .containsOnly(LocalDate.now().minusDays(3), LocalDate.now().minusDays(2), LocalDate.now().minusDays(1))
    }

    @Test
    fun `should project`() {
        val test = testEntity()
        repo.save(test)

        assertThat(repo.asProjection(test.personId, test.updatedOn, TestEntity.PetAddressProjection::class)).isEqualToComparingFieldByField(TestEntity.PetAddressProjection(test.petNames, test.addresses))
    }
}