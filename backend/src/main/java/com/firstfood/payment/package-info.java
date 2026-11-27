/**
 * payment module: the money a customer reports paying against a subscription.
 *
 * A subscription can be paid in instalments, before it starts or long afterwards, so each payment is its own
 * {@link com.firstfood.payment.SubscriptionPayment} row linked to the subscription; nothing about the subscription
 * or its term snapshot changes. A customer-entered transaction id is a CLAIM: only payments that authorized staff
 * have VERIFIED count as paid. Other modules go through {@link com.firstfood.payment.PaymentService}.
 */
package com.firstfood.payment;
