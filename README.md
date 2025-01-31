# Overview

This AWS Lambda function processes restaurant menu PDFs and generates a list of food options that match the user's nutritional goals using OpenAI's API. The function extracts text from a PDF, generates a prompt, and retrieves options that align with the user's macronutrient requirements.

## Usage

The Lambda function is triggered by API Gateway or another AWS service. It expects a JSON payload with a Base64-encoded PDF and nutritional goals.

## Deployment

### Create an AWS Lambda Function

1. Go to the AWS Management Console and create a new Lambda function.
2. Upload the JAR file created in the `target/` directory.

### Set up IAM Role

Ensure the Lambda function has an IAM role with the following permissions:

- `ssm:GetParameter`
- `ssm:GetParameters`

## Request Example

```json
{
  "pdf": "Base64EncodedPDFString",
  "mealTime": "Lunch",
  "protein": 50,
  "carbs": 100,
  "fat": 20,
  "targetEnergy": 600,
  "energyUnit": "kcal",
  "weightUnit": "g"
}

```

## Response Example

```json
{
  "pdf": "Base64EncodedPDFString",
  "mealTime": "Lunch",
  "protein": 50,
  "carbs": 100,
  "fat": 20,
  "targetEnergy": 600,
  "energyUnit": "kcal",
  "weightUnit": "g"
}

```
