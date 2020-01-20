package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TableBuilderTest {
    @Test
    fun `should create a table`() {
        setupDB()

        assertThat(dbClient.listTables().tableNames()).contains(TestEntity::class.simpleName)
    }
}
