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
public class Transaction {

    private String transactionHash;
    private String from;
    private String to;
    private BigDecimal amount;
    private Long timestamp;
    private TransactionType type;
    private TransactionStatus status;
    private Integer riskScore;
    private String riskLevel;
    private String flagReasons;
    private Long blockNumber;
    private Integer gasUsed;

    public enum TransactionType {
        PAYMENT, APPROVAL, REJECTION, CANCELLATION
    }

    public enum TransactionStatus {
        PENDING, CONFIRMED, FAILED
    }

    public String generateHash() {
        return "0x" + Integer.toHexString(
                (from + to + amount + timestamp + type).hashCode()
        ).substring(0, Math.min(40, Integer.toHexString(
                (from + to + amount + timestamp + type).hashCode()).length()));
    }
}
