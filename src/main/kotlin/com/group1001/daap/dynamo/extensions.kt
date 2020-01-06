package com.group1001.daap.dynamo

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

fun AttributeValue.maybeS(): String? = getValueForField("S", String::class.java).orElse(null)
