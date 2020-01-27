package com.group1001.daap.dynamo

import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI

@Configuration
@EnableConfigurationProperties(DynamoProperties::class)
@Import(EntityRegistry::class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Profile("!test")
class DynamoDBConfig(val properties: DynamoProperties) {
    @Bean
    @ConditionalOnMissingBean
    fun dynamoClient(): DynamoDbClient {
        // If we have an override specified, use it, otherwise, pull from the environment
        val url = properties.endpointOverride
        return if (url != null) {
            DynamoDbClient.builder()
                .endpointOverride(URI(url)) // These credentials are hard coded because that's what the docker-compose expects
                .credentialsProvider { AwsBasicCredentials.create("local", "local") }
                .region(Region.US_EAST_2)
                .build()
        } else {
            DynamoDbClient.create()
        }
    }

    @Bean
    fun tableCreationService(properties: DynamoProperties, client: DynamoDbClient) =
        TableCreationService(properties, client)
}

@ConstructorBinding
@ConfigurationProperties(prefix = "com.group1001.dynamo")
class DynamoProperties(
    val tableCreationMode: TableCreationMode,
    val classPackage: String,
    val endpointOverride: String? = null
) {
    enum class TableCreationMode {
        NONE,
        CREATE,
        VALIDATE
    }
}

@Configuration
@Import(EntityRegistry::class)
@EnableConfigurationProperties(DynamoProperties::class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Profile("test")
class DynamoTestConfiguration
