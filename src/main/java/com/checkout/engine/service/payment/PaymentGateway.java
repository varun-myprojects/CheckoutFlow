package com.checkout.engine.service.payment;

import com.checkout.engine.dto.PaymentInitiationRequest;
import com.checkout.engine.dto.PaymentInitiationResult;
import com.checkout.engine.dto.RefundRequest;
import com.checkout.engine.dto.RefundResult;

public interface PaymentGateway {

    /**
     * Initiates payment for the given order request, creating a payment link/intent.
     *
     * @param request the initiation request with order and customer details
     * @return initiation result containing transactionReference and paymentUrl
     */
    PaymentInitiationResult initiatePayment(PaymentInitiationRequest request);

    /**
     * Verifies the authenticity of incoming webhook payloads against the gateway signature.
     *
     * @param payload the raw request body payload
     * @param signature the signature header provided by the gateway
     * @return true if the signature is valid, false otherwise
     */
    boolean verifyWebhookSignature(String payload, String signature);

    /**
     * Initiates a refund for a previously captured transaction.
     *
     * @param request the refund parameters
     * @return the refund result
     */
    RefundResult initiateRefund(RefundRequest request);

    /**
     * Identifies the gateway name (e.g. "MOCK", "RAZORPAY").
     *
     * @return the unique gateway identifier
     */
    String getGatewayName();

    /**
     * Reconstructs or looks up the payment URL for a given transaction reference.
     *
     * @param transactionReference the transaction reference
     * @return the payment URL
     */
    String buildPaymentUrl(String transactionReference);
}
