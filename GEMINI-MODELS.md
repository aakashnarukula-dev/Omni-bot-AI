# Gemini model review — 9 September 2026

Official Google documentation was checked directly. These are Gemini Developer API model IDs, not names of consumer Gemini subscription plans. Public documentation does not prove that a particular API project has generation quota or access.

## Recommended for Omni

Keep `gemini-3.8-flash` as the current default while evaluating classification quality. It is stable, accepts text, images, video, audio and PDFs, and supports structured output and function calling. Its context limits are 1,048,576 input and 65,536 output tokens. It produces text, not card images or speech. Thinking levels are `low`, `medium` and `high`; `minimal` fails for this model. Omni uses `low`. [Model specification](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)

Evaluate `gemini-3.5-flash-lite` as the speed/cost option. Google describes it as optimized for low latency, document parsing and simpler extraction. It accepts the same input modalities, supports structured output, and has the same context limits. Actual speed and accuracy for this app remain unmeasured until a key is configured. [Flash-Lite specification](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)

`gemini-3.1-pro-preview` is an optional candidate for harder reasoning. It supports multimodal input and structured output but remains preview; there is also a `-customtools` variant. It is unnecessary for routine cable/reminder categorization without evidence from a comparison. [Pro specification](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-pro-preview)

This recommendation is an engineering judgment, not a measured ranking. No automatic cross-model fallback is enabled: failures preserve the original and use local classification.

## Catalog snapshot

The official catalog lists these general models:

| Status | Model IDs |
| --- | --- |
| Stable Gemini 3 Flash | `gemini-3.8-flash`, `gemini-3.7-flash`, `gemini-3.6-flash`, `gemini-3.5-flash` |
| Stable Gemini 3 Flash-Lite | `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite` |
| Preview general models | `gemini-3.1-pro-preview`, `gemini-3-flash-preview` |
| Older general models | `gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-2.5-pro` |

Specialized Gemini families include:

| Purpose | Model IDs |
| --- | --- |
| Image generation/editing | `gemini-3.1-flash-image`, `gemini-3.1-flash-lite-image`, `gemini-3-pro-image`, `gemini-2.5-flash-image` |
| Speech transcription | `gemini-3.5-transcribe`, `gemini-3.5-transcribe-live` |
| Live dialogue | `gemini-3.1-flash-live-preview`, `gemini-2.5-flash-native-audio-preview-12-2025` |
| Speech generation | `gemini-3.1-flash-tts-preview`, `gemini-2.5-flash-preview-tts`, `gemini-2.5-pro-preview-tts` |
| Live translation | `gemini-3.5-live-translate-preview` |
| Video generation | `gemini-omni-1.1-flash` |
| Specialized automation | `gemini-2.5-computer-use-preview-10-2025`, `gemini-robotics-er-2-preview`, `gemini-robotics-er-1.6-preview` |

The same API catalog also includes non-Gemini-named Veo, Imagen, Lyria, Deep Research and Antigravity offerings. They are separate capabilities, not interchangeable classifiers. [Complete official catalog](https://ai.google.dev/gemini-api/docs/models)

For later semantic retrieval, `gemini-embedding-2` accepts text, images, video, audio and PDFs and produces embeddings. Its specific model page marks it stable. The catalog still displays `gemini-embedding-2-preview`; this is a documentation inconsistency. Prefer the specific model page and authenticated `models.list` results before enabling it. Omni currently uses local word matching; no embedding upload occurs. [Embedding 2 specification](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-2)

The older text embedding ID `gemini-embedding-001` remains listed, with shutdown scheduled for May 14, 2028. [Embedding retirement table](https://ai.google.dev/gemini-api/docs/deprecations)

Gemini API also hosts the separate Gemma family: `gemma-4-31b-it` and `gemma-4-26b-a4b-it`. Both support image input and system instructions. Their thinking control is on/off through `high`/`minimal`, unlike Gemini 3.8. They are not included in Omni's current Flash/Pro picker and have not been validated with this classifier. [Gemma on Gemini API](https://ai.google.dev/gemma/docs/core/gemma_on_gemini_api)

## Pricing and limits

Standard paid pricing, USD per million tokens:

| Model | Input | Output, including thinking |
| --- | --- | --- |
| 3.8 Flash | $0.75 through 2026-12-31; $1.50 from 2027-01-01 | $3.75 through 2026-12-31; $7.50 from 2027-01-01 |
| 3.5 Flash-Lite | $0.30 | $2.50 |
| 3.1 Flash-Lite | $0.25 text/image/video; $0.50 audio | $1.50 |
| 3.1 Pro Preview | $2 up to 200k input tokens; $4 above | $12 up to 200k; $18 above |

Free quotas exist for the listed Flash options; Pro Preview has no free tier. Grounding, caching and other features have separate charges. [Official pricing](https://ai.google.dev/gemini-api/docs/pricing)

Rate limits depend on model and project tier. RPM, input TPM and RPD apply per project, not per key; daily limits reset at midnight Pacific. AI Studio shows actual active limits. A listed model can still fail with quota errors. [Rate-limit documentation](https://ai.google.dev/gemini-api/docs/rate-limits)

Avoid retired IDs: Gemini 2.0 Flash/Flash-Lite shut down June 1, 2026; Gemini 3 Pro Preview shut down March 9; 3.1 Flash-Lite Preview shut down May 25. Gemini 2.5 Flash Image is scheduled to shut down October 2, 2026. Current 2.5 general models have no announced shutdown date on the checked page. Some replacement suggestions there reference already-retired image previews, another reason to cross-check specific pages and live access. [Deprecations](https://ai.google.dev/gemini-api/docs/deprecations)

## API details verified against code

- Existing `generateContent` integration remains supported. Google now recommends the generally available Interactions API for new projects; new capabilities will arrive there. Migration was not mixed into this verification update. If migrated, use `store=false` for this local-vault design: Interactions stores requests by default. That flag does not cancel Google's separate abuse-monitoring terms. [Interactions documentation](https://ai.google.dev/gemini-api/docs/interactions)
- Raw Generate Content REST structured output uses `generationConfig.responseFormat.text` with `mimeType: "APPLICATION_JSON"` and a JSON Schema. The guide currently shows the SDK-style MIME string `application/json`, but the actual REST discovery schema defines an enum. Sending the guide's literal value to Google reproducibly returns HTTP 400 before API-key authentication. Omni 0.1.5 uses the enum and `thinkingLevel: "LOW"`. [REST discovery schema](https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta) JSON structure does not guarantee correct classification; app checks results and keeps ambiguous images reviewable. [Structured-output guide](https://ai.google.dev/gemini-api/docs/generate-content/structured-output)
- Gemini 3 uses thinking levels. Gemini 2.5 Flash uses `thinkingBudget: 0` for no thinking; that configuration must not be copied to 2.5 Pro. [Thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking)
- Omni sends bounded JPEG input plus text/OCR using inline image parts. Model vision can interpret screenshots; ambiguous design/product intent still requires the owner's answer. [Image-understanding guide](https://ai.google.dev/gemini-api/docs/generate-content/image-understanding)
- Gemini can process PDFs up to 50 MB or 1,000 pages. This is the provider limit, not this app's current capability: Omni stores originals and reads the first five PDF pages locally. APK bytes are stored and categorized without execution. [Document guide](https://ai.google.dev/gemini-api/docs/generate-content/document-processing)
- Settings now calls paginated `GET /v1beta/models`, filters general Flash/Pro models advertising `generateContent`, and offers a separate sample-classification check. The list alone does not certify all capabilities. Keys stay in the `x-goog-api-key` header. The probe sends a synthetic cable message, not saved content. [Models API reference](https://ai.google.dev/api/models)

## Private data

Unpaid Gemini services may use inputs and outputs for product improvement and human review. Google's terms say not to submit sensitive, confidential or personal information to unpaid services. Billing-enabled projects receive paid-service handling: prompts and responses are not used to improve products, but limited abuse-monitoring logging still applies. Local display of full card details is separate from uploading them to a provider. Omni keeps recognized card/bank/medical content local; detection remains heuristic. Dedicated Card import always bypasses cloud classification. [Gemini API terms](https://ai.google.dev/gemini-api/terms)

Card PNGs are rendered locally from verified stored fields. A generative image model could alter digits and is unsuitable for exact card reproduction. Reminders are scheduled by Android, not left to an LLM to deliver later.

## Live verification and remaining comparisons

On version 0.1.5, the installed personal app's Check connection result verified Gemini 3.8 Flash with the existing saved key: the synthetic USB cable sample was classified correctly in 1.8 seconds. No saved items were sent, and development tools did not read or export the key. The HTTP 400 was caused by the raw REST MIME enum mismatch described above.

One text sample is not a latency benchmark or a broad accuracy test. Multimodal classification quality, quota boundaries and a same-input speed/quality comparison with Gemini 3.5 Flash-Lite remain to be measured. Automated tests use synthetic credentials and controlled responses for those API handling cases.
