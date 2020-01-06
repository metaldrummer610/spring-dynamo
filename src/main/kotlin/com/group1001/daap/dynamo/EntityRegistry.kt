package com.group1001.daap.dynamo

import org.reflections.Reflections
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.config.RuntimeBeanReference
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.ResolvableType
import org.springframework.core.type.AnnotationMetadata

class EntityRegistry : ImportBeanDefinitionRegistrar, BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        logger.debug("Registering repository beans")
        val properties = beanFactory.getBean(DynamoProperties::class.java)

        // Iterate over all the Throughput annotated classes in the given paths
        // and register a SimpleDynamoRepository instance for them
        Reflections(properties.classPackage)
            .getTypesAnnotatedWith(Throughput::class.java)
            .filterNot { registry.isBeanNameInUse(beanName(it)) }
            .forEach {
                logger.debug("Registering entity ${it.simpleName}")

                val beanDefinition = RootBeanDefinition(SimpleDynamoRepository::class.java)
                beanDefinition.constructorArgumentValues.addIndexedArgumentValue(0, RuntimeBeanReference("dynamoClient"))
                beanDefinition.constructorArgumentValues.addIndexedArgumentValue(1, it.kotlin)

                // Need to set the target type so that we can autowire resolve this bean by using [DynamoRepository<T>] style syntax
                beanDefinition.setTargetType(ResolvableType.forClassWithGenerics(DynamoRepository::class.java, it))

                registry.registerBeanDefinition(beanName(it), beanDefinition)
            }
    }

    private fun beanName(it: Class<*>) = "${it.simpleName.decapitalize()}Repository"

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(TableCreationService::class.java)
    }
}
