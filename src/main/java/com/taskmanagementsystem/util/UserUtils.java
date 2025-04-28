package com.taskmanagementsystem.util;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.lambda.runtime.Context;

public class UserUtils {

    public static String getUserIdByEmail(AmazonDynamoDB dynamoDbClient, String userTableName, String email, Context context) {
        try {
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":email", new AttributeValue().withS(email));

            QueryRequest queryRequest = new QueryRequest()
                    .withTableName(userTableName)
                    .withIndexName("EmailIndex")
                    .withKeyConditionExpression("email = :email")
                    .withExpressionAttributeValues(expressionAttributeValues);

            QueryResult result = dynamoDbClient.query(queryRequest);
            if (result.getItems().isEmpty()) {
                context.getLogger().log("No user found for email: " + email);
                return null;
            }

            return result.getItems().get(0).get("userId").getS();

        } catch (Exception e) {
            context.getLogger().log("Error querying UserTable: " + e.getMessage());
            return null;
        }
    }
}
