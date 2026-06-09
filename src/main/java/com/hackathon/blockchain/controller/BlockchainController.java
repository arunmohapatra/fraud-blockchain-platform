package com.hackathon.blockchain.controller;

import com.hackathon.blockchain.dto.TransactionRequest;
import com.hackathon.blockchain.dto.TransactionResponse;
import com.hackathon.blockchain.model.Account;
import com.hackathon.blockchain.model.Block;
import com.hackathon.blockchain.model.Transaction;
import com.hackathon.blockchain.service.BlockchainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/blockchain")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Blockchain", description = "Java Blockchain APIs")
public class BlockchainController {

    private final BlockchainService blockchainService;

    @GetMapping("/info")
    @Operation(summary = "Get blockchain information")
    public ResponseEntity<Map<String, Object>> getInfo() {
        return ResponseEntity.ok(blockchainService.getBlockchainInfo());
    }

    @GetMapping("/blocks")
    @Operation(summary = "Get all blocks")
    public ResponseEntity<List<Block>> getAllBlocks() {
        return ResponseEntity.ok(blockchainService.getBlockchain());
    }

    @GetMapping("/blocks/latest")
    @Operation(summary = "Get latest block")
    public ResponseEntity<Block> getLatestBlock() {
        return ResponseEntity.ok(blockchainService.getLatestBlock());
    }

    @GetMapping("/blocks/height")
    @Operation(summary = "Get current block height")
    public ResponseEntity<Map<String, Long>> getBlockHeight() {
        Map<String, Long> response = new HashMap<>();
        response.put("blockNumber", blockchainService.getBlockNumber());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/transactions")
    @Operation(summary = "Submit transaction to blockchain")
    public ResponseEntity<TransactionResponse> submitTransaction(
            @RequestBody TransactionRequest request
    ) {
        log.info("Received transaction request: {} -> {} amount {}",
                request.getFrom(), request.getTo(), request.getAmount());

        Transaction transaction = blockchainService.submitTransaction(
                request.getFrom(),
                request.getTo(),
                request.getAmount(),
                request.getRiskScore(),
                request.getRiskLevel(),
                request.getFlagReasons()
        );

        return ResponseEntity.ok(TransactionResponse.from(transaction));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Get all transactions")
    public ResponseEntity<List<TransactionResponse>> getAllTransactions() {
        List<TransactionResponse> transactions = blockchainService.getAllTransactions()
                .stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/transactions/{txHash}")
    @Operation(summary = "Get transaction by hash")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable String txHash) {
        Transaction transaction = blockchainService.getTransaction(txHash);
        if (transaction == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TransactionResponse.from(transaction));
    }

    @GetMapping("/transactions/pending")
    @Operation(summary = "Get pending transactions")
    public ResponseEntity<List<TransactionResponse>> getPendingTransactions() {
        List<TransactionResponse> transactions = blockchainService.getPendingTransactions()
                .stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(transactions);
    }

    @PostMapping("/mine")
    @Operation(summary = "Mine a new block")
    public ResponseEntity<Block> mineBlock() {
        Block block = blockchainService.mineBlock();
        if (block == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(block);
    }

    @GetMapping("/accounts")
    @Operation(summary = "Get all accounts")
    public ResponseEntity<List<Account>> getAllAccounts() {
        return ResponseEntity.ok(blockchainService.getAllAccounts());
    }

    @GetMapping("/accounts/{address}")
    @Operation(summary = "Get account details")
    public ResponseEntity<Account> getAccount(@PathVariable String address) {
        return ResponseEntity.ok(blockchainService.getAccount(address));
    }

    @GetMapping("/accounts/{address}/balance")
    @Operation(summary = "Get account balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String address) {
        BigDecimal balance = blockchainService.getBalance(address);
        Map<String, Object> response = new HashMap<>();
        response.put("address", address);
        response.put("balance", balance);
        response.put("unit", "ETH");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/validate")
    @Operation(summary = "Validate blockchain integrity")
    public ResponseEntity<Map<String, Boolean>> validateChain() {
        Map<String, Boolean> response = new HashMap<>();
        response.put("isValid", blockchainService.isValidChain());
        return ResponseEntity.ok(response);
    }
}
