package com.taskmanagementsystem.tasks;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.util.DynamoDBUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetTasksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDBMapper dynamoDBMapper;
    private final ObjectMapper objectMapper;

    public GetTasksHandler() {
        AmazonDynamoDB dynamoDBClient = DynamoDBUtil.getDynamoDBClient();
        String taskTableName = System.getenv("TASK_TABLE");
        if (taskTableName == null || taskTableName.isEmpty()) {
            throw new IllegalStateException("TASK_TABLE environment variable is not set");
        }
        DynamoDBMapperConfig config = DynamoDBMapperConfig.builder()
                .withTableNameOverride(DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(taskTableName))
                .build();
        this.dynamoDBMapper = new DynamoDBMapper(dynamoDBClient, config);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(Map.of("Content-Type", "application/json"));

        try {
            Map<String, Object> authorizer = requestEvent.getRequestContext().getAuthorizer();
            if (authorizer == null) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"Unauthorized: No authorizer data found\"}");
                return response;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            if (claims == null) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"Unauthorized: No claims found in authorizer\"}");
                return response;
            }

            // Extract the email directly from claims
            String userEmail = claims.get("email");
            String userRole = claims.get("custom:role");
            boolean isAdmin = "admin".equals(userRole);
            
            if (userEmail == null || userEmail.isEmpty()) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"Unauthorized: Email not found in token\"}");
                return response;
            }
            
            context.getLogger().log("User email: " + userEmail);
            context.getLogger().log("User role: " + userRole);

            List<Tasks> tasks;
            
            // For admins, fetch all tasks
            if (isAdmin) {
                // Scan all tasks if the user is an admin
                tasks = dynamoDBMapper.scan(Tasks.class, new DynamoDBScanExpression());
                context.getLogger().log("Admin user - fetched all tasks: " + tasks.size());
            } else {
                // For regular users, query by assignedTo
                Map<String, AttributeValue> eav = new HashMap<>();
                eav.put(":email", new AttributeValue().withS(userEmail));
                
                DynamoDBQueryExpression<Tasks> taskQuery = new DynamoDBQueryExpression<Tasks>()
                        .withIndexName("AssigneeIndex")
                        .withConsistentRead(false)
                        .withKeyConditionExpression("assignedTo = :email")
                        .withExpressionAttributeValues(eav);

                // Additional debug logging to see what's happening
                context.getLogger().log("Query params: assignedTo = " + userEmail);
                
                tasks = dynamoDBMapper.query(Tasks.class, taskQuery);
                context.getLogger().log("Regular user - tasks found: " + tasks.size());
            }

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("statusCode", 200);
            responseBody.put("message", tasks.isEmpty() ? "No tasks assigned" : "Tasks retrieved successfully");
            responseBody.put("data", tasks);

            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));
            return response;

        } catch (Exception e) {
            context.getLogger().log("Error retrieving tasks: " + e.getMessage());
            e.printStackTrace();
            response.setStatusCode(500);
            try {
                response.setBody(objectMapper.writeValueAsString(Map.of(
                        "statusCode", 500,
                        "message", "Internal server error: " + e.getMessage()
                )));
            } catch (Exception jsonException) {
                response.setBody("{\"statusCode\": 500, \"message\": \"Internal server error\"}");
            }
            return response;
        }
    }
}