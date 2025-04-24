package com.taskmanagementsystem.tasks;


import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


import com.taskmanagementsystem.entities.Users;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.SnsPublisher;
import com.taskmanagementsystem.util.TasksMapper;
import com.taskmanagementsystem.util.UserUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;


import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;



/**
 * AWS Lambda handler for updating tasks in a task management system.
 * This handler processes API Gateway requests to update task details in a DynamoDB table.
 * It also sends notifications via Amazon SNS when tasks are completed or reassigned.
 */
public class UpdateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
   private static final Logger logger = LogManager.getLogger(UpdateTaskHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DynamoDbClient dynamoDbClient;
    private final AmazonDynamoDB dynamoDbClientV1;
    private final SnsClient snsClient;
    private final String taskAssignmentTopic;
    private final SnsPublisher snsPublisher;
    private final String userTableName;



    /**
     * Default constructor initializing AWS SDK clients for DynamoDB and SNS.
     */
    public UpdateTaskHandler() {
        this.dynamoDbClientV1 = DynamoDBUtil.getDynamoDBClient();
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.taskAssignmentTopic = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");
        this.snsPublisher = new SnsPublisher();
        this.userTableName = System.getenv("USER_TABLE");
    }



    /**
     * Handles the API Gateway request to update a task.
     *
     * @param requestEvent The API Gateway request event containing the task update details.
     * @param context      The Lambda execution context.
     * @return An API Gateway response event with the result of the update operation.
     */
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {
        logger.info("Processing update task request ");

        try {
            String taskId = requestEvent.getPathParameters().get("taskId");
            Map<String, Object> authorizeMap = requestEvent.getRequestContext().getAuthorizer();
            // JsonNode claimsNode = objectMapper.valueToTree(authorizeMap.get("claims"));

            // if (!claimsNode.has("email") || !claimsNode.has("admin")) {
            //     return createServerErrorResponse("Missing required fields in claims");
            // }
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizeMap.get("claims");
            String userEmail = claims.get("email");
            String userRole = claims.get("custom:role");
            boolean isAdmin = "admin".equals(userRole);

            Users user = Users.builder()
                    .email(userEmail)
                    .isAdmin(isAdmin)
                    .build();

            Map<String, AttributeValue> key = new HashMap<>();
            key.put("taskId", AttributeValue.builder().s(taskId).build());

            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(System.getenv("TASK_TABLE"))
                    .key(key)
                    .build();

            try {
                GetItemResponse getItemResponse = dynamoDbClient.getItem(getItemRequest);

                if (!getItemResponse.hasItem() || getItemResponse.item().isEmpty()) {
                    return createNotFoundResponse("Task with id " + taskId + " not found");
                }

                Map<String, AttributeValue> item = getItemResponse.item();
                Tasks task = TasksMapper.fromItem(item);

                if (!user.isAdmin()) {
                    return createForbiddenResponse("Not authorized to update this task");
                }

                JsonNode messageBody = objectMapper.readTree(requestEvent.getBody());

                if (messageBody.has("status")) {
                    task.setStatus(messageBody.get("status").asText());
                }

                if (messageBody.has("description")) {
                    task.setDescription(messageBody.get("description").asText());
                }

                // If admin reassigns the task to another person.
                boolean newlyAssigned = false;
                if (messageBody.has("assignedUserEmail") &&
                        !Objects.equals(messageBody.get("assignedUserEmail").asText(), task.getAssignedUserEmail())) {
                    task.setAssignedUserEmail(messageBody.get("assignedUserEmail").asText());
                    newlyAssigned = true;

                }

                if (messageBody.has("deadline") &&
                        !Objects.equals(task.getDeadline(), messageBody.get("deadline").asLong())) {
                    task.setDeadline(messageBody.get("deadline").asLong());
                }

                if (messageBody.has("deadline")) {
                    task.setDeadline(messageBody.get("deadline").asLong());
                }

                // If status is being changed to completed, add a completedAt timestamp
                if (messageBody.has("status") && Objects.equals(messageBody.get("status").asText(), "completed")) {
                    task.setCompletedAt(System.currentTimeMillis());
                }

                task.setUpdatedAt(System.currentTimeMillis());


                Map<String, AttributeValue> updateItemRequest = TasksMapper.toItem(task);

                PutItemRequest putItemRequest = PutItemRequest.builder()
                        .tableName(System.getenv("TASK_TABLE"))
                        .item(updateItemRequest)
                        .build();

                this.dynamoDbClient.putItem(putItemRequest);

                GetItemResponse updatedItem = this.dynamoDbClient.getItem(getItemRequest);
                Map<String, AttributeValue> updatedItemMap = updatedItem.item();
                Tasks updatedTask = TasksMapper.fromItem(updatedItemMap);


                // If task was marked as completed, notify admin
                if (updatedTask.getStatus().equals("completed")) {
                    String topicArn = System.getenv("TASK_COMPLETE_NOTIFICATION_TOPIC");

                    Map<String, Object> notificationPayload = new HashMap<>();
                    notificationPayload.put("taskId", updatedTask.getTaskId());
                    notificationPayload.put("name", updatedTask.getName());
                    notificationPayload.put("completedBy", updatedTask.getAssignedUserEmail());
                    notificationPayload.put("userComment", updatedTask.getUserComment() != null ? updatedTask.getUserComment() : "");

                    PublishRequest publishRequest = PublishRequest.builder()
                            .topicArn(topicArn)
                            .message(objectMapper.writeValueAsString(notificationPayload))
                            .build();

                    snsClient.publish(publishRequest);
                    logger.info("Task completion notification sent: {}", updatedTask.getTaskId());
                }

                if (newlyAssigned) {
                    
                    String userId = UserUtils.getUserIdByEmail(dynamoDbClientV1, userTableName, messageBody.get("assignedUserEmail").asText(), context);
                    String emailMessage = String.format("You have been reassigned to task %s", task.getName());
                    snsPublisher.publishTaskAssignment(taskAssignmentTopic, emailMessage, userId, context);
                    // String topicArn = System.getenv("TASK_ASSIGNED_NOTIFICATION_TOPIC");

                    // PublishRequest publishRequest = PublishRequest.builder()
                    //         .topicArn(topicArn)
                    //         .message(objectMapper.writeValueAsString(updateItemRequest))
                    //         .build();

                    // snsClient.publish(publishRequest);
                    // logger.info("Task assignment notification sent to {} for task: {}",
                    //         updatedTask.getAssignedUserEmail(), updatedTask.getTaskId());

                }


                return createSuccessResponse(updatedTask);

            } catch (IllegalArgumentException e) {
                logger.error("Error while updating task : {}", e.getMessage());
                return createServerErrorResponse(e.getMessage());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static APIGatewayProxyResponseEvent createNotFoundResponse(String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(404)
                .withBody("{\"error\": \"" + message + "\"}")
                .withHeaders(Map.of("Content-Type", "application/json"));
    }

    private static APIGatewayProxyResponseEvent createForbiddenResponse(String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(403)
                .withBody("{\"error\": \"" + message + "\"}")
                .withHeaders(Map.of("Content-Type", "application/json"));
}


    private static APIGatewayProxyResponseEvent createServerErrorResponse(String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\": \"" + message + "\"}")
                .withHeaders(Map.of("Content-Type", "application/json"));
    }


    private static APIGatewayProxyResponseEvent createSuccessResponse(Tasks task) {
        try {
            String jsonBody = new ObjectMapper().writeValueAsString(task);
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(jsonBody)
                    .withHeaders(Map.of("Content-Type", "application/json"));
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"Failed to serialize task object\"}")
                    .withHeaders(Map.of("Content-Type", "application/json"));
        }
    }

}