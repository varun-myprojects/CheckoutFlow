package com.checkout.engine.config;

import com.checkout.engine.service.payment.MockPaymentGatewayAdapter;
import com.checkout.engine.service.payment.PaymentGateway;
import com.checkout.engine.service.payment.RazorpayGatewayAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class PaymentGatewayConfig {

    @Bean
    @Primary
    public PaymentGateway paymentGateway(
            @Value("${checkout.payment.gateway:mock}") String gatewayType,
            MockPaymentGatewayAdapter mockPaymentGatewayAdapter,
            RazorpayGatewayAdapter razorpayGatewayAdapter) {

        log.info("Configuring PaymentGateway with strategy: {}", gatewayType);

        if ("razorpay".equalsIgnoreCase(gatewayType)) {
            return razorpayGatewayAdapter;
        }

        return mockPaymentGatewayAdapter;
    }
}
