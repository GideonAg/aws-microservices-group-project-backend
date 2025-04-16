package com.taskmanagementsystem.util;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;


/**
 * Utility class to provide a single, shared instance of the AmazonDynamoDB client.

 * This ensures that the application reuses the same DynamoDB connection,
 * avoiding unnecessary overhead from creating multiple clients.
 * Usage:
 *   AmazonDynamoDB client = DynamoDBUtil.getDynamoDBClient();
 */
public class DynamoDBUtil {
    private static final AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.standard().build();

    public static AmazonDynamoDB getDynamoDBClient(){
        return dynamoDBClient;
    }
}