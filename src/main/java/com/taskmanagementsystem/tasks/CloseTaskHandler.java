package com.taskmanagementsystem.tasks;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.regions.Region;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class CloseTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String REGION = System.getenv("REGION");

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.of(REGION))
            .build();
    private final SnsClient snsClient = SnsClient.builder()
            .region(Region.of(REGION))
            .build();

    private final String taskTable = System.getenv("TASK_TABLE");
    private final String closedTaskTopicArn = System.getenv("CLOSED_TASK_TOPIC_ARN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(Map.of("Content-Type", "application/json"));

        try {
            String taskId = request.getPathParameters().get("taskId");

            Map<String, Object> authorizer = request.getRequestContext().getAuthorizer();
            if (authorizer == null || !authorizer.containsKey("claims")) {
                response.setStatusCode(HttpStatusCode.UNAUTHORIZED);
                response.setBody("{\"message\": \"Authorizer claims not found\"}");
                return response;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            String userEmail = claims.get("email");
            String userRole = claims.get("custom:role");

            if (userEmail == null) {
                response.setStatusCode(HttpStatusCode.UNAUTHORIZED);
                response.setBody("{\"message\": \"User email not found in claims\"}");
                return response;
            }

            boolean isAdmin = "admin".equals(userRole);
            if (!isAdmin) {
                return response
                        .withStatusCode(HttpStatusCode.FORBIDDEN)
                        .withBody("{\"message\": \"Only administrators can close a task\"}");
            }

            CloseTaskRequest closeTaskRequest = mapper.readValue(request.getBody(), CloseTaskRequest.class);
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("taskId", AttributeValue.builder().s(taskId).build());

            // Convert LocalDateTime to appropriate formats
            long updatedAtTimestamp = Instant.now().getEpochSecond(); // Unix timestamp for updatedAt
            String closedAtString = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); // ISO string for closedAt

            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":isClosed", AttributeValue.builder().bool(true).build());
            expressionAttributeValues.put(":closedAt", AttributeValue.builder().s(closedAtString).build());
            expressionAttributeValues.put(":adminComment", AttributeValue.builder().s(closeTaskRequest.adminComment()).build());
            expressionAttributeValues.put(":updatedAt", AttributeValue.builder().n(String.valueOf(updatedAtTimestamp)).build());

            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(taskTable)
                    .key(key)
                    .updateExpression("SET isClosed = :isClosed, closedAt = :closedAt, adminComment = :adminComment, updatedAt = :updatedAt")
                    .expressionAttributeValues(expressionAttributeValues)
                    .returnValues("ALL_NEW")
                    .build();
            var updateResult = dynamoDbClient.updateItem(updateItemRequest);

            Map<String, AttributeValue> attributes = updateResult.attributes();
            String taskName = attributes.get("name").s();
            String assignedUserEmail = attributes.get("assignedUserEmail").s();
            String status = attributes.get("status").s();

            Map<String, String> messageAttributes = new HashMap<>();
            messageAttributes.put("email", assignedUserEmail);
            messageAttributes.put("taskId", taskId);
            messageAttributes.put("taskName", taskName);
            messageAttributes.put("status", status);
            messageAttributes.put("isClosed", "true");
            messageAttributes.put("adminComment", closeTaskRequest.adminComment());

            PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(closedTaskTopicArn)
                    .message(mapper.writeValueAsString(messageAttributes))
                    .subject("Task Closed: " + taskName)
                    .build();
            snsClient.publish(publishRequest);

            return response
                    .withStatusCode(200)
                    .withBody("{\"message\": \"Task closed successfully\", \"taskId\": \"" + taskId + "\"}");

        } catch (Exception e) {
            context.getLogger().log("Error closing task: " + e.getMessage());
            return response
                    .withStatusCode(500)
                    .withBody("{\"message\": \"Error closing task: " + e.getMessage() + "\"}");
        }
    }

    public record CloseTaskRequest(String adminComment) {}
}