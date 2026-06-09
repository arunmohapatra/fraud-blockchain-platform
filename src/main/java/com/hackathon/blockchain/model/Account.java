package com.hackathon.blockchain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    private String address;

    @Builder.Default
    private BigDecimal balance = new BigDecimal("100.0");

    private Long nonce;
}
