package com.taskmanagementsystem.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class AuthenticationHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthenticationService authenticationService = new AuthenticationService();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(Map.of("Content-Type", "application/json"));

        try {

            LoginRequest loginRequest = mapper.readValue(request.getBody(), LoginRequest.class);
            var email = loginRequest.email();
            var password = loginRequest.password();
            if (email == null || email.trim().isEmpty()) {
                return response
                        .withStatusCode(400)
                        .withBody("{\"message\": \"Email is required\"}");
            }

            if (password == null || password.trim().isEmpty()) {
                return response
                        .withStatusCode(400)
                        .withBody("{\"message\": \"Password is required\"}");
            }

            var authResponse = authenticationService.login(email, password);
            return response
                    .withStatusCode(200)
                    .withBody(mapper.writeValueAsString(authResponse));

        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            return response;
        }
    }
}
