package com.fitmymacros.pdfreader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatusCode;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmymacros.pdfreader.model.ChatCompletionResponse;
import com.fitmymacros.pdfreader.model.ChatCompletionResponseChoice;

import reactor.core.publisher.Mono;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class App implements RequestHandler<Map<String, Object>, Object> {

    private static final String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private static final String OPENAI_MODEL_NAME = "OpenAI-Model-PDF";
    private static final String OPENAI_MODEL_TEMPERATURE = "OpenAI-Model-Temperature";
    private static final String OPENAI_MAX_TOKENS = "OpenAI-Max-Tokens-pdf";
    private static final String URL = "https://api.openai.com/v1/chat/completions";
    private static final Region REGION = Region.EU_WEST_3;

    private final SsmClient ssmClient;
    private final String openAIKey;
    private final String openAIModel;
    private final Double modelTemperature;
    private final Integer modelMaxTokens;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public App() {
        this.ssmClient = SsmClient.builder().region(REGION).build();
        this.openAIKey = retrieveParameterFromSSM(OPENAI_API_KEY_NAME);
        this.openAIModel = retrieveParameterFromSSM(OPENAI_MODEL_NAME);
        this.modelTemperature = Double.valueOf(retrieveParameterFromSSM(OPENAI_MODEL_TEMPERATURE));
        this.modelMaxTokens = Integer.valueOf(retrieveParameterFromSSM(OPENAI_MAX_TOKENS));
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.create();
    }

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        try {
            Map<String, Object> body = convertBodyToMap(input.get("body").toString());
            String prompt = generatePrompt(body);

            Map<String, Object> requestBody = buildRequestBody(prompt);

            Mono<ChatCompletionResponse> completionResponseMono = webClient.post()
                    .uri(URL)
                    .headers(headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setBearerAuth(openAIKey);
                    })
                    .bodyValue(objectMapper.writeValueAsString(requestBody))
                    .exchangeToMono(response -> handleResponse(response));

            ChatCompletionResponse completionResponse = completionResponseMono.block();
            ChatCompletionResponseChoice firstChoice = completionResponse.getChoices().get(0);
            return buildSuccessResponse(parseJsonArray(firstChoice.getMessage().getContent()));
        } catch (Exception e) {
            return buildErrorResponse(e.getMessage());
        }
    }

    private Map<String, Object> convertBodyToMap(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (IOException e) {
            throw new RuntimeException("Error converting body to map", e);
        }
    }

    private String retrieveParameterFromSSM(String parameterName) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (SsmException e) {
            throw new RuntimeException("Error fetching parameter: " + parameterName, e);
        }
    }

    private String generatePrompt(Map<String, Object> input) {
        try {
            String pdfBase64 = input.get("pdf").toString();
            String pdf = decodePdf(pdfBase64);
            String mealTime = input.get("mealTime").toString();
            int protein = Integer.parseInt(input.get("protein").toString());
            int carbs = Integer.parseInt(input.get("carbs").toString());
            int fat = Integer.parseInt(input.get("fat").toString());
            int targetEnergy = Integer.parseInt(input.get("targetEnergy").toString());
            String energyUnit = input.get("energyUnit").toString();
            String weightUnit = input.get("weightUnit").toString();

            return createPrompt(pdf, mealTime, protein, carbs, fat, targetEnergy, energyUnit, weightUnit);
        } catch (Exception e) {
            throw new RuntimeException("Error generating prompt", e);
        }
    }

    private String decodePdf(String base64EncodedPdf) throws IOException {
        byte[] decodedPdf = Base64.getDecoder().decode(base64EncodedPdf);
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(decodedPdf))) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            return pdfStripper.getText(document);
        }
    }

    private String createPrompt(String pdf, String mealTime, int protein, int carbs, int fat, int targetEnergy, String energyUnit, String weightUnit) {
        return new StringBuilder()
                .append("I'm looking for the best food options to choose from at a restaurant to meet my nutritional goals. Here are my specific requirements:\n")
                .append(String.format("Meal Time: %s, ", mealTime))
                .append(String.format("Target Energy: %d %s, ", targetEnergy, energyUnit))
                .append(String.format("Target Protein: %d %s, ", protein, weightUnit))
                .append(String.format("Target Carbs: %d %s, ", carbs, weightUnit))
                .append(String.format("Target Fat: %d %s, ", fat, weightUnit))
                .append(String.format("This is the restaurant menu %s. ", pdf))
                .append("Please provide a list of the 5 best options available at this type of restaurant that match these nutritional targets as closely as possible.")
                .toString();
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", openAIModel);
        requestBody.put("messages", Arrays.asList(
                Map.of("role", "system", "content", generateSystemInstructions()),
                Map.of("role", "user", "content", prompt)));
        requestBody.put("max_tokens", modelMaxTokens);
        requestBody.put("temperature", modelTemperature);
        return requestBody;
    }

    private Mono<ChatCompletionResponse> handleResponse(org.springframework.web.reactive.function.client.ClientResponse clientResponse) {
        HttpStatusCode status = clientResponse.statusCode();
        if (status.is2xxSuccessful()) {
            return clientResponse.bodyToMono(ChatCompletionResponse.class);
        } else {
            return clientResponse.bodyToMono(String.class)
                    .doOnNext(responseBody -> System.out.println("Response from Open AI API: " + responseBody))
                    .then(Mono.error(new Exception("Error occurred while generating wordage")));
        }
    }

    private String generateSystemInstructions() {
        return "You are a helpful assistant, that generates a response that just contains a JSON array, that follows this structure for each option: {\n"
             + "  \"optionName\": \"\",\n"
             + "  \"energyAndMacros\": {\n"
             + "    \"energy\": \"\",\n"
             + "    \"protein\": \"\",\n"
             + "    \"carbs\": \"\",\n"
             + "    \"fat\": \"\"\n"
             + "  }"
             + "}";
    }

    private String parseJsonArray(String openAIResult) {
        int startIndex = openAIResult.indexOf('[');
        int endIndex = openAIResult.lastIndexOf(']');
        if (startIndex != -1 && endIndex != -1) {
            return openAIResult.substring(startIndex, endIndex + 1);
        }
        throw new RuntimeException("Invalid JSON string format generated by OpenAI");
    }

    private Map<String, Object> buildSuccessResponse(String response) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("statusCode", 200);
        responseBody.put("body", response);
        responseBody.put("isBase64Encoded", false);
        return responseBody;
    }

    private String buildErrorResponse(String errorMessage) {
        return "Error occurred: " + errorMessage;
    }
}
