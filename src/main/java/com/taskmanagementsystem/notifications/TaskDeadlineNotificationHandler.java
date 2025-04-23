package com.taskmanagementsystem.notifications;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import com.taskmanagementsystem.util.HeadersUtil;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class TaskDeadlineNotificationHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger logger = LogManager.getLogger(TaskDeadlineNotificationHandler.class);

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SnsClient snsClient = SnsClient.create();
    private final String taskTable = System.getenv("TASK_TABLE");
    private final String deadlineTopicArn = System.getenv("TASK_DEADLINE_TOPIC_ARN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(HeadersUtil.getHeaders());

        try {
            // Calculate time window: tasks with deadlines between now and 1 hour from now (in microseconds)
            // Convert milliseconds to microseconds
            long nowMicros = Instant.now().toEpochMilli() * 1000;
            // 1 hour = 3,600,000,000 microseconds
            long oneHourFromNowMicros = nowMicros + 3_600_000_000L;

            // Scan tasks that are open and have deadlines within the next hour
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":status", AttributeValue.builder().s("open").build());
            expressionAttributeValues.put(":startTime", AttributeValue.builder().n(String.valueOf(nowMicros)).build());
            expressionAttributeValues.put(":endTime", AttributeValue.builder().n(String.valueOf(oneHourFromNowMicros)).build());

            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(taskTable)
                    .filterExpression("status = :status AND deadline >= :startTime AND deadline <= :endTime")
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
            int notificationCount = 0;

            // Process each qualifying task
            for (Map<String, AttributeValue> item : scanResponse.items()) {
                String taskId = item.get("taskId").s();
                String taskName = item.get("name").s();
                String assignedTo = item.get("responsibility").s();
                String deadlineMicros = item.get("deadline").n();

                // Convert deadline to human-readable format for the notification (microseconds to milliseconds)
                // Convert microseconds to milliseconds
                long deadlineMillis = Long.parseLong(deadlineMicros) / 1000;
                String deadlineFormatted = DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.ofEpochMilli(deadlineMillis));

                // Publish SNS notification with userId filter
                String message = String.format(
                        "Reminder: Task \"%s\" (TaskId: %s) is due at %s UTC",
                        taskName, taskId, deadlineFormatted
                );

                Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
                messageAttributes.put("userId", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(assignedTo)
                        .build());
                messageAttributes.put("notificationType", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("TASK_DEADLINE")
                        .build());

                PublishRequest publishRequest = PublishRequest.builder()
                        .topicArn(deadlineTopicArn)
                        .message(message)
                        .messageAttributes(messageAttributes)
                        .build();

                snsClient.publish(publishRequest);
                notificationCount++;
                logger.info("Published notification for task: {}, assignedTo: {}, deadline: {}", taskId, assignedTo, deadlineFormatted);
            }

            // Return success response
            response.setStatusCode(200);
            response.setBody(String.format("{\"message\": \"Processed %d task deadline notifications\"}", notificationCount));
        } catch (Exception e) {
            logger.error("Error processing task deadline notifications", e);
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Internal server error\"}");
        }

        return response;
    }
}