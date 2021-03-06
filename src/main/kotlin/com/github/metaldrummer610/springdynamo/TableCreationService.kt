package com.github.metaldrummer610.springdynamo

import org.awaitility.Awaitility.await
import org.reflections.Reflections
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.TableStatus
import java.time.Duration
import javax.annotation.PostConstruct

class TableCreationService(private val properties: DynamoProperties, private val client: DynamoDbClient) {
    // We scan our classpath as well because of the SeedRepository
    private val entityClasses = Reflections(properties.classPackage, "com.github.metaldrummer610.springdynamo").getTypesAnnotatedWith(DynamoTable::class.java, true)
    private val classNames = entityClasses.map { it.simpleName }

    @PostConstruct
    fun configureTables() {
        logger.debug("Configuring tables in ${properties.tableCreationMode} mode")
        when (properties.tableCreationMode) {
            DynamoProperties.TableCreationMode.NONE -> return
            DynamoProperties.TableCreationMode.CREATE -> {
                drop()
                create()
            }
            DynamoProperties.TableCreationMode.VALIDATE -> validate()
        }
    }

    private fun create() {
        logger.debug("Creating tables -> $classNames")
        entityClasses.forEach {
            TableBuilder.tableForEntity(client, properties, it.kotlin)
        }

        var remainingTables = classNames
        await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).until {
            logger.debug("Polling for created tables...")

            remainingTables = remainingTables.filterNot { t ->
                val table = client.describeTable { it.tableName(t) }
                table.table().tableStatus() == TableStatus.ACTIVE
            }

            remainingTables.isEmpty()
        }
    }

    private fun drop() {
        logger.debug("Dropping tables -> $classNames")
        client.listTables().tableNames().filter {
            logger.debug("Contains $it")
            classNames.contains(it)
        }.forEach {
            logger.debug("Deleting $it")
            client.deleteTable { builder -> builder.tableName(it) }
        }

        await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).until {
            logger.debug("Polling for deleted tables...")
            client.listTables().tableNames().none { classNames.contains(it) }
        }
    }

    private fun validate() {
        TODO("Implement me!")
//        client.listTables().tableNames().filter {
//            classNames.contains(it)
//        }.map { s ->
//            client.describeTable {it.tableName(s)}.table()
//        }.forEach {}
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(TableCreationService::class.java)
    }
}
