package com.taskmanagementsystem.tasks;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.services.TaskService;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.TaskAssignmentMessage;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class ReassignTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoDbClient;
    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final String taskTableName;
    private final String userTableName;
    private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final String taskAssignmentTopic;

    public ReassignTaskHandler() {
        this.dynamoDbClient = DynamoDBUtil.getDynamoDBClient();
        this.sqsClient = SqsClient.create();
        this.snsClient = SnsClient.create();
        this.taskTableName = System.getenv("TASK_TABLE");
        this.userTableName = System.getenv("USER_TABLE");
        this.queueUrl = System.getenv("TASKS_QUEUE_URL");
        this.objectMapper = new ObjectMapper();
        this.taskService = new TaskService();
        this.taskAssignmentTopic = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {
        try {
            // Get the request body (email of user and the task id)
            String taskId = requestEvent.getPathParameters().get("taskId");
            JsonNode body = objectMapper.readTree(requestEvent.getBody());
            String newAssignedToEmail = body.get("assignedTo").asText(); // This is the email address

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

            // Check if task is closed
            Tasks task = taskService.getTask(taskId);
            // TODO: Check if task is null
            if (!task.getStatus().equals("closed") || task == null) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(403)
                        .withBody("{\"message\": \"Forbidden: Only closed tasks can be reassigned\"}");
            }

            // Get userId from UserTable using EmailIndex
            String userId = getUserIdByEmail(newAssignedToEmail, context);
            if (userId == null) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"message\": \"User not found for email: " + newAssignedToEmail + "\"}");
            }

            // Reassign the task to new user
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("taskId", new AttributeValue(taskId));

            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":newAssignedTo", new AttributeValue(newAssignedToEmail));

            UpdateItemRequest updateRequest = new UpdateItemRequest()
                    .withTableName(taskTableName)
                    .withKey(key)
                    .withUpdateExpression("SET assignedTo = :newAssignedTo")
                    .withExpressionAttributeValues(expressionAttributeValues);

            dynamoDbClient.updateItem(updateRequest);
            // update status of the task back to pending

            // Publish email with userId message attribute for filtering
            String emailMessage = String.format("You have been reassigned to task %s", task.getName());
            try {
                Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
                messageAttributes.put("userId", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(userId)
                        .build());

                PublishRequest publishRequest = PublishRequest.builder()
                        .message(emailMessage)
                        .topicArn(taskAssignmentTopic)
                        .messageAttributes(messageAttributes)
                        .build();

                PublishResponse publishResponse = snsClient.publish(publishRequest);
                context.getLogger().log("SNS message published: " + publishResponse.messageId());
            } catch (SnsException e) {
                context.getLogger().log("SNS publish failed: " + e.awsErrorDetails().errorMessage());
            }

            // Send message to SQS for notification processing
            TaskAssignmentMessage message = new TaskAssignmentMessage(taskId, newAssignedToEmail);
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
                    .messageGroupId("taskAssignments")
                    .messageDeduplicationId(taskId + "-" + System.currentTimeMillis())
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

    private String getUserIdByEmail(String email, Context context) {
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