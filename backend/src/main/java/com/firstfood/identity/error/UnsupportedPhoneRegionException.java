package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 400: the number is outside the countries OTP SMS is sent to (F-05). Depends only on the number, never on whether an
 * account exists, so it is not an account-enumeration signal.
 */
public class UnsupportedPhoneRegionException extends DomainException {

    public UnsupportedPhoneRegionException() {
        super("PHONE_REGION_NOT_SUPPORTED",
                "FirstFood currently supports Indian mobile numbers only. Enter a 10-digit number starting with 6, 7, 8 or 9.",
                HttpStatus.BAD_REQUEST);
    }
}
