package com.github.metaldrummer610.springdynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.*

class CompositeInMemoryRepositoryTest {
    private lateinit var repo: CompositeInMemoryRepository<TestEntity, UUID, LocalDate>
    private lateinit var mvaRateRepository: CompositeInMemoryRepository<MvaRate, String, LocalDate>

    @BeforeEach
    fun setUp() {
        repo = CompositeInMemoryRepository()
        mvaRateRepository = CompositeInMemoryRepository()
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

        assertThat(repo.findLatest(test.personId, LocalDate.now())).isEqualToComparingFieldByField(test)

        repo.deleteOne(test.personId, test.updatedOn)
        assertThat(repo.findAll()).containsOnly(another, third)
        assertThat(repo.findAll(test.personId)).containsOnly(another)
    }

    @Test
    fun `should find latest`() {
        for (i in 1..5) {
            val entity = MvaRate("MOODYS", LocalDate.now().minusDays(i.toLong()), i.toDouble())
            mvaRateRepository.save(entity)
        }

        val rate = mvaRateRepository.findLatest("MOODYS", LocalDate.now())
        assertThat(rate).isNotNull
        assertThat(rate!!.date).isEqualTo(LocalDate.now().minusDays(1))
        assertThat(rate.rate).isEqualTo(1.toDouble())
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

        assertThat(repo.asProjection(test.personId, test.updatedOn, TestEntity.PetAddressProjection::class)).isEqualToComparingFieldByField(
            TestEntity.PetAddressProjection(
                test.petNames,
                test.addresses
            )
        )
    }
}
