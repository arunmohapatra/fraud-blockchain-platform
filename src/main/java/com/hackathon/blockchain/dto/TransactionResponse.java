package com.hackathon.blockchain.dto;

import com.hackathon.blockchain.model.Transaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {
    private String transactionHash;
    private String from;
    private String to;
    private BigDecimal amount;
    private Long timestamp;
    private String status;
    private Integer riskScore;
    private String riskLevel;
    private Long blockNumber;

    public static TransactionResponse from(Transaction transaction) {
        return TransactionResponse.builder()
                .transactionHash(transaction.getTransactionHash())
                .from(transaction.getFrom())
                .to(transaction.getTo())
                .amount(transaction.getAmount())
                .timestamp(transaction.getTimestamp())
                .status(transaction.getStatus().name())
                .riskScore(transaction.getRiskScore())
                .riskLevel(transaction.getRiskLevel())
                .blockNumber(transaction.getBlockNumber())
                .build();
    }
}
