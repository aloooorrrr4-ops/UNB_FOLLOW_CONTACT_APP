# UNB Image Editor

Android MVP for goal 1: detect and remove image backgrounds.

## Current features
- Pick an image from Android.
- Configure the backend server URL.
- Send the image to `POST /api/background/cutout`.
- Preview the transparent PNG result.
- Save the PNG to the phone gallery.

## Backend contract
The app expects:
- Method: POST
- Path: `/api/background/cutout`
- Body: multipart/form-data
- Field: `file`
- Response: PNG bytes

The Android app does not bundle the AI model. The background-removal model runs on the backend server.

## APK
GitHub Actions builds a debug APK automatically on every push to `main`.
