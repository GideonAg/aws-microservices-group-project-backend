package com.taskmanagementsystem.tasks;


import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagement.models.ApiResponse;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagement.services.DynamoDBService;
import com.taskmanagement.services.SNSService;
import com.taskmanagement.services.TaskValidationService;
import com.taskmanagement.utils.DateUtils;

import software.amazon.awssdk.services.sns.model.PublishRequest;

public class UpdateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LogManager.getLogger(UpdateTaskHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DynamoDBService dynamoDBService;
    private final SNSService snsService;
    private final TaskValidationService validationService;

    public UpdateTaskHandler() {
        this.dynamoDBService = new DynamoDBService();
        this.snsService = new SNSService();
        this.validationService = new TaskValidationService();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Processing update task request");

        try {
            // Extract task ID and user info from request
            String taskId = request.getPathParameters().get("taskId");
            Map<String, String> claims = request.getRequestContext().getAuthorizer().getClaims();
            String userEmail = claims.get("email");
            String userRole = claims.get("custom:role");

            // Get current task from database
            Tasks task = dynamoDBService.getTask(taskId);
            if (task == null) {
                return buildResponse(ApiResponse.notFound("Task not found"));
            }

            // Validate authorization
            if (!isAuthorized(userEmail, userRole, task)) {
                return buildResponse(ApiResponse.forbidden("Not authorized to update this task"));
            }

            // Parse and validate update request
            JsonNode body = objectMapper.readTree(request.getBody());
            validationService.validateTaskUpdate(body, task);

            // Apply updates to task
            updateTaskFields(task, body, userEmail);

            // Save updated task
            Tasks updatedTask = dynamoDBService.updateTask(task);

            // Handle notifications based on changes
            handleNotifications(updatedTask, userEmail, userRole);

            return buildResponse(ApiResponse.success(updatedTask));
        } catch (IllegalArgumentException e) {
            logger.warn("Validation error: {}", e.getMessage());
            return buildResponse(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating task: {}", e.getMessage(), e);
            return buildResponse(ApiResponse.serverError("Error updating task: " + e.getMessage()));
        }
    }

    private boolean isAuthorized(String userEmail, String userRole, Tasks task) {
        // Admin can update any task
        if ("admin".equalsIgnoreCase(userRole)) {
            return true;
        }

        // Regular users can only update their own tasks
        return Objects.equals(userEmail, task.getAssignedUserEmail());
    }

    private void updateTaskFields(Tasks task, JsonNode body, String userEmail) {
        long currentTime = System.currentTimeMillis();

        if (body.has("status")) {
            String newStatus = body.get("status").asText();
            task.setStatus(newStatus);

            // Set completedAt timestamp if task is being marked as completed
            if ("completed".equalsIgnoreCase(newStatus)) {
                task.setCompletedAt(currentTime);
            }
            // Reset completedAt if task is being reopened
            else if ("open".equalsIgnoreCase(newStatus) && task.getCompletedAt() != null) {
                task.setCompletedAt(null);
            }
        }

        if (body.has("userComment")) {
            task.setUserComment(body.get("userComment").asText());
        }

        if (body.has("deadline")) {
            task.setDeadline(body.get("deadline").asLong());
        }

        if (body.has("responsibility")) {
            String newAssignee = body.get("responsibility").asText();
            if (!newAssignee.equals(task.getAssignedUserEmail())) {
                task.setAssignedUserEmail(newAssignee);
                task.setStatus("open"); // Reset status when reassigned
                task.setCompletedAt(null); // Clear completion when reassigned
            }
        }

        // Always update the updatedAt timestamp
        task.setUpdatedAt(currentTime);
    }

    private void handleNotifications(Tasks updatedTask, String userEmail, String userRole) {
        String status = updatedTask.getStatus();

        // Task completion notification to admin
        if ("completed".equalsIgnoreCase(status)) {
            Map<String, Object> completionPayload = new HashMap<>();
            completionPayload.put("taskId", updatedTask.getTaskId());
            completionPayload.put("name", updatedTask.getName());
            completionPayload.put("completedBy", userEmail);
            completionPayload.put("completedAt", DateUtils.formatTimestamp(updatedTask.getCompletedAt()));
            completionPayload.put("userComment", updatedTask.getUserComment());

            snsService.publishToTopic(
                    System.getenv("TASK_COMPLETE_NOTIFICATION_TOPIC"),
                    "Task Completed: " + updatedTask.getName(),
                    completionPayload
            );
            logger.info("Sent task completion notification for task: {}", updatedTask.getTaskId());
        }

        // Task reassignment notification to new assignee
        if (updatedTask.getAssignedUserEmail() != null &&
                !updatedTask.getAssignedUserEmail().equals(userEmail) &&
                "admin".equalsIgnoreCase(userRole)) {

            Map<String, Object> assignmentPayload = new HashMap<>();
            assignmentPayload.put("taskId", updatedTask.getTaskId());
            assignmentPayload.put("name", updatedTask.getName());
            assignmentPayload.put("description", updatedTask.getDescription());
            assignmentPayload.put("deadline", DateUtils.formatTimestamp(updatedTask.getDeadline()));
            assignmentPayload.put("assignedBy", userEmail);

            PublishRequest request = PublishRequest.builder()
                    .topicArn(System.getenv("TASK_ASSIGNMENT_NOTIFICATION_TOPIC"))
                    .message(objectMapper.writeValueAsString(assignmentPayload))
                    .messageAttributes(createEmailAttribute(updatedTask.getAssignedUserEmail()))
                    .build();

            snsService.getSnsClient().publish(request);
            logger.info("Sent task assignment notification to: {}", updatedTask.getAssignedUserEmail());
        }
    }

    private Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> createEmailAttribute(String email) {
        Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("email", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(email)
                .build());
        return attributes;
    }

    private APIGatewayProxyResponseEvent buildResponse(ApiResponse response) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(response.getStatusCode())
                .withBody(response.getBody())
                .withHeaders(response.getHeaders());
    }
}