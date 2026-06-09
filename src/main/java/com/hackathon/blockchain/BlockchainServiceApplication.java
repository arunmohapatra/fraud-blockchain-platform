package com.hackathon.blockchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BlockchainServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlockchainServiceApplication.class, args);
        System.out.println("\n" +
                "=======================================================\n" +
                "  Java Blockchain Service Started\n" +
                "  Port: 8545\n" +
                "  Swagger UI: http://localhost:8545/swagger-ui.html\n" +
                "  Simulating Ethereum-compatible blockchain\n" +
                "=======================================================\n");
    }
}
