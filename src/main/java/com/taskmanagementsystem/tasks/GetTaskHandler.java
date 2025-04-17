package com.taskmanagementsystem.tasks;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.services.TaskService;

import java.util.HashMap;
import java.util.Map;

public class GetTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public GetTaskHandler() {
        this.taskService = new TaskService();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(headers);

        try {
            // Extract taskId from the path parameters
            String taskId = event.getPathParameters().get("taskId");
            if (taskId == null || taskId.isEmpty()) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"taskId is required\"}");
                return response;
            }

            // Extract user claims from the authorizer context
            Map<String, Object> authorizer = event.getRequestContext().getAuthorizer();
            if (authorizer == null || !authorizer.containsKey("claims")) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"Authorizer claims not found\"}");
                return response;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            String userEmail = claims.get("email");
            String userRole = claims.get("custom:role");

            if (userEmail == null) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"User email not found in claims\"}");
                return response;
            }

            // Retrieve the task from DynamoDB
            Tasks task = taskService.getTask(taskId);
            if (task == null) {
                response.setStatusCode(404);
                response.setBody("{\"message\": \"Task not found\"}");
                return response;
            }

            // Authorization check: user must be assigned to the task or be an admin
            boolean isAdmin = "admin".equals(userRole);
            boolean isAssignedUser = userEmail.equals(task.getAssignedUserEmail());

            if (!isAdmin && !isAssignedUser) {
                response.setStatusCode(403);
                response.setBody("{\"message\": \"Access denied: User is not assigned to this task and is not an admin\"}");
                return response;
            }

            // Serialize the task to JSON and return it
            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(task));
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Internal server error\"}");
        }

        return response;
    }
}