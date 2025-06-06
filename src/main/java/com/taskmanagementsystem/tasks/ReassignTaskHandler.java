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
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.services.TaskService;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.SnsPublisher;
// import com.taskmanagementsystem.util.SqsMessenger;
// import com.taskmanagementsystem.util.TaskAssignmentMessage;
import com.taskmanagementsystem.util.UserUtils;


public class ReassignTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoDbClient;
    private final String taskTableName;
    private final String userTableName;
    // private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final String taskAssignmentTopic;
    private final SnsPublisher snsPublisher;
    // private final SqsMessenger sqsMessenger;

    public ReassignTaskHandler() {
        this.dynamoDbClient = DynamoDBUtil.getDynamoDBClient();
        this.taskTableName = System.getenv("TASK_TABLE");
        this.userTableName = System.getenv("USER_TABLE");
        // this.queueUrl = System.getenv("TASKS_QUEUE_URL");
        this.objectMapper = new ObjectMapper();
        this.taskService = new TaskService();
        this.taskAssignmentTopic = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");
        this.snsPublisher = new SnsPublisher();
        // this.sqsMessenger = new SqsMessenger();
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
            if(task == null) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(403)
                        .withBody("{\"message\": \"Task does not exist\"}");
            }
            if (!task.getStatus().equals("closed") || task == null) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(403)
                        .withBody("{\"message\": \"Forbidden: Only closed tasks can be reassigned\"}");
            }

            // Get userId from UserTable using EmailIndex
            String userId = UserUtils.getUserIdByEmail(dynamoDbClient, userTableName, newAssignedToEmail, context);
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
            expressionAttributeValues.put(":openStatus", new  AttributeValue("open"));

            UpdateItemRequest updateRequest = new UpdateItemRequest()
                    .withTableName(taskTableName)
                    .withKey(key)
                    .withUpdateExpression("SET assignedUserEmail = :newAssignedTo, #taskStatus = :openStatus")
                    .withExpressionAttributeValues(expressionAttributeValues)
                    .withExpressionAttributeNames(Map.of("#taskStatus", "status"));

            dynamoDbClient.updateItem(updateRequest);

            // Publish email with userId message attribute for filtering
            String emailMessage = String.format("You have been reassigned to task %s", task.getName());
            snsPublisher.publishTaskAssignment(taskAssignmentTopic, emailMessage, userId, context);

            // Send message to SQS for notification processing
            // TaskAssignmentMessage message = new TaskAssignmentMessage(taskId, newAssignedToEmail);
            // sqsMessenger.sendTaskAssignmentMessage(queueUrl, message, taskId, context);

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