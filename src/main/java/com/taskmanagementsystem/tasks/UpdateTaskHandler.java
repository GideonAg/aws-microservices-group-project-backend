package com.taskmanagementsystem.tasks;


import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


import com.taskmanagementsystem.entities.Users;
import com.taskmanagementsystem.services.TaskService;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.HeadersUtil;
import com.taskmanagementsystem.util.SnsPublisher;
import com.taskmanagementsystem.util.TasksMapper;
import com.taskmanagementsystem.util.UserUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;


import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;



/**
 * AWS Lambda handler for updating tasks in a task management system.
 * This handler processes API Gateway requests to update task details in a DynamoDB table.
 * It also sends notifications via Amazon SNS when tasks are completed or reassigned.
 */
public class UpdateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
   private static final Logger logger = LogManager.getLogger(UpdateTaskHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AmazonDynamoDB dynamoDbClient;
    private final TaskService taskService;
    private final SnsClient snsClient;
    private final String taskAssignmentTopic;
    private final SnsPublisher snsPublisher;
    private final String userTableName;
    private final String taskTableName;
    private final String taskCompleteTopicArn;



    /**
     * Default constructor initializing AWS SDK clients for DynamoDB and SNS.
     */
    public UpdateTaskHandler() {
        this.taskService = new TaskService();
        this.dynamoDbClient = DynamoDBUtil.getDynamoDBClient();
        this.snsClient = SnsClient.builder().build();
        this.taskAssignmentTopic = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");
        this.snsPublisher = new SnsPublisher();
        this.userTableName = System.getenv("USER_TABLE");
        this.taskTableName = System.getenv("TASK_TABLE");
        this.taskCompleteTopicArn = System.getenv("TASK_COMPLETE_TOPIC_ARN");
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
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(HeadersUtil.getHeaders());

        try {
            String taskId = requestEvent.getPathParameters().get("taskId");
            Map<String, Object> authorizeMap = requestEvent.getRequestContext().getAuthorizer();

            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizeMap.get("claims");
            String userEmail = claims.get("email");
            String userRole = claims.get("custom:role");
            boolean isAdmin = "admin".equals(userRole);
            JsonNode messageBody = objectMapper.readTree(requestEvent.getBody());

            Users user = Users.builder()
                    .email(userEmail)
                    .isAdmin(isAdmin)
                    .build();

            Map<String, AttributeValue> key = new HashMap<>();
            key.put("taskId", new AttributeValue(taskId));

            Tasks task = taskService.getTask(taskId);
            if (task == null) {
                response.setStatusCode(404);
                response.setBody("{\"message\": \"Task not found\"}");
                return response;
            }

            
            
            if (!user.isAdmin()) {
                // normal user
                try {
                    context.getLogger().log("Task update for user started");
                    if (messageBody.has("status")) {
                        task.setStatus(messageBody.get("status").asText());
                    }

                    if (messageBody.has("userComment")) {
                        task.setUserComment(messageBody.get("userComment").asText());
                    }

                    // If task was marked as completed, notify admin
                    if ((messageBody.get("status").asText()).equals("complete")) {

                        Map<String, Object> notificationPayload = new HashMap<>();
                        notificationPayload.put("taskId", taskId);
                        notificationPayload.put("name", task.getName());
                        notificationPayload.put("completedBy",  task.getAssignedUserEmail());
                        notificationPayload.put("userComment", messageBody.get("userComment").asText());

                         Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
                            messageAttributes.put("role", MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue("admin")
                                    .build());

                        PublishRequest publishRequest = PublishRequest.builder()
                                .topicArn(taskCompleteTopicArn)
                                .message(objectMapper.writeValueAsString(notificationPayload))
                                .messageAttributes(messageAttributes)
                                .build();

                        snsClient.publish(publishRequest);
                        context.getLogger().log("Task Updated...Email Sent");
                    }

                    task.setStatus("complete");

                    Map<String, AttributeValue> updateItemRequest = TasksMapper.toItem(task);
                    PutItemRequest putItemRequest = new PutItemRequest()
                        .withTableName(taskTableName)
                        .withItem(updateItemRequest);

                    dynamoDbClient.putItem(putItemRequest);
                    logger.info("Task {} updated successfully", taskId);

                    return createSuccessResponse();
                } catch (Exception e) {
                    context.getLogger().log("ERROR:" + e.getMessage());
                    return createServerErrorResponse("There was an error while updating the task");
                }
            }
            else {
                try {
                    // admin
                    // set the values
                    if (messageBody.has("name")) {
                        task.setName(messageBody.get("name").asText());
                    }

                    if (messageBody.has("status")) {
                        task.setStatus(messageBody.get("status").asText());
                    }
    
                    if (messageBody.has("description")) {
                        task.setDescription(messageBody.get("description").asText());
                    }

                    if (messageBody.has("adminComment")) {
                        task.setAdminComment(messageBody.get("adminComment").asText());
                    }

                    boolean newlyAssigned = false;
                    if (messageBody.has("assignedUserEmail") &&
                            !Objects.equals(messageBody.get("assignedUserEmail").asText(), task.getAssignedUserEmail())) {
                        if (!task.getStatus().equals("closed") || task == null) {
                            return new APIGatewayProxyResponseEvent()
                                    .withStatusCode(403)
                                    .withHeaders(HeadersUtil.getHeaders())
                                    .withBody("{\"message\": \"Forbidden: Only closed tasks can be reassigned\"}");
                        }
                        task.setAssignedUserEmail(messageBody.get("assignedUserEmail").asText());
                        newlyAssigned = true;
                    }

                    if (messageBody.has("deadline") &&
                            !Objects.equals(task.getDeadline(), messageBody.get("deadline").asLong())) {
                        task.setDeadline(messageBody.get("deadline").asLong());
                    }

                    task.setStatus("open");

                    Map<String, AttributeValue> updateItemRequest = TasksMapper.toItem(task);
                    PutItemRequest putItemRequest = new PutItemRequest()
                        .withTableName(taskTableName)
                        .withItem(updateItemRequest);

                    dynamoDbClient.putItem(putItemRequest);
                    logger.info("Task {} updated successfully", taskId);

                    

                    

                    if(newlyAssigned) {
                        String userId = UserUtils.getUserIdByEmail(dynamoDbClient, userTableName, messageBody.get("assignedUserEmail").asText(), context);
                        String emailMessage = String.format("You have been reassigned to task %s", task.getName());
                        snsPublisher.publishTaskAssignment(taskAssignmentTopic, emailMessage, userId, context);
                    }

                    return createSuccessResponse();


                } catch (Exception e) {
                    return createServerErrorResponse("There was an error while updating the task");
                }
            }

        } catch (Exception e) {
            return createServerErrorResponse("There was an error while updating the task");
        }
    }


    // private static APIGatewayProxyResponseEvent createNotFoundResponse(String message) {
    //     return new APIGatewayProxyResponseEvent()
    //             .withStatusCode(404)
    //             .withBody("{\"error\": \"" + message + "\"}")
    //             .withHeaders(Map.of("Content-Type", "application/json"));
    // }

    // private static APIGatewayProxyResponseEvent createForbiddenResponse(String message) {
    //     return new APIGatewayProxyResponseEvent()
    //             .withStatusCode(403)
    //             .withBody("{\"error\": \"" + message + "\"}")
    //             .withHeaders(Map.of("Content-Type", "application/json"));
    // }


    private static APIGatewayProxyResponseEvent createServerErrorResponse(String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\": \"" + message + "\"}")
                .withHeaders(HeadersUtil.getHeaders());
    }


    private static APIGatewayProxyResponseEvent createSuccessResponse() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("{\"message\": \"Task updated successfully\"}")
                .withHeaders(HeadersUtil.getHeaders());
    }
}