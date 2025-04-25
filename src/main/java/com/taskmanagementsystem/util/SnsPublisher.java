package com.taskmanagementsystem.util;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

import com.amazonaws.services.lambda.runtime.Context;

public class SnsPublisher {

    private final SnsClient snsClient;

    public SnsPublisher() {
        this.snsClient = SnsClient.create();
    }

    public void publishTaskAssignment(String topicArn, String message, String userId, Context context) {
        try {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            messageAttributes.put("userId", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(userId)
                    .build());

            PublishRequest request = PublishRequest.builder()
                    .message(message)
                    .topicArn(topicArn)
                    .messageAttributes(messageAttributes)
                    .build();

            PublishResponse response = snsClient.publish(request);
            context.getLogger().log("SNS message published: " + response.messageId());

        } catch (SnsException e) {
            context.getLogger().log("SNS publish failed: " + e.awsErrorDetails().errorMessage());
        }
    }
}
