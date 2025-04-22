package com.taskmanagementsystem.tasks;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.entities.Users;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.HeadersUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetTasksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDBMapper dynamoDBMapper;
    private final ObjectMapper objectMapper;
    private final String userTableName;

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
        this.userTableName = System.getenv("USER_TABLE");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(HeadersUtil.getHeaders());

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

            String cognitoUsername = claims.get("sub");
            if (cognitoUsername == null || cognitoUsername.isEmpty()) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"Unauthorized: User ID not found in token\"}");
                return response;
            }

            DynamoDBQueryExpression<Users> userQuery = new DynamoDBQueryExpression<Users>()
                    .withIndexName("EmailIndex")
                    .withConsistentRead(false)
                    .withKeyConditionExpression("cognitoUsername = :cognitoUsername")
                    .withExpressionAttributeValues(Map.of(
                            ":cognitoUsername", new AttributeValue().withS(cognitoUsername)
                    ));

            List<Users> users = dynamoDBMapper.query(Users.class, userQuery);
            if (users.isEmpty()) {
                response.setStatusCode(404);
                response.setBody("{\"message\": \"User not found\"}");
                return response;
            }

            String userEmail = users.getFirst().getEmail();

            DynamoDBQueryExpression<Tasks> taskQuery = new DynamoDBQueryExpression<Tasks>()
                    .withIndexName("AssigneeIndex")
                    .withConsistentRead(false)
                    .withKeyConditionExpression("assignedTo = :email")
                    .withExpressionAttributeValues(Map.of(
                            ":email", new AttributeValue().withS(userEmail)
                    ));

            List<Tasks> tasks = dynamoDBMapper.query(Tasks.class, taskQuery);

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("statusCode", 200);
            responseBody.put("message", tasks.isEmpty() ? "No tasks assigned" : "Tasks retrieved successfully");
            responseBody.put("data", tasks);

            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));
            return response;

        } catch (Exception e) {
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