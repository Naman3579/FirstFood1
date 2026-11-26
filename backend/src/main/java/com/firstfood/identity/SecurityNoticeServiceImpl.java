package com.firstfood.identity;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SecurityNoticeServiceImpl implements SecurityNoticeService {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityNoticeServiceImpl.class);

    private final SecurityNoticeSender sender;
    private final AccountLookupService accountLookup;

    public SecurityNoticeServiceImpl(SecurityNoticeSender sender, AccountLookupService accountLookup) {
        this.sender = sender;
        this.accountLookup = accountLookup;
    }

    @Override
    public void notifyAfterCommit(UUID accountId, String message) {
        String phone;
        try {
            phone = accountLookup.findPhonesByIds(List.of(accountId)).get(accountId);
        } catch (RuntimeException e) {
            LOG.warn("Security notice not sent: could not look up the account's phone");
            return;
        }
        if (phone == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(phone, message);
                }
            });
        } else {
            deliver(phone, message);
        }
    }

    private void deliver(String phone, String message) {
        try {
            sender.send(phone, message);
        } catch (RuntimeException e) {
            // The phone number is deliberately not logged.
            LOG.warn("Security notice could not be delivered ({})", e.getClass().getSimpleName());
        }
    }
}
