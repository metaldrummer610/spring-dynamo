package com.group1001.daap.dynamo

import org.reflections.Reflections
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.config.RuntimeBeanReference
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.ResolvableType
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.core.type.AnnotationMetadata
import kotlin.reflect.KProperty1
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType

@UseExperimental(ExperimentalStdlibApi::class)
class EntityRegistry : ImportBeanDefinitionRegistrar, BeanFactoryAware, EnvironmentAware {
    private lateinit var environment: Environment
    private lateinit var beanFactory: BeanFactory

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        logger.debug("Registering repository beans")
        val properties = beanFactory.getBean(DynamoProperties::class.java)
        val testMode = environment.acceptsProfiles(Profiles.of("test"))

        // Iterate over all the Throughput annotated classes in the given paths
        // and register a SimpleDynamoRepository instance for them
        Reflections(properties.classPackage)
            .getTypesAnnotatedWith(DynamoTable::class.java, true)
            .filterNot { registry.isBeanNameInUse(beanName(it)) }
            .forEach {
                logger.debug("Registering entity ${it.simpleName}")
                val partitionKey = it.kotlin.memberProperties.first { prop -> prop.hasAnnotation<PartitionKey>() }
                val sortKey = it.kotlin.memberProperties.firstOrNull { prop -> prop.hasAnnotation<SortKey>() }

                val repositoryClass: Class<*>
                val genericType: ResolvableType

                if (sortKey != null) {
                    repositoryClass = if (testMode) {
                        CompositeInMemoryRepository::class.java
                    } else {
                        DefaultCompositeKeyRepository::class.java
                    }
                    genericType = ResolvableType.forClassWithGenerics(CompositeKeyRepository::class.java, it, classFromProperty(partitionKey), classFromProperty(sortKey))
                } else {
                    repositoryClass = if (testMode) {
                        SimpleInMemoryRepository::class.java
                    } else {
                        DefaultSimpleKeyRepository::class.java
                    }
                    genericType = ResolvableType.forClassWithGenerics(SimpleKeyRepository::class.java, it, classFromProperty(partitionKey))
                }

                val beanDefinition = RootBeanDefinition(repositoryClass)
                if (!testMode) {
                    beanDefinition.constructorArgumentValues.addIndexedArgumentValue(0, RuntimeBeanReference("dynamoClient"))
                    beanDefinition.constructorArgumentValues.addIndexedArgumentValue(1, it.kotlin)
                }

                // Need to set the target type so that we can autowire resolve this bean by using [SimpleKeyRepository<T>] style syntax
                beanDefinition.setTargetType(genericType)

                registry.registerBeanDefinition(beanName(it), beanDefinition)
                logger.debug("Registered ${it.simpleName} as $beanDefinition")
            }
    }

    private fun beanName(it: Class<*>) = "${it.simpleName.decapitalize()}Repository"

    private fun classFromProperty(prop: KProperty1<out Any, Any?>) = prop.returnType.javaType as Class<*>

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }
    companion object {
        val logger: Logger = LoggerFactory.getLogger(TableCreationService::class.java)

    }
}
