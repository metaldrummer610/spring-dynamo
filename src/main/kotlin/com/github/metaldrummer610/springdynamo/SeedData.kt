package com.github.metaldrummer610.springdynamo

import org.reflections.Reflections
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.core.Ordered
import org.springframework.core.ResolvableType
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

/**
 * DataSeeder provides the logic to apply static seed data to select [DynamoTable]s. All one has to do is
 * create a class that implements [SeedData] and has a [SeedInfo] annotation, and it will be run on the
 * subsequent run of the application. Each [SeedData] is run only once. The inspriation for this came from
 * Flyway's Migration pattern (though DynamoDb doesn't need migrations)
 */
class DataSeeder(private val properties: DynamoProperties, private val beanFactory: ListableBeanFactory) : InitializingBean, Ordered {
    private lateinit var seedRepo: SeedRepository
    override fun afterPropertiesSet() {
        seedRepo = beanFactory.getBean<SeedRepository>("seedRepository")

        insertSeedData()
    }

    /**
     * Finds and applies all [SeedData] instances found in the classpath
     */
    private fun insertSeedData() {
        logger.info("Starting to Seed Data")

        Reflections(properties.classPackage)
            .getSubTypesOf(SeedData::class.java)
            .groupBy {
                val info = requireNotNull(it.kotlin.findAnnotation<SeedInfo>()) { "SeedData implementations *must* have a SeedInfo annotation" }
                info.table.simpleName!!
            }
            .values
            .forEach { data ->
                data.sortedBy { it.getAnnotation(SeedInfo::class.java).version }.forEach { applySeedData(it) }
            }
    }

    /**
     * Does the actual application of the [SeedData], if it hasn't already been run
     */
    private fun applySeedData(clazz: Class<out SeedData>) {
        val info = clazz.getAnnotation(SeedInfo::class.java)

        val tableName = info.table.simpleName!!
        if (seedRepo.findById(tableName, info.version) != null) {
            // We can skip this one as it's already been applied
            return
        }
        logger.info("Applying version ${info.version} to $tableName")

        val constructor = clazz.kotlin.primaryConstructor!!
        val instance = constructor.callBy(constructor.parameters.associateWith {
            // Have to perform this wonky around-about way to get beans because the most common thing injected here
            // is a Repository, and that has generics which act all sorts of weird with type erasure
            val names = beanFactory.getBeanNamesForType(ResolvableType.forType(it.type.javaType))
            beanFactory.getBean(names[0])
        })
        instance.execute()

        seedRepo.save(Seed(tableName, info.version))
        logger.info("Version ${info.version} of $tableName has been applied")
    }

    override fun getOrder() = 0

    companion object {
        val logger: Logger = LoggerFactory.getLogger(DataSeeder::class.java)
    }
}

/**
 * SeedInfo defines metadata about a specific version of [SeedData]
 *
 * @param version The version number of this seed data
 * @param table The [DynamoTable] class this seed data is applied to
 */
annotation class SeedInfo(val version: Int, val table: KClass<*>)

/**
 * Marker interface needed to find and execute seed data instructions.
 * NOTE: Any class that implements this interface can have *any* Spring Bean
 * injected into it through it's constructor ONLY. [@Autowired] annotations
 * DO NOT WORK for these classes
 */
interface SeedData {
    fun execute()
}

/**
 * Internal typealias used with the [Seed] table
 */
typealias SeedRepository = CompositeKeyRepository<Seed, String, Int>

/**
 * Metadata table used to store what [SeedData] has been run against what tables
 */
@DynamoTable
data class Seed(
    @PartitionKey val table: String,
    @SortKey val version: Int,
    val appliedOn: LocalDateTime = LocalDateTime.now()
)
