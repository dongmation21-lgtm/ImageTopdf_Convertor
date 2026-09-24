# Image to PDF Converter

Native Android project designed for large manga/manhwa image batches.

## Important implementation notes

- Uses Android Storage Access Framework (`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`).
- Keeps selected images as URI references rather than retaining full-resolution bitmaps.
- Generates PDF pages incrementally with Android `PdfDocument`.
- Each page derives its dimensions independently from the decoded, EXIF-normalized image.
- Uses one proportional scale for both dimensions.
- Draws at (0,0) to the complete page rectangle; no intentional margins, padding, borders, or cropping.
- Processes one full-resolution bitmap at a time, with bounded background work.
- Thumbnails are loaded lazily using sampled decoding.
- Supports JPEG, PNG and WebP through Android's image decoder.
- Progress is based on completed pages.
- Cancellation closes the document and removes the incomplete output.
- Output is written through a temporary file and renamed/finalized only after successful completion.

## Build

Open the project in Android Studio, sync Gradle, then Build > Build APK(s).

## Honest verification status

This project has not been physically installed/tested on a 500/1000-image Android device in this generation environment. The required test matrix is provided below.

## Test matrix

1. 1000x1000 square
2. 1000x2000 tall
3. 2000x1000 wide
4. 1200x3500 very tall
5. mixed dimensions
6. JPEG/PNG/WebP
7. EXIF orientations 1-8
8. 100 images
9. 500 images
10. 1000 images

For each, verify page count, ordering, no clipping, no distortion, edge-to-edge placement, and successful finalization.

## PDF page-size limitation

PDF page dimensions are represented in points and PDF viewers/libraries can impose practical limits. This implementation detects dimensions above the Android PdfDocument-supported page-size range and reports the affected image instead of silently cropping it.
