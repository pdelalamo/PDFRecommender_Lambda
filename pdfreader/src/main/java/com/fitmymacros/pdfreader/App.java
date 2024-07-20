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

/**
 * Hello world!
 *
 */
public class App implements RequestHandler<Map<String, Object>, Object> {

    private static String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private static String OPENAI_MODEL_NAME = "OpenAI-Model-PDF";
    private static String OPENAI_MODEL_TEMPERATURE = "OpenAI-Model-Temperature";
    private static String OPENAI_MAX_TOKENS = "OpenAI-Max-Tokens";
    private SsmClient ssmClient;
    private String OPENAI_AI_KEY;
    private String OPENAI_MODEL;
    private Double MODEL_TEMPERATURE;
    private Integer MODEL_MAX_TOKENS;
    private String URL = "https://api.openai.com/v1/chat/completions";
    private ObjectMapper objectMapper;
    private WebClient webClient;

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        try {
            Map<String, String> queryParams = this.extractQueryString(input);
            System.out.println("input: " + input);
            String prompt = generatePrompt(queryParams);
            System.out.println("prompt: " + prompt);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", this.OPENAI_MODEL);
            requestBody.put("messages", Arrays.asList(
                    Map.of("role", "system",
                            "content", this.generateSystemInstructions()),
                    Map.of("role", "user",
                            "content", prompt)));
            requestBody.put("max_tokens", this.MODEL_MAX_TOKENS);
            requestBody.put("temperature", MODEL_TEMPERATURE);

            Mono<ChatCompletionResponse> completionResponseMono = webClient.post()
                    .uri(URL)
                    .headers(httpHeaders -> {
                        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                        httpHeaders.setBearerAuth(OPENAI_AI_KEY);
                    })
                    .bodyValue(objectMapper.writeValueAsString(requestBody))
                    .exchangeToMono(clientResponse -> {
                        HttpStatusCode httpStatus = clientResponse.statusCode();
                        if (httpStatus.is2xxSuccessful()) {
                            return clientResponse.bodyToMono(ChatCompletionResponse.class);
                        } else {
                            Mono<String> stringMono = clientResponse.bodyToMono(String.class);
                            stringMono.subscribe(s -> {
                                System.out.println("Response from Open AI API " + s);
                            });
                            System.out.println("Error occurred while invoking Open AI API");
                            return Mono.error(new Exception(
                                    "Error occurred while generating wordage"));
                        }
                    });
            ChatCompletionResponse completionResponse = completionResponseMono.block();
            List<ChatCompletionResponseChoice> choices = completionResponse.getChoices();
            ChatCompletionResponseChoice aChoice = choices.get(0);
            return buildSuccessResponse(this.parseJsonArray(aChoice.getMessage().getContent()));
        } catch (Exception e) {
            return this.buildErrorResponse(e.getMessage());
        }
    }

    /**
     * This method extracts the query params from the received event
     * 
     * @param input
     * @return
     */
    private Map<String, String> extractQueryString(Map<String, Object> input) {
        Map<String, Object> queryStringMap = (Map<String, Object>) input.get("queryStringParameters");
        if (queryStringMap != null) {
            String queryString = (String) queryStringMap.get("querystring");
            if (queryString != null) {
                return parseQueryString(queryString);
            } else {
                System.out.println("No query string parameters found.");
            }
        } else {
            System.out.println("No queryStringParameters found.");
        }
        return null;
    }

    /**
     * This method converts a String into a Map
     * 
     * @param queryString
     * @return
     */
    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> queryMap = new HashMap<>();

        // Remove leading and trailing braces if present
        if (queryString.startsWith("{") && queryString.endsWith("}")) {
            queryString = queryString.substring(1, queryString.length() - 1);
        }

        // Split the string by comma and space
        String[] pairs = queryString.split(", ");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2) {
                String key = keyValue[0];
                String value = keyValue[1];

                // Handle boolean values
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    queryMap.put(key, value);
                } else {
                    // For non-boolean values, put the key-value pair in the map
                    queryMap.put(key, value);
                }
            } else if (keyValue.length == 1) {
                // If there's no '=', treat the whole string as a key with a value of "true"
                queryMap.put(keyValue[0], "true");
            }
        }

        return queryMap;
    }

    /**
     * This method retrieves the clear text value for the openai key from the
     * parameter store
     * 
     * @return
     */
    private String getOpenAIKeyFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_API_KEY_NAME)
                    .withDecryption(true)
                    .build();
            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method retrieves the clear text value for the openai model from the
     * parameter store
     * 
     * @return
     */
    private String getOpenAIModelFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_MODEL_NAME)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method retrieves the clear value for the openai temperature to use from
     * the
     * parameter store
     * 
     * @return
     */
    private Double getTemperatureFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_MODEL_TEMPERATURE)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return Double.valueOf(parameterResponse.parameter().value());

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method retrieves the clear value for the openai max tokens to use from
     * the
     * parameter store
     * 
     * @return
     */
    private Integer getMaxTokensFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_MAX_TOKENS)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return Integer.valueOf(parameterResponse.parameter().value());

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method generates the prompt that will be sent to the openai api.
     * 
     * @return
     */
    private String generatePrompt(Map<String, String> input) {
        try {
            String pdfBase64 = input.get("pdf").toString();
            String pdf = this.decodePdf(pdfBase64);
            String mealTime = input.get("mealTime").toString();
            int protein = Integer.parseInt(input.get("protein").toString());
            int carbs = Integer.parseInt(input.get("carbs").toString());
            int fat = Integer.parseInt(input.get("fat").toString());
            int targetEnergy = Integer.parseInt(input.get("targetEnergy").toString());
            String energyUnit = input.get("energyUnit").toString();
            String weightUnit = input.get("weightUnit").toString();

            return this.createPrompt(pdf, mealTime, protein, carbs, fat, targetEnergy,
                    energyUnit, weightUnit);
        } catch (Exception e) {
            System.out.println("Error while deserializing input params: " + e.getMessage());
            return null;
        }
    }

    private String decodePdf(String base64EncodedPdf) throws IOException {
        byte[] decodedPdf = Base64.getDecoder().decode(base64EncodedPdf);
        PDDocument document = PDDocument.load(new ByteArrayInputStream(decodedPdf));
        PDFTextStripper pdfStripper = new PDFTextStripper();
        String text = pdfStripper.getText(document);
        document.close();
        return text;
    }

    /**
     * This method creates the prompt that will be sent to openAI
     * 
     * @param restaurantName
     * @param cuisineType
     * @param mealTime
     * @param protein
     * @param carbs
     * @param fat
     * @param targetEnergy
     * @param energyUnit
     * @param weightUnit
     * @return
     */
    private String createPrompt(String pdf, String mealTime, int protein, int carbs,
            int fat, int targetEnergy,
            String energyUnit, String weightUnit) {

        StringBuilder promptBuilder = new StringBuilder();

        // Start with a general introduction
        promptBuilder.append(
                "I'm looking for the best food options to choose from at a restaurant to meet my nutritional goals. Here are my specific requirements:\n");

        // Include the meal time
        promptBuilder.append(String.format("Meal Time: %s,", mealTime));

        // Include the target calories with unit
        promptBuilder.append(String.format("Target Energy: %d %s,", targetEnergy, energyUnit));

        // Include the macronutrient goals
        promptBuilder.append(String.format("Target Protein: %d %s,", protein, weightUnit));
        promptBuilder.append(String.format("Target Carbs: %d %s,", carbs, weightUnit));
        promptBuilder.append(String.format("Target Fat: %d %s,", fat, weightUnit));

        promptBuilder.append(String.format("This is the restaurant menu %s.", pdf));

        // Additional context to guide the AI
        promptBuilder.append(
                "Please provide a list of the 5 best options available at this type of restaurant that match these nutritional targets as closely as possible.");

        return promptBuilder.toString();

    }

    /**
     * This method creates the instructions that define the format that the model
     * must use for returning the response
     * 
     * @return
     */
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

    /**
     * This method removes any leading or trailing characters that could be
     * generated before or after the JsonArray
     * 
     * @param openAIResult
     * @return
     */
    private String parseJsonArray(String openAIResult) {
        int startIndex = openAIResult.indexOf('[');
        int endIndex = openAIResult.lastIndexOf(']');

        if (startIndex != -1 && endIndex != -1) {
            return openAIResult.substring(startIndex, endIndex + 1);
        } else {
            throw new RuntimeException("Invalid JSON string format generated by OpenAI");
        }
    }

    private Map<String, Object> buildSuccessResponse(String response) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("statusCode", 200);
        responseBody.put("body", response);
        return responseBody;
    }

    private String buildErrorResponse(String errorMessage) {
        return "Error occurred: " + errorMessage;
    }
}
