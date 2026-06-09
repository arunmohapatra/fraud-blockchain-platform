package com.hackathon.blockchain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {
    private String from;
    private String to;
    private BigDecimal amount;
    private Integer riskScore;
    private String riskLevel;
    private String flagReasons;
}
