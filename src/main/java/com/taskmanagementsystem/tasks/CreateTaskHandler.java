package com.taskmanagementsystem.tasks;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.util.DynamoDBUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;

public class CreateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger logger = LoggerFactory.getLogger(CreateTaskHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final DynamoDBMapper dynamoDBMapper;
    private final SqsClient sqsClient;
    private final String tasksQueueUrl;
    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;

    public CreateTaskHandler() {
        AmazonDynamoDB dynamoDBClient = DynamoDBUtil.getDynamoDBClient();

        // Get table name from environment variable
        String tableName = System.getenv("TASK_TABLE");
        if (tableName != null && !tableName.isEmpty()) {

            // Create a config that overrides the table name
            DynamoDBMapperConfig mapperConfig = DynamoDBMapperConfig.builder()
                    .withTableNameOverride(DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(tableName))
                    .build();
            this.dynamoDBMapper = new DynamoDBMapper(dynamoDBClient, mapperConfig);
        } else {
            // Fallback to default table name in the class annotation
            this.dynamoDBMapper = new DynamoDBMapper(dynamoDBClient);
        }

        // Create a shared HTTP client
        UrlConnectionHttpClient httpClient = (UrlConnectionHttpClient) UrlConnectionHttpClient.builder().build();

        // Set region from environment variables
        String regionStr = System.getenv("REGION");
        Region region = regionStr != null ? Region.of(regionStr) : Region.EU_CENTRAL_1; // Default to EU Central 1

        this.sqsClient = SqsClient.builder()
                .httpClient(httpClient)
                .region(region)
                .build();

        this.tasksQueueUrl = System.getenv("TASKS_QUEUE_URL");
        this.userPoolId = System.getenv("USER_POOL_ID");

        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .httpClient(httpClient)
                .region(region)
                .build();
    }

    // Constructor for testing
    public CreateTaskHandler(DynamoDBMapper dynamoDBMapper,
                             SqsClient sqsClient,
                             String tasksQueueUrl,
                             CognitoIdentityProviderClient cognitoClient,
                             String userPoolId) {
        this.dynamoDBMapper = dynamoDBMapper;
        this.sqsClient = sqsClient;
        this.tasksQueueUrl = tasksQueueUrl;
        this.cognitoClient = cognitoClient;
        this.userPoolId = userPoolId;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        logger.info("Received request to create task");
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(createCorsHeaders());

        try {
            // Extract user information from the claims
            Map<String, String> claims = getClaims(input);
            logger.info("Claims received: {}", claims);

            // Check if the user is authorized (must be logged in)
            if (claims.isEmpty()) {
                logger.error("No authorization claims found in request");
                return createErrorResponse(response, 401, "Unauthorized: Authentication required");
            }

            // Extract user email - primary way
            String createdBy = claims.get("email");
            // Fallback to cognito:username if email is not available
            if (createdBy == null || createdBy.isEmpty()) {
                createdBy = claims.get("cognito:username");
            }

            // Get user role
            String userRole = claims.get("custom:role");

            logger.info("User email: {}, role: {}", createdBy, userRole);

            // Verify user identity was found
            if (createdBy == null || createdBy.isEmpty()) {
                logger.error("User identity not found in token claims");
                return createErrorResponse(response, 403, "Unauthorized: User information not found");
            }

            // Check if user is admin - strict check requiring explicit admin role
            if (userRole == null || !userRole.equalsIgnoreCase("admin")) {
                logger.error("Unauthorized access attempt by non-admin user: {}", createdBy);
                return createErrorResponse(response, 403, "Unauthorized: Only admin users can create tasks");
            }

            // Parse the task from the request body
            TaskRequest taskRequest = objectMapper.readValue(input.getBody(), TaskRequest.class);
            logger.info("Successfully parsed task request: {}", taskRequest.getName());

            // Validate task data
            if (!isValidTask(taskRequest)) {
                logger.error("Invalid task data provided: missing name or responsibility");
                return createErrorResponse(response, 400, "Invalid task data. Name and responsibility are required.");
            }

            // Verify assigned user exists
            if (taskRequest.getAssignedUserEmail() != null && !taskRequest.getAssignedUserEmail().isEmpty()) {
                logger.info("Verifying assigned user exists: {}", taskRequest.getAssignedUserEmail());
                if (!userExistsInCognito(taskRequest.getAssignedUserEmail())) {
                    logger.error("Assigned user does not exist in Cognito: {}", taskRequest.getAssignedUserEmail());
                    return createErrorResponse(response, 400, "The assigned user does not exist Stop this!!");
                }
            }

            // Create and save task
            Tasks task = createTaskFromRequest(taskRequest, createdBy);
            dynamoDBMapper.save(task);
            logger.info("Task saved successfully to the table with ID: {}", task.getTaskId());

            // Send task to SQS for notification processing
            sendTaskToQueue(task, context);

            // Return success response with the created task
            return createSuccessResponse(response, task);

        } catch (JsonProcessingException e) {
            logger.error("Error parsing request body", e);
            return createErrorResponse(response, 400, "Invalid request format: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing task creation", e);
            return createErrorResponse(response, 500, "Error creating task: " + e.getMessage());
        }
    }

    private boolean userExistsInCognito(String email){
        try{
            logger.info("Checking if user exists in Cognito: {}", email);
            ListUsersRequest request = ListUsersRequest.builder()
                    .userPoolId(userPoolId)
                    .filter("email = \"" + email + "\"")
                    .limit(1)  // We only need to know if at least one exists
                    .build();

            logger.info("User pool ID: {}", userPoolId);
            ListUsersResponse response = cognitoClient.listUsers(request);
            boolean exists = !response.users().isEmpty();
            logger.info("User exists: {}", exists);
            return exists;
        } catch (Exception e){
            logger.error("Error checking user in Cognito", e);
            // In case of error, log it but assume user doesn't exist for safety
            return false;
        }
    }

    private Tasks createTaskFromRequest(TaskRequest request, String createdBy) {
        Tasks task = new Tasks();
        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setStatus("OPEN"); // Default status for new tasks
        task.setDeadline(request.getDeadline());
        task.setResponsibility(request.getResponsibility());
        task.setAssignedUserEmail(request.getAssignedUserEmail());
        task.setCreatedBy(createdBy);
        task.setCreatedAt(System.currentTimeMillis());
        task.setUpdatedAt(System.currentTimeMillis());
        return task;
    }

    // Update the method signature to accept the context
    private void sendTaskToQueue(Tasks task, Context context) {
        try {
            // Log request ID from context for traceability
            String requestId = context.getAwsRequestId();
            logger.info("[RequestID: {}] Attempting to send task to queue URL: {}", requestId, tasksQueueUrl);

            if (tasksQueueUrl == null || tasksQueueUrl.isEmpty()) {
                logger.error("[RequestID: {}] Queue URL is null or empty - check TASKS_QUEUE_URL environment variable", requestId);
                return;
            }

            String messageBody = objectMapper.writeValueAsString(task);
            logger.info("[RequestID: {}] Task serialized to JSON successfully", requestId);

            String messageGroupId = task.getResponsibility().replace("\\s+", "_"); // Group by responsibility
            String messageDeduplicationId = task.getTaskId(); // Use taskId for deduplication

            logger.info("[RequestID: {}] Preparing to send message with groupId: {}, deduplicationId: {}",
                    requestId, messageGroupId, messageDeduplicationId);

            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(tasksQueueUrl)
                    .messageBody(messageBody)
                    .messageGroupId(messageGroupId)
                    .messageDeduplicationId(messageDeduplicationId)
                    .build();

            // Log that we're about to send the message
            logger.info("[RequestID: {}] Sending message to SQS with {} ms remaining",
                    requestId, context.getRemainingTimeInMillis());

            // Send the message and capture the response
            var sendMessageResponse = sqsClient.sendMessage(sendMessageRequest);

            // Log successful send with message ID
            logger.info("[RequestID: {}] Task sent to queue successfully. Message ID: {}",
                    requestId, sendMessageResponse.messageId());

        } catch (Exception e) {
            // More detailed error logging
            String requestId = context.getAwsRequestId();
            logger.error("[RequestID: {}] Failed to send task to queue. Error: {}, Message: {}",
                    requestId, e.getClass().getName(), e.getMessage(), e);

            // If it's an AWS service exception, log additional details
            if (e instanceof software.amazon.awssdk.services.sqs.model.SqsException) {
                software.amazon.awssdk.services.sqs.model.SqsException sqsEx =
                        (software.amazon.awssdk.services.sqs.model.SqsException) e;
                logger.error("[RequestID: {}] SQS Error details - Status code: {}, Request ID: {}",
                        requestId, sqsEx.statusCode(), sqsEx.requestId());
            }
        }
    }

    private Map<String, String> getClaims(APIGatewayProxyRequestEvent input) {
        Map<String, String> claims = new HashMap<>();
        try {
            if (input.getRequestContext() != null &&
                    input.getRequestContext().getAuthorizer() != null &&
                    input.getRequestContext().getAuthorizer().get("claims") != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> authClaims = (Map<String, String>) input.getRequestContext().getAuthorizer().get("claims");
                claims.putAll(authClaims);
                logger.info("Found {} claims in request", claims.size());
            } else {
                logger.warn("No claims found in request context");
            }
        } catch (Exception e) {
            logger.error("Error extracting claims from request", e);
        }
        return claims;
    }

    private boolean isValidTask(TaskRequest task) {
        return task.getName() != null && !task.getName().isEmpty() &&
                task.getResponsibility() != null && !task.getResponsibility().isEmpty();
    }

    private APIGatewayProxyResponseEvent createSuccessResponse(APIGatewayProxyResponseEvent response, Tasks task) {
        try {
            response.setStatusCode(201);
            response.setBody(objectMapper.writeValueAsString(task));
            return response;
        } catch (JsonProcessingException e) {
            logger.error("Error serializing task", e);
            return createErrorResponse(response, 500, "Error serializing response");
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(APIGatewayProxyResponseEvent response, int statusCode, String message) {
        response.setStatusCode(statusCode);
        Map<String, String> errorResponse = new HashMap<>();
        errorResponse.put("message", message);
        try {
            response.setBody(objectMapper.writeValueAsString(errorResponse));
        } catch (JsonProcessingException e) {
            response.setBody("{\"message\": \"Error generating error response\"}");
        }
        return response;
    }

    private Map<String, String> createCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Amz-Date,X-Api-Key");
        headers.put("Access-Control-Allow-Methods", "OPTIONS,POST");
        headers.put("Content-Type", "application/json");
        return headers;
    }
}