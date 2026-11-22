package com.firstfood.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local-only: prints the notice instead of sending an SMS. Notices carry no secrets. */
@Component
@Profile("local")
public class DevConsoleSecurityNoticeSender implements SecurityNoticeSender {

    @Override
    public void send(String phone, String message) {
        System.out.println("[DEV SECURITY NOTICE - local only] phone=" + phone + " message=" + message);
    }
}
