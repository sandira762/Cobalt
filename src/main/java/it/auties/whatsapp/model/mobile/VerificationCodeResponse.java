package it.auties.whatsapp.model.mobile;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A model that represents a newsletters from Whatsapp regarding the registration of a phone number
 *
 * @param number              the number that was registered
 * @param lid                 the lid of the number that was registered
 * @param status              the status of the registration
 * @param errorReason         the error, if any was thrown
 * @param method              the method used to register, if any was used
 * @param codeLength          the expected length of the code, if a code request was sent
 * @param notifyAfter         the time in seconds after which the app would notify you to try again to register
 * @param retryAfter          the time in seconds after which the app would allow you to try again to register a sms
 * @param voiceLength         unknown
 * @param voiceWait           the time in seconds after which the app would allow you to try again to register using a call
 * @param smsWait             the time in seconds after which the app would allow you to try again to register using a sms
 * @param flashType           unknown
 * @param oldWait             the last wait time in seconds before trying again, if available
 * @param securityCodeSet     whether 2fa is enabled
 * @param whatsappOtpWait     the time in seconds after which the app would allow you to try again to register using Whatsapp
 * @param whatsappOtpEligible whether an otp over whatsapp can be sent
 * @param imageCaptcha        the image captcha to solve, only available for business accounts
 * @param audioCaptcha        the audio captcha to solve, only available for business accounts
 */
public record VerificationCodeResponse(@JsonProperty("login") PhoneNumber number, @JsonProperty("lid") long lid,
                                       @JsonProperty("status") VerificationCodeStatus status,
                                       @JsonProperty("reason") VerificationCodeError errorReason,
                                       @JsonProperty("method") VerificationCodeMethod method,
                                       @JsonProperty("length") int codeLength,
                                       @JsonProperty("notify_after") int notifyAfter,
                                       @JsonProperty("retry_after") long retryAfter,
                                       @JsonProperty("voice_length") long voiceLength,
                                       @JsonProperty("voice_wait") long voiceWait,
                                       @JsonProperty("sms_wait") long smsWait,
                                       @JsonProperty("email_otp_wait") long whatsappOtpWait,
                                       @JsonProperty("flash_type") long flashType,
                                       @JsonProperty("wa_old_wait") long oldWait,
                                       @JsonProperty("security_code_set") boolean securityCodeSet,
                                       @JsonProperty("email_otp_eligible") boolean whatsappOtpEligible,
                                       @JsonProperty("image_blob") String imageCaptcha,
                                       @JsonProperty("audio_blob") String audioCaptcha
) {

}