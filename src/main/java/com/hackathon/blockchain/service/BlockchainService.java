package com.hackathon.blockchain.service;

import com.hackathon.blockchain.model.Account;
import com.hackathon.blockchain.model.Block;
import com.hackathon.blockchain.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BlockchainService {

    @Value("${blockchain.mining.difficulty:4}")
    private Integer miningDifficulty;

    private final List<Block> blockchain = new CopyOnWriteArrayList<>();
    private final List<Transaction> pendingTransactions = new CopyOnWriteArrayList<>();
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Transaction> transactionPool = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Create genesis block
        Block genesis = Block.createGenesisBlock();
        blockchain.add(genesis);
        log.info("Genesis block created: {}", genesis.getHash());

        // Create default accounts with balances
        createDefaultAccounts();
    }

    private void createDefaultAccounts() {
        String[] defaultAddresses = {
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
                "0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199",
                "0xdD2FD4581271e230360230F9337D5c0430Bf44C0",
                "0x90F79bf6EB2c4f870365E785982E1f101E93b906"
        };

        for (String address : defaultAddresses) {
            accounts.put(address, Account.builder()
                    .address(address)
                    .balance(new BigDecimal("100.0"))
                    .nonce(0L)
                    .build());
        }

        log.info("Created {} default accounts", defaultAddresses.length);
    }

    public Transaction submitTransaction(
            String from,
            String to,
            BigDecimal amount,
            Integer riskScore,
            String riskLevel,
            String flagReasons
    ) {
        Transaction transaction = Transaction.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .timestamp(Instant.now().toEpochMilli())
                .type(Transaction.TransactionType.PAYMENT)
                .status(Transaction.TransactionStatus.PENDING)
                .riskScore(riskScore)
                .riskLevel(riskLevel)
                .flagReasons(flagReasons)
                .gasUsed(21000)
                .build();

        String txHash = transaction.generateHash();
        transaction.setTransactionHash(txHash);

        pendingTransactions.add(transaction);
        transactionPool.put(txHash, transaction);

        log.info("Transaction submitted: {} from {} to {} amount {}",
                txHash, from, to, amount);

        // Auto-mine block if we have transactions
        if (pendingTransactions.size() >= 1) {
            mineBlock();
        }

        return transaction;
    }

    public Block mineBlock() {
        if (pendingTransactions.isEmpty()) {
            log.warn("No pending transactions to mine");
            return null;
        }

        Block lastBlock = getLatestBlock();
        Block newBlock = Block.builder()
                .index(lastBlock.getIndex() + 1)
                .timestamp(Instant.now().toEpochMilli())
                .previousHash(lastBlock.getHash())
                .transactions(new ArrayList<>(pendingTransactions))
                .difficulty(miningDifficulty)
                .nonce(0)
                .build();

        newBlock.setHash(newBlock.calculateHash());
        newBlock.mineBlock(miningDifficulty);

        // Update transaction statuses
        for (Transaction tx : newBlock.getTransactions()) {
            tx.setStatus(Transaction.TransactionStatus.CONFIRMED);
            tx.setBlockNumber(newBlock.getIndex());
            transactionPool.put(tx.getTransactionHash(), tx);
        }

        blockchain.add(newBlock);
        pendingTransactions.clear();

        log.info("New block mined! Block #{} with {} transactions",
                newBlock.getIndex(), newBlock.getTransactions().size());

        return newBlock;
    }

    public Block getLatestBlock() {
        return blockchain.get(blockchain.size() - 1);
    }

    public List<Block> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public Long getBlockNumber() {
        return (long) (blockchain.size() - 1);
    }

    public Transaction getTransaction(String txHash) {
        return transactionPool.get(txHash);
    }

    public List<Transaction> getAllTransactions() {
        return new ArrayList<>(transactionPool.values());
    }

    public List<Transaction> getPendingTransactions() {
        return new ArrayList<>(pendingTransactions);
    }

    public BigDecimal getBalance(String address) {
        Account account = accounts.get(address);
        return account != null ? account.getBalance() : BigDecimal.ZERO;
    }

    public Account getAccount(String address) {
        return accounts.computeIfAbsent(address, addr -> Account.builder()
                .address(addr)
                .balance(new BigDecimal("100.0"))
                .nonce(0L)
                .build());
    }

    public List<Account> getAllAccounts() {
        return new ArrayList<>(accounts.values());
    }

    public boolean isValidChain() {
        for (int i = 1; i < blockchain.size(); i++) {
            Block currentBlock = blockchain.get(i);
            Block previousBlock = blockchain.get(i - 1);

            // Validate current block hash
            if (!currentBlock.getHash().equals(currentBlock.calculateHash())) {
                log.error("Block {} hash is invalid", i);
                return false;
            }

            // Validate link to previous block
            if (!currentBlock.getPreviousHash().equals(previousBlock.getHash())) {
                log.error("Block {} link to previous block is invalid", i);
                return false;
            }
        }
        return true;
    }

    public Map<String, Object> getBlockchainInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("blockHeight", blockchain.size() - 1);
        info.put("totalBlocks", blockchain.size());
        info.put("totalTransactions", transactionPool.size());
        info.put("pendingTransactions", pendingTransactions.size());
        info.put("difficulty", miningDifficulty);
        info.put("latestBlockHash", getLatestBlock().getHash());
        info.put("isValid", isValidChain());
        info.put("totalAccounts", accounts.size());
        return info;
    }
}
