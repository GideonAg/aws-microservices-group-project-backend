package com.taskmanagementsystem.notifications;

import java.util.List;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.services.TaskService;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.UserUtils;

import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

public class ProcessExpiredTaskHandler implements RequestHandler<Object, Void> {

    private final TaskService taskService = new TaskService();
    private final String stepFunctionArn = System.getenv("EXPIRED_TASK_STATE_MACHINE_ARN");
    private final AmazonDynamoDB dynamoDB = DynamoDBUtil.getDynamoDBClient();
    private final String userTable = System.getenv("USER_TABLE");


    @Override
    public Void handleRequest(Object input, Context context) {
        context.getLogger().log("Checking for expired tasks...");
        try {
            // Fetch all tasks that are not completed or expired (add your filtering logic here)
            List<Tasks> tasks = taskService.getIncompleteTasks();
            long now = System.currentTimeMillis();
            context.getLogger().log("Time on the system" + now);
            for (Tasks task : tasks) {
                Long deadline = task.getDeadline();
                
                if (deadline != null && now > deadline && !"expired".equalsIgnoreCase(task.getStatus()) && !"complete".equalsIgnoreCase(task.getStatus())) {
                    //get the user id and pass it to the step function
                    String userId = UserUtils.getUserIdByEmail(dynamoDB, userTable, task.getAssignedUserEmail(), context);
                    // Trigger Step Function with the taskId to handle expiration logic
                    triggerStepFunction(task.getTaskId(), userId);
                    context.getLogger().log("Task expired: " + task.getTaskId());
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error during task expiration check: " + e.getMessage());
        }

        return null;
    }

    private void triggerStepFunction(String taskId, String userId) {
        try (SfnClient sfnClient = SfnClient.create()) {
            String inputJson = String.format("{\"taskId\":\"%s\", \"userId\":\"%s\"}", taskId, userId);
    
            StartExecutionRequest request = StartExecutionRequest.builder()
                    .stateMachineArn(stepFunctionArn)
                    .input(inputJson)
                    .build();
    
            sfnClient.startExecution(request);
        }
    }
    
}
