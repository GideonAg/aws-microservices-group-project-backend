package com.taskmanagementsystem.util;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsMessenger {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    public SqsMessenger() {
        this.sqsClient = SqsClient.create();
        this.objectMapper = new ObjectMapper();
    }

    public void sendTaskAssignmentMessage(String queueUrl, TaskAssignmentMessage message, String taskId, Context context) {
        try {
            String messageBody = objectMapper.writeValueAsString(message);

            SendMessageRequest sendRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageGroupId("taskAssignments")
                    .messageDeduplicationId(taskId + "-" + System.currentTimeMillis())
                    .build();

            sqsClient.sendMessage(sendRequest);
            context.getLogger().log("SQS message sent: " + messageBody);

        } catch (Exception e) {
            context.getLogger().log("Error sending message to SQS: " + e.getMessage());
        }
    }
}
