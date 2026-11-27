package com.firstfood.identity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test-only {@link SecurityNoticeSender}: remembers every notice per phone so tests can assert who was told what. */
@Component
@Profile("test")
public class TestCapturingSecurityNoticeSender implements SecurityNoticeSender {

    private final Map<String, List<String>> noticesByPhone = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String message) {
        noticesByPhone.computeIfAbsent(phone, p -> new CopyOnWriteArrayList<>()).add(message);
    }

    public List<String> noticesFor(String phone) {
        return List.copyOf(noticesByPhone.getOrDefault(phone, List.of()));
    }
}
