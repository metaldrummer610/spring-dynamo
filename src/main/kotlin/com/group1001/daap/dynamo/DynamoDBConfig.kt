package com.group1001.daap.dynamo

import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI

@Configuration
@EnableConfigurationProperties(DynamoProperties::class)
@Import(EntityRegistry::class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
class DynamoDBConfig(val properties: DynamoProperties) {
    @Bean
    @ConditionalOnMissingBean
    fun dynamoClient(): DynamoDbClient {
        // If we have an override specified, use it, otherwise, pull from the environment
        val url = properties.endpointOverride
        return if (url != "") {
            DynamoDbClient.builder()
                .endpointOverride(URI(url))
                .credentialsProvider {
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey)).resolveCredentials()
                }
                .region(Region.US_EAST_1)
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
    val tableCreationMode: TableCreationMode = TableCreationMode.NONE,

    /**
     * If this is empty, the entire classpath will be scanned!
     */
    val classPackage: String = "",
    val endpointOverride: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val sse: ServerSideEncryption = ServerSideEncryption()
) {
    enum class TableCreationMode {
        NONE,
        CREATE,
        VALIDATE
    }

    data class ServerSideEncryption(
        val type: SSEType = SSEType.NONE,
        val kmsMasterKey: String = ""
    )
}
