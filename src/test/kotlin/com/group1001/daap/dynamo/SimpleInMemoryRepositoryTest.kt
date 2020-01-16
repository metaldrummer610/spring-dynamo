package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class SimpleInMemoryRepositoryTest {
    lateinit var repo: SimpleInMemoryRepository<Foo, UUID>

    @BeforeEach
    fun setUp() {
        repo = SimpleInMemoryRepository()
    }

    @Test
    internal fun `should be able to add and remove`() {
        val foo = Foo()

        repo.save(foo)
        assertThat(repo.findById(foo.id)).isNotNull.isEqualToComparingFieldByField(foo)
        repo.deleteOne(foo.id)
        assertThat(repo.findById(foo.id)).isNull()
    }

    @Test
    fun `projections should work too`() {
        val foo = Foo()

        repo.save(foo)
        assertThat(repo.asProjection(foo.id, Foo.FooProjection::class)).isEqualToComparingFieldByField(Foo.FooProjection(foo.name))
    }
}
