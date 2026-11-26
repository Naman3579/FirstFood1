package com.firstfood.identity;

import com.firstfood.identity.error.InvalidOtpException;
import com.firstfood.identity.error.OtpExpiredException;
import com.firstfood.identity.error.StepUpException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class StepUpServiceImpl implements StepUpService {

    private static final Pattern SIX_DIGITS = Pattern.compile("^[0-9]{6}$");

    private final OtpService otpService;
    private final AccountLookupService accountLookup;
<<<<<<< HEAD
    private final PhoneProperties phoneProperties;

    public StepUpServiceImpl(
            OtpService otpService, AccountLookupService accountLookup, PhoneProperties phoneProperties) {
        this.otpService = otpService;
        this.accountLookup = accountLookup;
        this.phoneProperties = phoneProperties;
=======

    public StepUpServiceImpl(OtpService otpService, AccountLookupService accountLookup) {
        this.otpService = otpService;
        this.accountLookup = accountLookup;
>>>>>>> origin/main
    }

    @Override
    public void requestCode(UUID accountId) {
<<<<<<< HEAD
        Channel channel = channelOf(accountId);
        if (channel.isEmail()) {
            otpService.requestEmailOtp(channel.destination(), OtpPurpose.OWNER_ACTION);
        } else {
            otpService.requestOtp(channel.destination(), OtpPurpose.OWNER_ACTION);
        }
=======
        otpService.requestOtp(phoneOf(accountId), OtpPurpose.OWNER_ACTION);
>>>>>>> origin/main
    }

    @Override
    public void consume(UUID accountId, String code) {
        if (code == null || code.isBlank()) {
            throw StepUpException.required();
        }
        String trimmed = code.trim();
        if (!SIX_DIGITS.matcher(trimmed).matches()) {
            throw StepUpException.invalid();
        }
        try {
<<<<<<< HEAD
            otpService.verifyOtp(channelOf(accountId).destination(), OtpPurpose.OWNER_ACTION, trimmed);
=======
            otpService.verifyOtp(phoneOf(accountId), OtpPurpose.OWNER_ACTION, trimmed);
>>>>>>> origin/main
        } catch (InvalidOtpException e) {
            throw StepUpException.invalid();
        } catch (OtpExpiredException e) {
            throw StepUpException.expired();
        }
    }

<<<<<<< HEAD
    /**
     * Where the code goes - always an address ON RECORD, never one the caller supplies. While SMS is not set up
     * ({@code app.phone.verification-required=false}) the code is emailed to the verified email; once SMS is live a
     * verified phone is preferred. Whichever is missing, the other is used.
     */
    private Channel channelOf(UUID accountId) {
        String phone = accountLookup.findPhonesByIds(List.of(accountId)).get(accountId);
        String email = accountLookup.findVerifiedEmailsByIds(List.of(accountId)).get(accountId);
        if (phone != null && (phoneProperties.verificationRequired() || email == null)) {
            return new Channel(phone, false);
        }
        if (email != null) {
            return new Channel(email, true);
        }
        throw StepUpException.noChannel();
    }

    private record Channel(String destination, boolean isEmail) {
=======
    private String phoneOf(UUID accountId) {
        return accountLookup.findPhonesByIds(List.of(accountId)).get(accountId);
>>>>>>> origin/main
    }
}
