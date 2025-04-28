package com.taskmanagementsystem.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.util.HeadersUtil;

public class AuthenticationHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthenticationService authenticationService = new AuthenticationService();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(HeadersUtil.getHeaders());

        try {

            LoginRequest loginRequest = mapper.readValue(request.getBody(), LoginRequest.class);
            var email = loginRequest.email();
            var password = loginRequest.password();
            if (email == null || email.trim().isEmpty()) {
                return response
                        .withStatusCode(400)
                        .withBody("{\"message\": \"Email is required\"}");
            }

            if (!isValidEmail(email)) {
                return response
                        .withStatusCode(400)
                        .withBody("{\"message\": \"Invalid email format\"}");
            }

            if (password == null || password.trim().isEmpty()) {
                return response
                        .withStatusCode(400)
                        .withBody("{\"message\": \"Password is required\"}");
            }

            var authResponse = authenticationService.login(email, password);
            context.getLogger().log("Headers" + response.getHeaders());
            return response
                    .withStatusCode(200)
                    .withBody(mapper.writeValueAsString(authResponse));

        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody(e.getMessage());
            return response;
        }
    }

    private boolean isValidEmail(String email) {
        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
        return email.matches(emailRegex);
    }
}
