# Prompts and Templates

## LLM prompts and system instructions

**None.** This codebase does not call any LLM or AI service. It has no prompt strings, system instructions or prompt template files, and no AI SDK dependency (see `build.gradle.kts`).

If you add LLM features later, record every prompt here, with its file location, its variables and the model it targets.

## Message templates

The only template in the code is the **WhatsApp Business message template** that carries login codes.

### `login_otp` (WhatsApp Cloud API template)

- **Defined in:** `src/main/kotlin/com/example/cricketscoring/backend/auth/WhatsAppOtpSender.kt` (`CloudWhatsAppOtpSender`)
- **Used when:** `WHATSAPP_OTP_MODE=cloud`
- **Template name:** env `WHATSAPP_OTP_TEMPLATE_NAME`, default `login_otp`
- **Endpoint:** `POST https://graph.facebook.com/v21.0/{WHATSAPP_PHONE_NUMBER_ID}/messages`, with `Authorization: Bearer {WHATSAPP_ACCESS_TOKEN}`
- **Language:** `en` (hardcoded)
- **Parameters:** one `body` text parameter, the 6-digit code

Request body the server sends:

```json
{
  "messaging_product": "whatsapp",
  "to": "919876543210",
  "type": "template",
  "template": {
    "name": "login_otp",
    "language": { "code": "en" },
    "components": [
      {
        "type": "body",
        "parameters": [ { "type": "text", "text": "123456" } ]
      }
    ]
  }
}
```

`to` is the E.164 number without the leading `+`.

The wording of the message lives in **Meta's WhatsApp Manager**, not in this repository. The template there must exist under the configured name and language, and it must have exactly one body variable (`{{1}}`) for the code. If you change the server payload (adding a button component for copy-code authentication templates, for example), update the approved template to match.

### Console "template" (development)

In `console` mode, no message is sent. The server logs this line instead:

```
WHATSAPP_OTP_MODE=console: OTP for {phone} is {code} (not sent via WhatsApp)
```

## User-facing error messages

The API's human-readable `message` strings are written inline at each `AuthException` throw site (`AuthService.kt`, `GoogleVerifier.kt`, `PhoneNumber.kt`, `JwtService.kt`, `WhatsAppOtpSender.kt`) and in `Application.kt` (`internal_error`, `not_found`). There is no message catalog or localization. See `API.md` for the codes.
