package com.group1001.daap.dynamo

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

fun AttributeValue.isNull(): Boolean = getValueForField("NUL", Boolean::class.javaObjectType).orElse(false)
