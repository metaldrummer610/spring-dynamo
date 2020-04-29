package com.github.metaldrummer610.springdynamo

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

fun AttributeValue.isNull(): Boolean = getValueForField("NUL", Boolean::class.javaObjectType).orElse(false)
