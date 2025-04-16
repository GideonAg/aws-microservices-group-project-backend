package com.taskmanagementsystem.tasks;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.TaskAssignmentMessage;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class ReassignTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoDbClient;
    private final SqsClient sqsClient;
    private final String taskTableName;
    private final String queueUrl;
    private final ObjectMapper objectMapper;

    public ReassignTaskHandler() {
        this.dynamoDbClient = DynamoDBUtil.getDynamoDBClient();
        this.sqsClient = SqsClient.create();
        this.taskTableName = System.getenv("TASK_TABLE");
        this.queueUrl = System.getenv("TASKS_QUEUE_URL");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {
        try {
            // Get the request body(email/id of user and the task id)
            String taskId = requestEvent.getPathParameters().get("taskId");
            JsonNode body = objectMapper.readTree(requestEvent.getBody());
            String newAssignedTo = body.get("assignedTo").asText();
            
            // Check if authenticated user is the admin
            Map<String, Object> authorizer = requestEvent.getRequestContext().getAuthorizer();
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            String role = claims.get("custom:role");

            if (!"admin".equals(role)) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(403)
                        .withBody("{\"message\": \"Forbidden: Only admins can reassign tasks\"}");
            }
            
            // Reassign the task to new user
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("taskId", new AttributeValue(taskId));
            
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":newAssignedTo", new AttributeValue(newAssignedTo));
            
            UpdateItemRequest updateRequest = new UpdateItemRequest()
                    .withTableName(taskTableName)
                    .withKey(key)
                    .withUpdateExpression("SET assignedTo = :newAssignedTo")
                    .withExpressionAttributeValues(expressionAttributeValues);
                    
            dynamoDbClient.updateItem(updateRequest); 

            // Send message to SQS for notification processing
            TaskAssignmentMessage message = new TaskAssignmentMessage(taskId, newAssignedTo);
            String messageBody;
            try {
                messageBody = objectMapper.writeValueAsString(message);
            } catch (Exception e) {
                context.getLogger().log("Error serializing message: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(500)
                        .withBody("{\"message\": \"Error processing request\"}");
            }
            
            SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageGroupId("taskAssignments") // Required for FIFO queue
                    .messageDeduplicationId(taskId + "-" + System.currentTimeMillis()) // Add deduplication ID for FIFO queue
                    .build();
                    
            sqsClient.sendMessage(sendMsgRequest);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("{\"message\": \"Task reassigned successfully\"}");
                    
        } catch (Exception e) {
            context.getLogger().log("Error in ReassignTaskHandler: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"message\": \"Internal server error: " + e.getMessage() + "\"}");
        }
    }
}