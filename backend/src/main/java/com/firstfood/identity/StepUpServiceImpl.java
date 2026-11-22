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

    public StepUpServiceImpl(OtpService otpService, AccountLookupService accountLookup) {
        this.otpService = otpService;
        this.accountLookup = accountLookup;
    }

    @Override
    public void requestCode(UUID accountId) {
        otpService.requestOtp(phoneOf(accountId), OtpPurpose.OWNER_ACTION);
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
            otpService.verifyOtp(phoneOf(accountId), OtpPurpose.OWNER_ACTION, trimmed);
        } catch (InvalidOtpException e) {
            throw StepUpException.invalid();
        } catch (OtpExpiredException e) {
            throw StepUpException.expired();
        }
    }

    private String phoneOf(UUID accountId) {
        return accountLookup.findPhonesByIds(List.of(accountId)).get(accountId);
    }
}
