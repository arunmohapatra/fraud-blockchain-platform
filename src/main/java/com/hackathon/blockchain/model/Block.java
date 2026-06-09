package com.hackathon.blockchain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Block {

    private Long index;
    private Long timestamp;
    private String previousHash;
    private String hash;
    private Integer nonce;
    private Integer difficulty;

    @Builder.Default
    private List<Transaction> transactions = new ArrayList<>();

    public String calculateHash() {
        try {
            String data = index + timestamp.toString() + previousHash +
                    transactions.toString() + nonce;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error calculating hash", e);
        }
    }

    public void mineBlock(int difficulty) {
        String target = new String(new char[difficulty]).replace('\0', '0');
        nonce = 0;

        while (!hash.substring(0, difficulty).equals(target)) {
            nonce++;
            hash = calculateHash();
        }

        System.out.println("Block mined! Hash: " + hash + " (nonce: " + nonce + ")");
    }

    public static Block createGenesisBlock() {
        Block genesis = Block.builder()
                .index(0L)
                .timestamp(Instant.now().toEpochMilli())
                .previousHash("0")
                .nonce(0)
                .difficulty(4)
                .transactions(new ArrayList<>())
                .build();

        genesis.setHash(genesis.calculateHash());
        return genesis;
    }
}
