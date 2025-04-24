package com.taskmanagementsystem.notifications;

import java.io.IOException;
import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.services.TaskService;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.SnsPublisher;
import com.taskmanagementsystem.util.UserUtils;

import lombok.Data;

public class ProcessTaskQueueHandler implements RequestHandler<SQSEvent, Void> {
    private static final Logger logger = LogManager.getLogger(ProcessTaskQueueHandler.class);
    private final AmazonDynamoDB dynamoDB = DynamoDBUtil.getDynamoDBClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    TaskService taskService = new TaskService();
    private final String userTable = System.getenv("USER_TABLE");
    private final String taskAssignmentTopicArn = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");
    private final SnsPublisher publish = new SnsPublisher();

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message, context);
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
                // Allow message to go to DLQ via redrive policy
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private void processMessage(SQSEvent.SQSMessage message, Context context) throws IOException {
        TaskMessage taskMessage = objectMapper.readValue(message.getBody(), TaskMessage.class);
        Tasks task = taskService.getTask(taskMessage.getTaskId());

        if (task == null) {
            logger.error("Task not found: {}", taskMessage.getTaskId());
            return;
        }

        String userId = UserUtils.getUserIdByEmail(dynamoDB, userTable, task.getAssignedUserEmail(), context);
        if (userId == null) {
            context.getLogger().log("User not found for email:  " + task.getAssignedUserEmail());
            return;
        }

        publishTaskAssignmentNotification(task, userId, context);
    }

    private void publishTaskAssignmentNotification(Tasks task, String userId, Context context) {
        String message = String.format(
                "New task assigned: %s\nDescription: %s\nDeadline: %s",
                task.getName(),
                task.getDescription(),
                Instant.ofEpochMilli(task.getDeadline()).toString()
        );

        publish.publishTaskAssignment(taskAssignmentTopicArn, message, userId, context);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TaskMessage {
        private String taskId;
    }
}
