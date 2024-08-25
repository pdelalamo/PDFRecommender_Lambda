This AWS Lambda function processes restaurant menu PDFs and generates a list of food options that match the user's nutritional goals using OpenAI's API. The function extracts text from a PDF, generates a prompt, and retrieves options that align with the user's macronutrient requirements.

Usage
The Lambda function is triggered by API Gateway or another AWS service. It expects a JSON payload with a Base64-encoded PDF and nutritional goals.

Request Example

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
Response Example

{
  "statusCode": 200,
  "body": [
    {
      "optionName": "Grilled Chicken Salad",
      "energyAndMacros": {
        "energy": "500 kcal",
        "protein": "50 g",
        "carbs": "40 g",
        "fat": "20 g"
      }
    },
    {
      "optionName": "Turkey Sandwich",
      "energyAndMacros": {
        "energy": "600 kcal",
        "protein": "45 g",
        "carbs": "70 g",
        "fat": "15 g"
      }
    }
  ]}
