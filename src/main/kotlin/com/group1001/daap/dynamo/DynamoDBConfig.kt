package com.group1001.daap.dynamo

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

@Configuration
@EnableConfigurationProperties(DynamoProperties::class)
@Import(EntityRegistry::class)
class DynamoDBConfig {
    @Bean
    @ConditionalOnMissingBean
    fun dynamoClient(): DynamoDbClient = DynamoDbClient.create()

    @Bean
    fun tableCreationService(properties: DynamoProperties, client: DynamoDbClient) =
        TableCreationService(properties, client)
}

@ConstructorBinding
@ConfigurationProperties(prefix = "com.group1001.dynamo")
class DynamoProperties(
    val tableCreationMode: TableCreationMode,
    val classPackage: String
) {
    enum class TableCreationMode {
        NONE,
        CREATE,
        VALIDATE
    }
}