package com.group1001.daap.dynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty

@DisabledIfSystemProperty(named = "CI", matches = "true")
class TableBuilderTest {
    @Test
    fun `should create a table`() {
        setupDB()

        assertThat(dbClient.listTables().tableNames()).contains(TestEntity::class.simpleName)
    }
}
